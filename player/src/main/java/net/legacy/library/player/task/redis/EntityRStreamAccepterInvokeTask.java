package net.legacy.library.player.task.redis;

import com.google.common.collect.Sets;
import io.fairyproject.log.Log;
import io.fairyproject.mc.scheduler.MCScheduler;
import lombok.Getter;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamReadArgs;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task for invoking entity Redis stream accepters.
 *
 * <p>This task periodically checks for new messages in Redis streams and invokes
 * the appropriate accepters to process them. It handles the scanning of classes
 * annotated with {@link EntityRStreamAccepterRegister} and dispatches messages
 * to the corresponding accepters based on the action name.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Getter
public class EntityRStreamAccepterInvokeTask implements TaskInterface<ScheduledFuture<?>> {
    private final LegacyEntityDataService service;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration period;

    private final Set<Class<?>> annotatedClasses = Sets.newConcurrentHashSet();
    private final Set<EntityRStreamAccepterInterface> accepters = Sets.newConcurrentHashSet();
    private final Set<StreamMessageId> acceptedId = Sets.newConcurrentHashSet();

    /**
     * Constructs a new {@link EntityRStreamAccepterInvokeTask}.
     *
     * @param service      the {@link LegacyEntityDataService} instance to be used
     * @param basePackages the list of base packages to scan for annotated accepters
     * @param classLoaders the list of class loaders to use for scanning
     * @param period       the interval at which to invoke the task processing
     */
    public EntityRStreamAccepterInvokeTask(LegacyEntityDataService service,
                                           List<String> basePackages,
                                           List<ClassLoader> classLoaders,
                                           Duration period) {
        this.service = service;
        this.basePackages = basePackages;
        this.classLoaders = classLoaders;
        this.period = period;
        updateAccepter();
    }

    /**
     * Factory method to create a new {@link EntityRStreamAccepterInvokeTask}.
     *
     * @param service      the {@link LegacyEntityDataService} instance to be used
     * @param basePackages the list of base packages to scan for annotated accepters
     * @param classLoaders the list of class loaders to use for scanning
     * @param period       the interval at which to invoke the task processing
     * @return a new instance of {@link EntityRStreamAccepterInvokeTask}
     */
    public static EntityRStreamAccepterInvokeTask of(LegacyEntityDataService service,
                                                     List<String> basePackages,
                                                     List<ClassLoader> classLoaders,
                                                     Duration period) {
        return new EntityRStreamAccepterInvokeTask(service, basePackages, classLoaders, period);
    }

    /**
     * Updates the list of base packages to scan for annotated accepters and refreshes the accepter instances.
     *
     * @param basePackages the new list of base packages to scan
     */
    public void updateBasePackages(List<String> basePackages) {
        this.basePackages.clear();
        this.basePackages.addAll(basePackages);
        updateAccepter();
    }

    /**
     * Updates the list of class loaders to scan for annotated accepters and refreshes the accepter instances.
     *
     * @param classLoaders the new list of class loaders to use for scanning
     */
    public void updateClassLoaders(List<ClassLoader> classLoaders) {
        this.classLoaders.clear();
        this.classLoaders.addAll(classLoaders);
        updateAccepter();
    }

    /**
     * Scans for classes annotated with {@link EntityRStreamAccepterRegister}
     * and initializes the accepter set.
     */
    public void updateAccepter() {
        annotatedClasses.clear();
        annotatedClasses.addAll(AnnotationScanner.findAnnotatedClasses(
                ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders),
                EntityRStreamAccepterRegister.class
        ));

        accepters.clear();
        annotatedClasses.forEach(clazz -> {
            try {
                accepters.add((EntityRStreamAccepterInterface) clazz.getDeclaredConstructor().newInstance());
            } catch (Exception exception) {
                Log.error("Failed to add EntityRStreamAccepter", exception);
            }
        });
    }

    /**
     * Starts the scheduled task that periodically checks and processes tasks from Redis streams.
     *
     * @return {@inheritDoc}
     */
    @Override
    public ScheduledFuture<?> start() {
        Runnable runnable = () -> {
            RedisCacheServiceInterface redisCacheService = service.getL2Cache();
            RedissonClient redissonClient = redisCacheService.getResource();

            // Each service has its own RStream communication channel
            RStream<Object, Object> rStream = redissonClient.getStream(EntityRKeyUtil.getEntityStreamKey(service));

            StreamReadArgs args = StreamReadArgs.greaterThan(StreamMessageId.ALL);
            Map<StreamMessageId, Map<Object, Object>> messages = rStream.read(args);

            // Process all messages
            for (Map.Entry<StreamMessageId, Map<Object, Object>> entry : messages.entrySet()) {
                StreamMessageId streamMessageId = entry.getKey();
                Map<Object, Object> value = entry.getValue();

                // Skip already processed messages
                if (acceptedId.contains(streamMessageId)) {
                    continue;
                }

                // Validate message
                if (value.isEmpty()) {
                    Log.error("Entity RStream message is empty! StreamMessageId: " + streamMessageId);
                    continue;
                }

                // Check expiration time
                long expirationTime = Long.parseLong(value.getOrDefault("timeout", 0).toString());
                if (expirationTime > 0 && System.currentTimeMillis() > expirationTime) {
                    rStream.remove(streamMessageId);
                    continue;
                }

                // Process message entries
                String actionName = (String) value.get("actionName");
                String data = (String) value.get("data");

                if (actionName == null || data == null) {
                    Log.error("Entity RStream message has invalid format! StreamMessageId: " + streamMessageId);
                    continue;
                }

                // Find and invoke matching accepters
                for (EntityRStreamAccepterInterface accepter : accepters) {
                    String accepterActionName = accepter.getActionName();

                    // Skip non-matching accepters
                    if (accepterActionName != null && !accepterActionName.equals(actionName)) {
                        continue;
                    }

                    boolean recordLimit = accepter.isRecordLimit();
                    boolean useVirtualThread = accepter.useVirtualThread();

                    if (useVirtualThread) {
                        new TaskInterface<CompletableFuture<?>>() {
                            @Override
                            public ExecutorService getVirtualThreadPerTaskExecutor() {
                                return accepter.getVirtualThreadPerTaskExecutor();
                            }

                            @Override
                            public CompletableFuture<?> start() {
                                CompletableFuture<Void> completableFuture =
                                        submitWithVirtualThreadAsync(() -> accepter.accept(rStream, streamMessageId, service, data));

                                if (recordLimit) {
                                    completableFuture.whenComplete((aVoid, throwable) -> acceptedId.add(streamMessageId));
                                }

                                return completableFuture;
                            }
                        }.start();
                    } else {
                        // Use bukkit thread
                        new TaskInterface<CompletableFuture<?>>() {
                            @Override
                            public MCScheduler getMCScheduler() {
                                return accepter.getMCScheduler();
                            }

                            @Override
                            public CompletableFuture<?> start() {
                                CompletableFuture<?> completableFuture =
                                        schedule(() -> accepter.accept(rStream, streamMessageId, service, data)).getFuture();

                                if (recordLimit) {
                                    completableFuture.whenComplete((aVoid, throwable) -> acceptedId.add(streamMessageId));
                                }

                                return completableFuture;
                            }
                        }.start();
                    }
                }
            }
        };

        return scheduleAtFixedRateWithVirtualThread(runnable, period.getSeconds(), period.getSeconds(), TimeUnit.SECONDS);
    }
}