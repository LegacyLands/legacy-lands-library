package net.legacy.library.player.task.redis;

import com.google.common.collect.Sets;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.Getter;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
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
public class RStreamAccepterTask implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;

    private final Set<Class<?>> annotatedClasses;
    private final Set<RStreamAccepterInterface> accepters;
    private final Set<StreamMessageId> acceptedId;

    public RStreamAccepterTask(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        this.legacyPlayerDataService = legacyPlayerDataService;
        this.basePackages = basePackages;
        this.classLoaders = classLoaders;
        this.interval = interval;
        this.annotatedClasses = Sets.newConcurrentHashSet();
        this.accepters = Sets.newConcurrentHashSet();
        this.acceptedId = Sets.newConcurrentHashSet();
        updateAccepter();
    }

    public static RStreamAccepterTask of(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        return new RStreamAccepterTask(legacyPlayerDataService, basePackages, classLoaders, interval);
    }

    public void updateAccepter() {
        annotatedClasses.clear();
        annotatedClasses.addAll(AnnotationScanner.findAnnotatedClasses(
                ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders),
                RStreamAccepterRegister.class
        ));

        accepters.clear();
        annotatedClasses.forEach(clazz -> {
            try {
                accepters.add((RStreamAccepterInterface) clazz.getDeclaredConstructor().newInstance());
            } catch (Exception exception) {
                Log.error("Failed to add RStreamAccepter", exception);
            }
        });
    }

    @Override
    public ScheduledTask<?> start() {
        Runnable runnable = () -> {
            RedisCacheServiceInterface redisCacheService = legacyPlayerDataService.getL2Cache();
            RedissonClient redissonClient = redisCacheService.getCache();
            RStream<Object, Object> rStream = redissonClient.getStream(RKeyUtil.getRStreamNameKey(legacyPlayerDataService));

            StreamReadArgs args = StreamReadArgs.greaterThan(StreamMessageId.ALL);
            Map<StreamMessageId, Map<Object, Object>> messages = rStream.read(args);

            // Get all msg
            for (Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry : messages.entrySet()) {
                // LPDS name and data
                StreamMessageId streamMessageId = streamMessageIdMapEntry.getKey();
                Map<Object, Object> value = streamMessageIdMapEntry.getValue();

                if (acceptedId.contains(streamMessageId)) {
                    continue;
                }

                // Get all registed accepter
                for (RStreamAccepterInterface accepter : accepters) {
                    // handle
                    for (Map.Entry<Object, Object> entry : value.entrySet()) {
                        Object key1 = entry.getKey();

                        if (!accepter.getActionName().equals(key1) ||
                                !accepter.getTargetLegacyPlayerDataServiceName().equals(legacyPlayerDataService.getName())) {
                            continue;
                        }

                        // New thread async accept
                        ScheduledTask<?> schedule =
                                schedule(() -> accepter.accept(rStream, streamMessageIdMapEntry));

                        if (accepter.isRecodeLimit()) {
                            schedule.getFuture().whenComplete(
                                    (result, throwable) -> acceptedId.add(streamMessageId)
                            );
                        }
                    }
                }
            }
        };

        return scheduleAtFixedRate(runnable, interval, interval);
    }
}
