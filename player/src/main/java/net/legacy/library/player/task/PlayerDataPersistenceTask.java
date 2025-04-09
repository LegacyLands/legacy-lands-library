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
            RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = l2Cache.getResource();

            // Sync L1 cache to L2 cache
            L1ToL2PlayerDataSyncTask.of(legacyPlayerDataService).start();

            // Persistence
            String lockKey = RKeyUtil.getRLPDSKey(legacyPlayerDataService, "persistence-lock");
            RLock lock = redissonClient.getLock(lockKey);

            try {
                // Exclusive
                if (!lock.tryLock(lockSettings.getWaitTime(), lockSettings.getLeaseTime(), lockSettings.getTimeUnit())) {
                    throw new RuntimeException("Could not acquire lock: " + lock.getName());
                }

                try {
                    Datastore datastore = legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore();

                    /*
                     * Get all LPDS key (name + "-rlpds-*") and deserialize and save all LegacyPlayerData
                     */
                    RKeys keys = redissonClient.getKeys();
                    KeysScanOptions keysScanOptions =
                            KeysScanOptions.defaults()
                                    .pattern(RKeyUtil.getPlayerKeyPattern(legacyPlayerDataService))
                                    .limit(limit);

                    for (String string : keys.getKeys(keysScanOptions)) {
                        if (keys.getType(string) != RType.OBJECT) {
                            continue;
                        }

                        String legacyPlayerDataString =
                                l2Cache.getWithType(client -> client.getBucket(string).get(), () -> "", null, false);

                        if (!legacyPlayerDataString.isEmpty()) {
                            datastore.save(SimplixSerializer.deserialize(legacyPlayerDataString, LegacyPlayerData.class));

                            // Set TTL for this player data if custom TTL is provided 
                            // or if it currently has no TTL
                            if (ttl != null) {
                                boolean expireSuccess = redissonClient.getBucket(string).expire(ttl);
                                if (!expireSuccess) {
                                    Log.warn(String.format("Failed to set custom TTL for player data key: %s", string));
                                }
                            } else {
                                // Check if key has no TTL and set default if needed
                                long currentTtl = redissonClient.getBucket(string).remainTimeToLive();
                                if (currentTtl < 0) {
                                    boolean expireSuccess = redissonClient.getBucket(string).expire(LegacyPlayerDataService.DEFAULT_TTL_DURATION);
                                    if (!expireSuccess) {
                                        Log.warn(String.format("Failed to set default TTL for player data key: %s", string));
                                    }
                                }
                            }
                        } else {
                            Log.error("The key value is not expected to be null, this should not happen!! key: " + string);
                        }
                    }
                } finally {
                    // Ensure the lock is always released
                    lock.forceUnlock();
                }
            } catch (InterruptedException exception) {
                Log.error(exception);
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                Log.error(exception);
            }
        });
    }
}