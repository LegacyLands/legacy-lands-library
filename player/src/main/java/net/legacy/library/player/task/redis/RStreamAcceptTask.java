package net.legacy.library.player.task.redis;

import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.annotation.RedisStreamAccepter;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
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
public class RStreamAcceptTask implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;

    public static RStreamAcceptTask of(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        return new RStreamAcceptTask(legacyPlayerDataService, basePackages, classLoaders, interval);
    }

    @Override
    public ScheduledTask<?> start() {
        Runnable runnable = () -> {
            RedisCacheServiceInterface redisCacheService = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = redisCacheService.getCache();
            RStream<Object, Object> rStream = redissonClient.getStream(RKeyUtil.getRStreamNameKey(legacyPlayerDataService));

            StreamReadArgs args = StreamReadArgs.greaterThan(StreamMessageId.ALL);
            Map<StreamMessageId, Map<Object, Object>> messages = rStream.read(args);

            Set<Class<?>> annotatedClasses = AnnotationScanner.findAnnotatedClasses(
                    ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders),
                    RedisStreamAccepter.class
            );

            // Get all msg
            for (Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry : messages.entrySet()) {
                // Get the class that uses @RedisStreamAccepter
                for (Class<?> clazz : annotatedClasses) {
                    try {
                        // get key (action name)
                        StreamMessageId key = streamMessageIdMapEntry.getKey();

                        // LPDS name and data
                        Map<Object, Object> value = streamMessageIdMapEntry.getValue();

                        // handle
                        for (Map.Entry<Object, Object> entry : value.entrySet()) {
                            RStreamAcceptInterface redisStreamAcceptInterface =
                                    (RStreamAcceptInterface) clazz.getDeclaredConstructor().newInstance();

                            Object key1 = entry.getKey();
                            Object value1 = entry.getValue();

                            if (!redisStreamAcceptInterface.getActionName().equals(key1) ||
                                    !redisStreamAcceptInterface.getTargetLegacyPlayerDataServiceName().equals(legacyPlayerDataService.getName())) {
                                continue;
                            }

                            redisStreamAcceptInterface.accept(rStream, streamMessageIdMapEntry);
                        }
                    } catch (Exception exception) {
                        Log.error("Failed to process Redis stream message", exception);
                    }
                }
            }
        };

        return scheduleAtFixedRate(runnable, interval, interval);
    }
}
