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
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.resilience.ResilienceFactory;
import net.legacy.library.player.task.redis.resilience.ResilientRStreamAccepter;
import net.legacy.library.player.util.RKeyUtil;
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
 * Enhanced version of {@link RStreamAccepterInvokeTask} that provides resilient error handling
 * for stream accepter operations. This task wraps stream accepters with resilience capabilities
 * including retry logic and compensation actions.
 *
 * <p>This class maintains backward compatibility while adding structured failure handling
 * to prevent inconsistent states and improve reliability in distributed environments.
 *
 * @author qwq-dev
 * @since 2025-06-06 16:30
 */
@Getter
public class ResilientRStreamAccepterInvokeTask implements TaskInterface<VirtualThreadScheduledFuture> {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;
    private final boolean enableResilience;

    private final Set<Class<?>> annotatedClasses;
    private final Set<RStreamAccepterInterface> accepters;
    private final Set<StreamMessageId> acceptedId;

    /**
     * Constructs a new resilient RStream accepter invoke task.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @param enableResilience        whether to wrap accepters with resilience capabilities
     */
    public ResilientRStreamAccepterInvokeTask(LegacyPlayerDataService legacyPlayerDataService,
                                              List<String> basePackages,
                                              List<ClassLoader> classLoaders,
                                              Duration interval,
                                              boolean enableResilience) {
        this.legacyPlayerDataService = legacyPlayerDataService;
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
     * Factory method to create a new resilient RStream accepter invoke task.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @param enableResilience        whether to enable resilience features
     * @return a new instance of {@link ResilientRStreamAccepterInvokeTask}
     */
    public static ResilientRStreamAccepterInvokeTask of(LegacyPlayerDataService legacyPlayerDataService,
                                                        List<String> basePackages,
                                                        List<ClassLoader> classLoaders,
                                                        Duration interval,
                                                        boolean enableResilience) {
        return new ResilientRStreamAccepterInvokeTask(legacyPlayerDataService, basePackages, classLoaders, interval, enableResilience);
    }

    /**
     * Factory method to create a resilient task with resilience enabled by default.
     *
     * <p>This is a convenience method that calls {@link #of} with resilience enabled.
     * All discovered stream accepters will be automatically wrapped with default
     * resilience capabilities (3 retries with exponential backoff).
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @return a new instance of {@link ResilientRStreamAccepterInvokeTask} with resilience enabled
     */
    public static ResilientRStreamAccepterInvokeTask ofResilient(LegacyPlayerDataService legacyPlayerDataService,
                                                                 List<String> basePackages,
                                                                 List<ClassLoader> classLoaders,
                                                                 Duration interval) {
        return of(legacyPlayerDataService, basePackages, classLoaders, interval, true);
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
     * {@link RStreamAccepterRegister}, instantiates them, and adds them to the accepter set.
     * If resilience is enabled, wraps them with resilient wrappers.
     */
    public void updateAccepter() {
        annotatedClasses.clear();
        annotatedClasses.addAll(AnnotationScanner.findAnnotatedClasses(
                ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders),
                RStreamAccepterRegister.class
        ));

        accepters.clear();
        annotatedClasses.forEach(clazz -> {
            try {
                RStreamAccepterInterface accepter = (RStreamAccepterInterface) clazz.getDeclaredConstructor().newInstance();

                if (enableResilience) {
                    // Wrap with resilience capabilities
                    ResilientRStreamAccepter resilientAccepter = ResilienceFactory.createDefault(accepter);
                    accepters.add(resilientAccepter);
                    Log.info("Wrapped RStreamAccepter %s with resilience capabilities", clazz.getSimpleName());
                } else {
                    // Use original accepter
                    accepters.add(accepter);
                }
            } catch (Exception exception) {
                Log.error("Failed to add RStreamAccepter", exception);
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
                RedisCacheServiceInterface redisCacheService = legacyPlayerDataService.getL2Cache();
                RedissonClient redissonClient = redisCacheService.getResource();

                /*
                 * Each LegacyPlayerDataService has its own RStream communication
                 * which will not contain data from other LegacyPlayerDataService
                 */
                RStream<Object, Object> rStream = redissonClient.getStream(RKeyUtil.getRStreamNameKey(legacyPlayerDataService));

                StreamReadArgs args = StreamReadArgs.greaterThan(StreamMessageId.ALL);
                Map<StreamMessageId, Map<Object, Object>> messages = rStream.read(args);

                // Get all messages
                for (Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry : messages.entrySet()) {
                    StreamMessageId streamMessageId = streamMessageIdMapEntry.getKey();
                    Map<Object, Object> value = streamMessageIdMapEntry.getValue();

                    if (acceptedId.contains(streamMessageId)) {
                        continue;
                    }

                    // Validate message format
                    if (value.isEmpty()) {
                        Log.error("RStream message is empty! StreamMessageId: " + streamMessageId);
                        continue;
                    }

                    if (value.size() > 2) {
                        Log.error("RStream message is not a pair! StreamMessageId: " + streamMessageId);
                        continue;
                    }

                    // Check expiration
                    long expirationTime = Long.parseLong(value.getOrDefault("expiration-time", 0).toString());
                    if (expirationTime == 0 || System.currentTimeMillis() > expirationTime) {
                        rStream.remove(streamMessageId);
                        continue;
                    }

                    // Process each entry in the message
                    for (Map.Entry<Object, Object> entry : value.entrySet()) {
                        Object key = entry.getKey();
                        String left = key.toString();

                        if (left.equals("expiration-time")) {
                            continue;
                        }

                        String right = entry.getValue().toString();

                        // Get all registered accepters
                        for (RStreamAccepterInterface accepter : accepters) {
                            String accepterActionName = accepter.getActionName();

                            // Filter action name
                            if (accepterActionName != null && !accepterActionName.equals(left)) {
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
                                                submitWithVirtualThreadAsync(() -> accepter.accept(rStream, streamMessageId, legacyPlayerDataService, right));

                                        if (recordLimit) {
                                            completableFuture.whenComplete((aVoid, throwable) -> acceptedId.add(streamMessageId));
                                        }

                                        return completableFuture;
                                    }
                                }.start();
                            } else {
                                new TaskInterface<CompletableFuture<?>>() {
                                    @Override
                                    public MCScheduler getMCScheduler() {
                                        return accepter.getMCScheduler();
                                    }

                                    @Override
                                    public CompletableFuture<?> start() {
                                        CompletableFuture<?> completableFuture =
                                                schedule(() -> accepter.accept(rStream, streamMessageId, legacyPlayerDataService, right)).getFuture();

                                        if (recordLimit) {
                                            completableFuture.whenComplete((aVoid, throwable) -> acceptedId.add(streamMessageId));
                                        }

                                        return completableFuture;
                                    }
                                }.start();
                            }
                        }
                    }
                }
            } catch (Exception exception) {
                Log.error("Error during Redis stream processing", exception);
            }
        };

        return scheduleAtFixedRateWithVirtualThread(runnable, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }
}