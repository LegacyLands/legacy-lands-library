package net.legacy.library.player.task.redis;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
import org.redisson.api.RMapCache;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.stream.StreamAddArgs;

import java.time.Duration;
import java.util.concurrent.TimeUnit;

/**
 * @author qwq-dev
 * @since 2025-01-04 19:59
 */
@RequiredArgsConstructor
public class RStreamPubTask implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final RStreamTask rStreamTask;

    public static RStreamPubTask of(LegacyPlayerDataService legacyPlayerDataService, RStreamTask rStreamTask) {
        return new RStreamPubTask(legacyPlayerDataService, rStreamTask);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            RedissonClient redissonClient = legacyPlayerDataService.getL2Cache().getCache();

            RStream<String, String> rStream = redissonClient.getStream(
                    RKeyUtil.getRStreamNameKey(legacyPlayerDataService)
            );

            RMapCache<String, String> mapCache = redissonClient.getMapCache(
                    RKeyUtil.getTempRMapCacheKey(legacyPlayerDataService)
            );

            Duration expirationTime = rStreamTask.getExpirationTime();
            long millis = expirationTime.toMillis();

            mapCache.put(
                    rStreamTask.getActionName(), rStreamTask.getData(),
                    millis, TimeUnit.MILLISECONDS
            );

            // Set expiration time for the cache
            mapCache.put(
                    "expiration-time", String.valueOf(System.currentTimeMillis() + millis),
                    millis + 200, TimeUnit.MILLISECONDS
            );

            rStream.add(StreamAddArgs.entries(mapCache));
        });
    }
}
