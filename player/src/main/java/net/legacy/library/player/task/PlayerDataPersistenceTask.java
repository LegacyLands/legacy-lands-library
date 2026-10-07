package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.Datastore;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Task responsible for persisting player data from the L2 cache (Redis) to the database.
 *
 * <p>This task ensures that all player data cached in Redis is serialized and stored
 * in the underlying database, maintaining data consistency and durability.
 *
 * <p>The task acquires an exclusive lock to prevent concurrent persistence operations,
 * scans the Redis cache for relevant keys, deserializes the data, and saves it to the database.
 *
 * @author qwq-dev
 * @since 2025-01-04 12:53
 */
@RequiredArgsConstructor
public class PlayerDataPersistenceTask implements TaskInterface<CompletableFuture<?>> {

    private final LockSettings lockSettings;
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final int limit;
    private final Duration ttl;

    /**
     * Whether this is the last run before shutdown: it persists everything this server wrote to L2, and a database
     * step skipped is worth a warning, as nothing runs after it.
     */
    private boolean finalRun;

    /**
     * How many players one database write saves.
     */
    private static final int DATABASE_BATCH_SIZE = 1000;

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTask}.
     *
     * @param lockSettings            the settings for lock acquisition
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link PlayerDataPersistenceTask}
     */
    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, 1000, null);
    }

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTask}.
     *
     * @param lockSettings            the settings for lock acquisition
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @param limit                   the maximum number of player data entries to process
     * @return a new instance of {@link PlayerDataPersistenceTask}
     */
    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService, int limit) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, limit, null);
    }

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTask} with custom TTL.
     *
     * @param lockSettings            the settings for lock acquisition
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @param ttl                     the custom Time-To-Live duration to set for player data in Redis
     * @return a new instance of {@link PlayerDataPersistenceTask}
     */
    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService, Duration ttl) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, 1000, ttl);
    }

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTask} with custom TTL.
     *
     * @param lockSettings            the settings for lock acquisition
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @param limit                   the maximum number of player data entries to process
     * @param ttl                     the custom Time-To-Live duration to set for player data in Redis
     * @return a new instance of {@link PlayerDataPersistenceTask}
     */
    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService, int limit, Duration ttl) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, limit, ttl);
    }

    /**
     * Marks this run as the last before shutdown: it persists to the database everything this server wrote to L2,
     * however much, and a database step skipped because the lock stayed held is logged as a warning.
     *
     * @return this task
     */
    public PlayerDataPersistenceTask asFinalRun() {
        this.finalRun = true;
        return this;
    }

    /**
     * Executes the persistence task, transferring player data from L2 cache to the database.
     *
     * <p>Acquires an exclusive lock to prevent concurrent modifications, iterates through the
     * Redis cache keys related to player data, deserializes the data, and saves it to the database.
     * Ensures that only valid and non-expired data is persisted.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> start() {
        CompletableFuture<?> task = L1ToL2PlayerDataSyncTask.of(legacyPlayerDataService).start();

        task.whenComplete((result, throwable) -> {
            if (throwable != null) {
                Log.error("L1ToL2PlayerDataSyncTask failed to sync L1 cache to L2 cache: %s", throwable.getMessage());
            }
        });

        return task.thenCompose(ignored -> submitWithVirtualThreadAsync(() -> {
            try {
                persistPlayerData();
            } catch (Exception exception) {
                Log.error("PlayerDataPersistenceTask failed to persist player data. error: %s", exception.getMessage());
                throw new CompletionException("Failed to persist player data", exception);
            }
        }));
    }

    /**
     * Persists player data from L2 cache to the database.
     *
     * <p>This method acquires an exclusive lock to prevent concurrent operations,
     * then delegates to {@link #processPlayerDataInL2Cache} to handle the actual persistence.
     * It ensures proper error handling and lock release, even in case of exceptions.
     */
    private void persistPlayerData() {
        RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();
        RedissonClient redissonClient = l2Cache.getResource();

        // Persistence lock
        String lockKey = RKeyUtil.getRLPDSKey(legacyPlayerDataService, "persistence-lock");
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Try to acquire lock
            if (!lock.tryLock(lockSettings.getWaitTime(), lockSettings.getLeaseTime(), lockSettings.getTimeUnit())) {
                /*
                 * Another persistence, on this server or another, is writing to the database; what is waiting to be
                 * persisted stays in the pending set for the next run, of any server
                 */
                if (finalRun) {
                    Log.warn("Skipped the final persistence to the database: %s stayed held; %s players written to L2 wait to be persisted by another server",
                            lock.getName(), redissonClient.getSet(RKeyUtil.getPendingDatabaseKey(legacyPlayerDataService)).size());
                } else {
                    Log.debug("Skipped persisting to the database, another persistence holds %s", lock.getName());
                }
                return;
            }

            try {
                Datastore datastore = legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore();

                // What this server wrote first, then what the scan finds with the rest of the limit
                int persisted = persistPendingPlayerData(l2Cache, datastore);
                if (persisted < limit) {
                    processPlayerDataInL2Cache(l2Cache, redissonClient, datastore, limit - persisted);
                }
            } finally {
                // Ensure the lock is always released safely
                if (lock.isHeldByCurrentThread()) {
                    try {
                        lock.unlock();
                    } catch (Exception exception) {
                        Log.warn("Failed to unlock player persistence lock", exception);
                    }
                }
            }
        } catch (InterruptedException exception) {
            Log.error("Task interrupted during persistence", exception);
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            Log.error("Exception during player data persistence", exception);
        }
    }

    /**
     * Saves to the database the players written to L2 and not persisted yet, by any server: up to the limit, or all
     * of them on the final run, in batches.
     *
     * <p>Every write to L2 adds the player to a Redis set, so a player one server wrote and could not persist, such as
     * one that crashed, is persisted by the next run of any server. Each is saved as it is in L2, read a batch at a
     * time, or as cached here if L2 no longer holds it. A batch that fails goes back to the set for the next run.
     *
     * @param l2Cache   the Redis cache service
     * @param datastore the MongoDB datastore
     * @return how many players were taken from the set
     */
    private int persistPendingPlayerData(RedisCacheServiceInterface l2Cache, Datastore datastore) {
        RedissonClient redissonClient = l2Cache.getResource();
        RSet<String> pending = redissonClient.getSet(RKeyUtil.getPendingDatabaseKey(legacyPlayerDataService));
        Map<UUID, LegacyPlayerData> cached = legacyPlayerDataService.getL1Cache().getResource().asMap();
        int max = finalRun ? Integer.MAX_VALUE : limit;
        int taken = 0;

        while (taken < max) {
            Set<String> batch = pending.removeRandom(Math.min(DATABASE_BATCH_SIZE, max - taken));
            if (batch.isEmpty()) {
                break;
            }
            taken += batch.size();

            try {
                Map<String, String> keys = new HashMap<>();
                batch.forEach(uuid -> keys.put(uuid, RKeyUtil.getRLPDSKey(UUID.fromString(uuid), legacyPlayerDataService)));
                Map<String, Object> stored = redissonClient.getBuckets().get(keys.values().toArray(new String[0]));

                List<LegacyPlayerData> copies = new ArrayList<>();
                for (String uuid : batch) {
                    Object value = stored.get(keys.get(uuid));
                    LegacyPlayerData playerData = cached.get(UUID.fromString(uuid));
                    String serialized = value instanceof String string && !string.isEmpty() ? string
                            : playerData != null ? SimplixSerializer.serialize(playerData).toString() : "";
                    if (!serialized.isEmpty()) {
                        copies.add(SimplixSerializer.deserialize(serialized, LegacyPlayerData.class));
                    }
                }
                datastore.save(copies);
            } catch (Exception exception) {
                pending.addAll(batch);
                Log.error("Failed to persist %s players to the database; they are retried next run", exception, batch.size());
                break;
            }
        }
        return taken;
    }

    /**
     * Processes player data from L2 cache and saves it to the database.
     *
     * <p>This method scans the Redis cache for player data keys, deserializes the data,
     * and persists it to the MongoDB database. It also maintains TTL for each entry in Redis,
     * either using the provided custom TTL or falling back to the default TTL.
     *
     * @param l2Cache        the Redis cache service
     * @param redissonClient the Redisson client
     * @param datastore      the MongoDB datastore
     * @param budget         how many entries to process at most
     */
    private void processPlayerDataInL2Cache(RedisCacheServiceInterface l2Cache,
                                            RedissonClient redissonClient,
                                            Datastore datastore,
                                            int budget) {
        // Get all LPDS keys and process them
        RKeys keys = redissonClient.getKeys();
        KeysScanOptions keysScanOptions = KeysScanOptions.defaults()
                .pattern(RKeyUtil.getPlayerKeyPattern(legacyPlayerDataService))
                .limit(budget);

        for (String key : keys.getKeys(keysScanOptions)) {
            if (keys.getType(key) != RType.OBJECT) {
                continue;
            }

            String playerDataString = l2Cache.getWithType(
                    client -> client.getBucket(key).get(),
                    () -> "",
                    null,
                    false
            );

            if (playerDataString.isEmpty()) {
                Log.error("The Player data key value is not expected to be null, this should not happen!! key: %s", key);
                continue;
            }

            // Save to database
            datastore.save(SimplixSerializer.deserialize(playerDataString, LegacyPlayerData.class));

            // Update TTL if needed
            if (ttl != null) {
                TTLUtil.setTTLIfMissing(redissonClient, key, ttl.getSeconds());
            } else {
                TTLUtil.setTTLIfMissing(redissonClient, key, LegacyPlayerDataService.DEFAULT_TTL_DURATION.getSeconds());
            }
        }
    }

}