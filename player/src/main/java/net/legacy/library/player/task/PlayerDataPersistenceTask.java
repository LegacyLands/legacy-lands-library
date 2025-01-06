package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.Datastore;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.L1ToL2DataSyncTask;
import net.legacy.library.player.util.RKeyUtil;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

/**
 * @author qwq-dev
 * @since 2025-01-04 12:53
 */
@RequiredArgsConstructor
public class PlayerDataPersistenceTask implements TaskInterface {
    private final LockSettings lockSettings;
    private final LegacyPlayerDataService legacyPlayerDataService;

    public static PlayerDataPersistenceTask of(LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataPersistenceTask(lockSettings, legacyPlayerDataService);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = l2Cache.getCache();

            // Sync L1 cache to L2 cache
            L1ToL2DataSyncTask.of(legacyPlayerDataService).start();

            // Persistence
            String lockKey = RKeyUtil.getRLPDSKey(legacyPlayerDataService, "persistence-lock");
            RLock lock = redissonClient.getLock(lockKey);

            try {
                if (!lock.tryLock(lockSettings.getWaitTime(), lockSettings.getLeaseTime(), lockSettings.getTimeUnit())) {
                    throw new RuntimeException("Could not acquire lock: " + lock.getName());
                }

                try {
                    Datastore datastore = legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore();

                    /*
                     * Get all LPDS key (name + "-lpds-*") and deserialize and save all LPD
                     */
                    RKeys keys = redissonClient.getKeys();
                    for (String string : keys.getKeys(
                            KeysScanOptions.defaults()
                                    .pattern(RKeyUtil.getRLPDSKey(legacyPlayerDataService) + "*")
                    )) {
                        RType type = keys.getType(string);

                        if (type != RType.OBJECT) {
                            continue;
                        }

                        LegacyPlayerData legacyPlayerData = l2Cache.getWithType(
                                client -> SimplixSerializer.deserialize(client.getBucket(string).get().toString(), LegacyPlayerData.class), () -> null, null, false
                        );
                        datastore.save(legacyPlayerData);
                    }
                } finally {
                    if (lock.isHeldByCurrentThread()) {
                        lock.unlock();
                    }
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RuntimeException("Thread interrupted while trying to acquire lock.", exception);
            } catch (Exception exception) {
                // DEBUG print
//                exception.printStackTrace();
                throw new RuntimeException("Unexpected error during legacy player data migration.", exception);
            }
        });
    }
}
