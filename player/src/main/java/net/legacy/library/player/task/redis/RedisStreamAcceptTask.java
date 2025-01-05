package net.legacy.library.player.task.redis;

import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.annotation.RedisStreamAccept;
import net.legacy.library.player.service.LegacyPlayerDataService;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadArgs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:06
 */
@Getter
@RequiredArgsConstructor
public class RedisStreamAcceptTask implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final String streamName;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;

    public static RedisStreamAcceptTask of(LegacyPlayerDataService legacyPlayerDataService, String streamName, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        return new RedisStreamAcceptTask(legacyPlayerDataService, streamName, basePackages, classLoaders, interval);
    }

    @Override
    public ScheduledTask<?> start() {
        Runnable runnable = () -> {
            RedisCacheServiceInterface redisCacheService = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = redisCacheService.getCache();
            RStream<Object, Object> rStream = redissonClient.getStream(streamName);

            StreamReadArgs args = StreamReadArgs.greaterThan(StreamMessageId.ALL);
            Map<StreamMessageId, Map<Object, Object>> messages = rStream.read(args);

            Set<Class<?>> annotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders),
                    RedisStreamAccept.class
            );

            for (Class<?> clazz : annotatedClasses) {
                try {
                    for (Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry : messages.entrySet()) {
                        StreamMessageId key = streamMessageIdMapEntry.getKey();
                        Map<Object, Object> value = streamMessageIdMapEntry.getValue();

                        RedisStreamAcceptInterface redisStreamAcceptInterface =
                                (RedisStreamAcceptInterface) clazz.getDeclaredConstructor().newInstance();

                        redisStreamAcceptInterface.accept(value);
                    }
                } catch (Exception exception) {
                    Log.error("Failed to process Redis stream message", exception);
                }
            }
        };

        return scheduleAtFixedRate(runnable, interval, interval);
    }
}
