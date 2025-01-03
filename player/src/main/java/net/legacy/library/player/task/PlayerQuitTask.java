package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.scheduler.ScheduledTask;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.multi.FlexibleMultiLevelCacheService;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.KeyUtil;
import org.redisson.api.RedissonClient;

import java.util.UUID;
import java.util.concurrent.locks.Lock;
import java.util.function.Function;

/**
 * @author qwq-dev
 * @since 2025-01-03 18:51
 */
public class PlayerQuitTask implements TaskInterface {
    private final UUID uuid;
    private final LockSettings lockSettings;

    public PlayerQuitTask(UUID uuid, LockSettings lockSettings1) {
        this.uuid = uuid;
        this.lockSettings = lockSettings1;
    }

    public static PlayerQuitTask of(UUID uuid, LockSettings lockSettings) {
        return new PlayerQuitTask(uuid, lockSettings);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES.getCache().asMap().forEach((key, legacyPlayerDataService) -> legacyPlayerDataService.getFromL1Cache(uuid).ifPresent(legacyPlayerData -> {
            String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();

            FlexibleMultiLevelCacheService flexibleMultiLevelCacheService =
                    legacyPlayerDataService.getFlexibleMultiLevelCacheService();

            // Get L2 cache
            RedisCacheServiceInterface l2Cache =
                    flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, () -> new IllegalStateException("L2 cache not found"))
                            .getCacheWithType();

            /*
             * Save player data to L2 cache
             * Each LegacyPlayerDataService has a different Lock and ensures thread safety
             */
            String bucketKey = KeyUtil.getLegacyPlayerDataServiceKey(uuid, legacyPlayerDataService, "bucket-key");
            String nowCache = l2Cache.getWithType(client -> client.getBucket(bucketKey), null, null, false);

            if (nowCache.equals(serialized)) {
                return;
            }

            Function<RedissonClient, Lock> lockFunction =
                    cache -> cache.getLock(KeyUtil.getLegacyPlayerDataServiceKey(uuid, legacyPlayerDataService, "quit-lock"));

            l2Cache.execute(
                    lockFunction,
                    cache -> {
                        cache.getBucket(bucketKey).set(serialized);
                        return null;
                    },
                    lockSettings
            );
        })));
    }
}
