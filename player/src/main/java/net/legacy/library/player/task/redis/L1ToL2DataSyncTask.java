package net.legacy.library.player.task.redis;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.KeyUtil;
import org.redisson.api.RedissonClient;

import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * @author qwq-dev
 * @since 2025-01-05 12:10
 */
@RequiredArgsConstructor
public class L1ToL2DataSyncTask implements TaskInterface {
    private final UUID uuid;
    private final LegacyPlayerDataService legacyPlayerDataService;

    public static L1ToL2DataSyncTask of(LegacyPlayerDataService legacyPlayerDataService) {
        return new L1ToL2DataSyncTask(null, legacyPlayerDataService);
    }

    public static L1ToL2DataSyncTask of(UUID uuid, LegacyPlayerDataService legacyPlayerDataService) {
        return new L1ToL2DataSyncTask(uuid, legacyPlayerDataService);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> l1Cache =
                    legacyPlayerDataService.getL1Cache();
            RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = l2Cache.getCache();

            l1Cache.getCache().asMap().forEach((key, legacyPlayerData) -> {
                UUID uuid = legacyPlayerData.getUuid();

                if (this.uuid != null && !this.uuid.equals(key)) {
                    return;
                }

                String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();
                String bucketKey = KeyUtil.getLegacyPlayerDataServiceKey(key, legacyPlayerDataService, "bucket-key");
                String syncLockKey = KeyUtil.getLegacyPlayerDataServiceKey(key, legacyPlayerDataService, "l1-l2-sync-lock");

                l2Cache.execute(
                        client -> client.getLock(syncLockKey),
                        client -> {
                            client.getBucket(bucketKey).set(serialized);
                            return null;
                        },
                        LockSettings.of(5, 5, TimeUnit.MILLISECONDS)
                );
            });
        });
    }
}
