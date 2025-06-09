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
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

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
     * Executes the persistence task, transferring player data from L2 cache to the database.
     *
     * <p>Acquires an exclusive lock to prevent concurrent modifications, iterates through the
     * Redis cache keys related to player data, deserializes the data, and saves it to the database.
     * Ensures that only valid and non-expired data is persisted.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            try {
                // Sync L1 cache to L2 cache first
                L1ToL2PlayerDataSyncTask.of(legacyPlayerDataService).start();

                // Perform persistence operation
                persistPlayerData();

            } catch (Exception exception) {
                Log.error("Error during player data persistence", exception);
            }
        });
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
                throw new RuntimeException("Could not acquire lock: " + lock.getName());
            }

            try {
                Datastore datastore = legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore();
                processPlayerDataInL2Cache(l2Cache, redissonClient, datastore);
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
     * Processes all player data from L2 cache and saves it to the database.
     *
     * <p>This method scans the Redis cache for player data keys, deserializes the data,
     * and persists it to the MongoDB database. It also maintains TTL for each entry in Redis,
     * either using the provided custom TTL or falling back to the default TTL.
     *
     * @param l2Cache        the Redis cache service
     * @param redissonClient the Redisson client
     * @param datastore      the MongoDB datastore
     */
    private void processPlayerDataInL2Cache(RedisCacheServiceInterface l2Cache,
                                            RedissonClient redissonClient,
                                            Datastore datastore) {
        // Get all LPDS keys and process them
        RKeys keys = redissonClient.getKeys();
        KeysScanOptions keysScanOptions = KeysScanOptions.defaults()
                .pattern(RKeyUtil.getPlayerKeyPattern(legacyPlayerDataService))
                .limit(limit);

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
                Log.error("The key value is not expected to be null, this should not happen!! key: %s", key);
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