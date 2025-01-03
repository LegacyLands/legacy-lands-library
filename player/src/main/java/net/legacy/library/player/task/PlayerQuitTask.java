package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.scheduler.ScheduledTask;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.multi.FlexibleMultiLevelCacheService;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.LockKeyUtil;

import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-03 18:51
 */
public class PlayerQuitTask implements TaskInterface {
    private final UUID uuid;
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final LockSettings lockSettings;

    public PlayerQuitTask(UUID uuid, LegacyPlayerDataService legacyPlayerDataService, LockSettings lockSettings1) {
        this.uuid = uuid;
        this.legacyPlayerDataService = legacyPlayerDataService;
        this.lockSettings = lockSettings1;
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            legacyPlayerDataService.getFromL1Cache(uuid).ifPresent(legacyPlayerData -> {
                String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();

                FlexibleMultiLevelCacheService flexibleMultiLevelCacheService =
                        legacyPlayerDataService.getFlexibleMultiLevelCacheService();

                RedisCacheServiceInterface l2Cache =
                        flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, () -> new IllegalStateException("L2 cache not found"))
                                .getCacheWithType();

                l2Cache.execute(
                        cache -> cache.getLock(LockKeyUtil.getPlayerLockKey(uuid, "quit-lock")),
                        cache -> {
                            cache.getBucket(uuid.toString()).set(serialized);
                            return null;
                        },
                        lockSettings
                );
            });
        });
    }
}
