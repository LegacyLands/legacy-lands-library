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
import net.legacy.library.player.util.OutageLog;
import net.legacy.library.player.util.StreamRetentionUtil;
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
    /**
     * No longer filled: the read cursor delivers every message once. Kept so {@code getAcceptedId()} still exists.
     */
    private final Set<StreamMessageId> acceptedId;

    /**
     * How many messages one read takes from the stream.
     */
    private static final int READ_BATCH_SIZE = 1000;

    /**
     * Messages an accepter that does not record them has handled, delivered again every round while they stay.
     */
    private final Set<StreamMessageId> redeliveredId = Sets.newConcurrentHashSet();

    /**
     * The last message read; the next round reads from after it. It starts at the newest message when the task is
     * created: the state from before comes from L2 or the database when an entity is first needed, and replaying what
     * the stream still holds would apply old relationship commands again.
     */
    private volatile StreamMessageId readCursor;

    /**
     * Logs failed rounds once per outage.
     */
    private final OutageLog roundLog = new OutageLog("Reading the entity stream");

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
        this.readCursor = StreamRetentionUtil.lastMessageId(legacyEntityDataService.getL2Cache().getResource()
                .getStream(EntityRKeyUtil.getEntityStreamKey(legacyEntityDataService)));
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

                // Each service has its own RStream communication channel
                RStream<Object, Object> rStream = redissonClient.getStream(EntityRKeyUtil.getEntityStreamKey(legacyEntityDataService));

                // Expired messages and those past the retention are removed here: messages are read once, from a cursor
                StreamRetentionUtil.bound(rStream, legacyEntityDataService.getSyncSettings().getStreamRetention(), "timeout", false);

                // Messages an accepter that does not record them handled come again every round while they stay, as before
                for (StreamMessageId streamMessageId : List.copyOf(redeliveredId)) {
                    Map<StreamMessageId, Map<Object, Object>> redelivered = rStream.range(streamMessageId, streamMessageId);
                    if (redelivered.isEmpty()) {
                        redeliveredId.remove(streamMessageId);
                        continue;
                    }
                    dispatch(rStream, streamMessageId, redelivered.get(streamMessageId), false);
                }

                /*
                 * New messages are read once, from where the previous round stopped: messages stay in the stream until they
                 * expire, so reading it whole every round would cost as much as the stream is long
                 */
                while (true) {
                    Map<StreamMessageId, Map<Object, Object>> messages =
                            rStream.read(StreamReadArgs.greaterThan(readCursor).count(READ_BATCH_SIZE));
                    if (messages == null || messages.isEmpty()) {
                        break;
                    }

                    for (Map.Entry<StreamMessageId, Map<Object, Object>> entry : messages.entrySet()) {
                        readCursor = entry.getKey();
                        dispatch(rStream, entry.getKey(), entry.getValue(), true);
                    }

                    if (messages.size() < READ_BATCH_SIZE) {
                        break;
                    }
                }
                roundLog.succeeded();
            } catch (Exception exception) {
                // Every round fails while Redis is unreachable: the first failure is logged, the rest counted
                roundLog.failed(exception);
            }
        };

        return scheduleWithFixedDelayWithVirtualThread(runnable, interval.toMillis(), interval.toMillis(), TimeUnit.MILLISECONDS);
    }

    /**
     * Hands one message to the accepters it is meant for.
     *
     * <p>On its first delivery a message goes to every matching accepter. An accepter that does not record the messages
     * it handled ({@link EntityRStreamAccepterInterface#isRecordLimit()} is {@code false}) receives it again every
     * round while it stays in the stream, so such an accepter retries until it removes the message, as it did when the
     * whole stream was read every round; on those later deliveries only such accepters receive it.
     *
     * @param rStream         the stream the message is in
     * @param streamMessageId the message id
     * @param value           the message entries
     * @param firstDelivery   whether the message is read for the first time
     */
    private void dispatch(RStream<Object, Object> rStream, StreamMessageId streamMessageId, Map<Object, Object> value,
                          boolean firstDelivery) {

        // Validate message
        if (value.isEmpty()) {
            Log.error("Entity RStream message is empty! StreamMessageId: %s", streamMessageId);
            return;
        }

        // Check expiration time
        long expirationTime = Long.parseLong(value.getOrDefault("timeout", 0).toString());
        if (expirationTime > 0 && System.currentTimeMillis() > expirationTime) {
            rStream.remove(streamMessageId);
            redeliveredId.remove(streamMessageId);
            return;
        }

        // Process message entries
        String actionName = (String) value.get("actionName");
        String data = (String) value.get("data");
        Object publisher = value.get("publisher");
        boolean ownMessage = publisher != null && legacyEntityDataService.getInstanceId().toString().equals(publisher.toString());

        if (actionName == null || data == null) {
            Log.error("Entity RStream message has invalid format! StreamMessageId: %s", streamMessageId);
            return;
        }

        // Find and invoke matching accepters
        for (EntityRStreamAccepterInterface accepter : accepters) {
            String accepterActionName = accepter.getActionName();

            // Skip non-matching accepters
            if (accepterActionName != null && !accepterActionName.equals(actionName)) {
                continue;
            }

            // Skip messages this service published itself, for accepters that must not apply them
            if (ownMessage && !accepter.acceptOwnMessages()) {
                continue;
            }

            boolean recordLimit = accepter.isRecordLimit();
            if (recordLimit && !firstDelivery) {
                continue;
            }
            if (!recordLimit) {
                redeliveredId.add(streamMessageId);
            }

            invoke(accepter, rStream, streamMessageId, data, recordLimit);
        }
    }

    private void invoke(EntityRStreamAccepterInterface accepter, RStream<Object, Object> rStream,
                        StreamMessageId streamMessageId, String data, boolean recordLimit) {
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

                    return completableFuture;
                }
            }.start();
        }
    }

}
