package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.Datastore;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.PlayerLauncher;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

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
public class PlayerDataPersistenceTask implements TaskInterface {
    private final LockSettings lockSettings;
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final int limit;

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTask}.
     *
     * @param lockSettings            the settings for lock acquisition
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link PlayerDataPersistenceTask}
     */
    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, 1000);
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
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService, limit);
    }

    /**
     * Executes the persistence task, transferring player data from L2 cache to the database.
     *
     * <p>Acquires an exclusive lock to prevent concurrent modifications, iterates through the
     * Redis cache keys related to player data, deserializes the data, and saves it to the database.
     * Ensures that only valid and non-expired data is persisted.
     */
    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = l2Cache.getCache();

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
                                    .pattern(RKeyUtil.getRLPDSKey(legacyPlayerDataService) + "*")
                                    .limit(limit);

                    for (String string : keys.getKeys(keysScanOptions)) {
                        if (keys.getType(string) != RType.OBJECT) {
                            continue;
                        }

                        String legacyPlayerDataString =
                                l2Cache.getWithType(client -> client.getBucket(string).get(), () -> "", null, false);

                        if (!legacyPlayerDataString.isEmpty()) {
                            datastore.save(SimplixSerializer.deserialize(legacyPlayerDataString, LegacyPlayerData.class));
                        } else {
                            Log.error("The key value is not expected to be null, this should not happen!! key: " + string);
                        }
                    }
                } finally {
                    // Ensure the lock is always released
                    lock.forceUnlock();
                }
            } catch (InterruptedException exception) {
                if (PlayerLauncher.DEBUG) {
                    //noinspection CallToPrintStackTrace
                    exception.printStackTrace();
                }

                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted while trying to acquire lock.", exception);
            } catch (Exception exception) {
                if (PlayerLauncher.DEBUG) {
                    //noinspection CallToPrintStackTrace
                    exception.printStackTrace();
                }

                throw new RuntimeException("Unexpected error during legacy player data migration.", exception);
            }
        });
    }
}