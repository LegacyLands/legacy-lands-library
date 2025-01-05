package net.legacy.library.player.task.redis;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
import org.apache.commons.lang3.tuple.Pair;
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
    private final Pair<String, String> data;
    private final Duration duration;

    public static RStreamPubTask of(LegacyPlayerDataService legacyPlayerDataService, Pair<String, String> data, Duration duration) {
        return new RStreamPubTask(legacyPlayerDataService, data, duration);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            RedissonClient redissonClient = legacyPlayerDataService.getL2Cache().getCache();

            RStream<String, String> rStream = redissonClient.getStream(
                    RKeyUtil.getRStreamNameKey(legacyPlayerDataService)
            );

            RMapCache<String, String> mapCache = redissonClient.getMapCache(
                    RKeyUtil.getRMapCacheKey(legacyPlayerDataService)
            );

            mapCache.put(data.getLeft(), data.getRight(), duration.toMillis(), TimeUnit.MILLISECONDS);
            rStream.add(StreamAddArgs.entries(mapCache));
        });
    }
}
