package net.legacy.library.player.task.redis;

import com.google.common.collect.Sets;
import io.fairyproject.log.Log;
import io.fairyproject.mc.scheduler.MCScheduler;
import lombok.Getter;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.commons.task.VirtualThreadScheduledFuture;
import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.resilience.ResilienceFactory;
import net.legacy.library.player.task.redis.resilience.ResilientEntityRStreamAccepter;
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
import java.util.concurrent.TimeUnit;

/**
 * Enhanced version of {@link EntityRStreamAccepterInvokeTask} that provides resilient error handling
 * for entity stream accepter operations. This task wraps entity stream accepters with resilience
 * capabilities including retry logic and compensation actions.
 *
 * <p>This class maintains backward compatibility while adding structured failure handling
 * to prevent inconsistent states and improve reliability in distributed environments.
 *
 * @author qwq-dev
 * @since 2025-06-06 16:30
 */
@Getter
public class ResilientEntityRStreamAccepterInvokeTask implements TaskInterface<VirtualThreadScheduledFuture> {

    private final LegacyEntityDataService legacyEntityDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;
    private final boolean enableResilience;

    private final Set<Class<?>> annotatedClasses;
    private final Set<EntityRStreamAccepterInterface> accepters;
    private final Set<StreamMessageId> acceptedId;

    /**
     * Constructs a new resilient entity RStream accepter invoke task.
     *
     * @param legacyEntityDataService the {@link LegacyEntityDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @param enableResilience        whether to wrap accepters with resilience capabilities
     */
    public ResilientEntityRStreamAccepterInvokeTask(LegacyEntityDataService legacyEntityDataService,
                                                    List<String> basePackages,
                                                    List<ClassLoader> classLoaders,
                                                    Duration interval,
                                                    boolean enableResilience) {
        this.legacyEntityDataService = legacyEntityDataService;
        this.basePackages = basePackages;
        this.classLoaders = classLoaders;
        this.interval = interval;
        this.enableResilience = enableResilience;
        this.annotatedClasses = Sets.newConcurrentHashSet();
        this.accepters = Sets.newConcurrentHashSet();
        this.acceptedId = Sets.newConcurrentHashSet();
        updateAccepter();
    }

    /**
     * Factory method to create a new resilient entity RStream accepter invoke task.
     *
     * @param legacyEntityDataService the {@link LegacyEntityDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @param enableResilience        whether to enable resilience features
     * @return a new instance of {@link ResilientEntityRStreamAccepterInvokeTask}
     */
    public static ResilientEntityRStreamAccepterInvokeTask of(LegacyEntityDataService legacyEntityDataService,
                                                              List<String> basePackages,
                                                              List<ClassLoader> classLoaders,
                                                              Duration interval,
                                                              boolean enableResilience) {
        return new ResilientEntityRStreamAccepterInvokeTask(legacyEntityDataService, basePackages, classLoaders, interval, enableResilience);
    }

    /**
     * Factory method to create a resilient task with resilience enabled by default.
     *
     * <p>This is a convenience method that calls {@link #of} with resilience enabled.
     * All discovered entity stream accepters will be automatically wrapped with default
     * resilience capabilities (3 retries with exponential backoff).
     *
     * @param legacyEntityDataService the {@link LegacyEntityDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @return a new instance of {@link ResilientEntityRStreamAccepterInvokeTask} with resilience enabled
     */
    public static ResilientEntityRStreamAccepterInvokeTask ofResilient(LegacyEntityDataService legacyEntityDataService,
                                                                       List<String> basePackages,
                                                                       List<ClassLoader> classLoaders,
                                                                       Duration interval) {
        return of(legacyEntityDataService, basePackages, classLoaders, interval, true);
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
     * Scans the specified base packages and class loaders for classes annotated with
     * {@link EntityRStreamAccepterRegister}, instantiates them, and adds them to the accepter set.
     * If resilience is enabled, wraps them with resilient wrappers.
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
                EntityRStreamAccepterInterface accepter = (EntityRStreamAccepterInterface) clazz.getDeclaredConstructor().newInstance();

                if (enableResilience) {
                    // Wrap with resilience capabilities
                    ResilientEntityRStreamAccepter resilientAccepter = ResilienceFactory.createDefault(accepter);
                    accepters.add(resilientAccepter);
                    Log.info("Wrapped EntityRStreamAccepter %s with resilience capabilities", clazz.getSimpleName());
                } else {
                    // Use original accepter
                    accepters.add(accepter);
                }
            } catch (Exception exception) {
                Log.error("Failed to add EntityRStreamAccepter", exception);
            }
        });
    }

    /**
     * Starts the scheduled task that periodically checks and processes tasks from Redis streams.
     * Uses the same core logic as the original implementation but with enhanced error handling.
     *
     * @return {@inheritDoc}
     */
    @Override
    public VirtualThreadScheduledFuture start() {
        Runnable runnable = () -> {
            try {
                RedisCacheServiceInterface redisCacheService = legacyEntityDataService.getL2Cache();
                RedissonClient redissonClient = redisCacheService.getResource();

                /*
                 * Each LegacyEntityDataService has its own RStream communication
                 * which will not contain data from other LegacyEntityDataService
                 */
                RStream<Object, Object> rStream = redissonClient.getStream(EntityRKeyUtil.getEntityStreamKey(legacyEntityDataService));

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
                        Log.error("Entity RStream message is empty! StreamMessageId: %s", streamMessageId);
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
                        Log.error("Entity RStream message has invalid format! StreamMessageId: %s", streamMessageId);
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
                                            submitWithVirtualThreadAsync(() -> accepter.accept(rStream, streamMessageId, legacyEntityDataService, data));

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
                                            schedule(() -> accepter.accept(rStream, streamMessageId, legacyEntityDataService, data)).getFuture();

                                    if (recordLimit) {
                                        completableFuture.whenComplete((aVoid, throwable) -> acceptedId.add(streamMessageId));
                                    }

                                    return completableFuture;
                                }
                            }.start();
                        }
                    }
                }
            } catch (Exception exception) {
                Log.error("Error during entity Redis stream processing", exception);
            }
        };

        return scheduleWithFixedDelayWithVirtualThread(runnable, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

}