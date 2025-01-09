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
import org.apache.commons.lang3.tuple.Pair;
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
public class RStreamAccepterInvokeTask implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;

    private final Set<Class<?>> annotatedClasses;
    private final Set<RStreamAccepterInterface> accepters;
    private final Set<StreamMessageId> acceptedId;

    public RStreamAccepterInvokeTask(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        this.legacyPlayerDataService = legacyPlayerDataService;
        this.basePackages = basePackages;
        this.classLoaders = classLoaders;
        this.interval = interval;
        this.annotatedClasses = Sets.newConcurrentHashSet();
        this.accepters = Sets.newConcurrentHashSet();
        this.acceptedId = Sets.newConcurrentHashSet();
        updateAccepter();
    }

    public static RStreamAccepterInvokeTask of(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        return new RStreamAccepterInvokeTask(legacyPlayerDataService, basePackages, classLoaders, interval);
    }

    public void updateBasePacakges(List<String> basePackages) {
        this.basePackages.clear();
        this.basePackages.addAll(basePackages);
        updateAccepter();
    }

    public void updateClassLoaders(List<ClassLoader> classLoaders) {
        this.classLoaders.clear();
        this.classLoaders.addAll(classLoaders);
        updateAccepter();
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

            /*
             * Each LegacyPlayerDataService has its own RStream communication
             * which will not contain data from other LegacyPlayerDataService
             */
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

                // The message published by RStreamPubTask is definitely a Pair
                if (value.isEmpty()) {
                    Log.error("RStream message is empty! StreamMessageId: " + streamMessageId);
                    continue;
                }

                for (Map.Entry<Object, Object> entry : value.entrySet()) {
                    String left = entry.getKey().toString();
                    String right = entry.getValue().toString();
                    Pair<String, String> pair = Pair.of(left, right);

                    // Get all registed accepter
                    for (RStreamAccepterInterface accepter : accepters) {
                        String actionName = accepter.getActionName();

                        // Filter action name
                        if (actionName != null && !actionName.equals(left)) {
                            continue;
                        }

                        // New thread async accept
                        ScheduledTask<?> schedule =
                                schedule(() -> accepter.accept(rStream, streamMessageId, legacyPlayerDataService, pair.getRight()));

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
