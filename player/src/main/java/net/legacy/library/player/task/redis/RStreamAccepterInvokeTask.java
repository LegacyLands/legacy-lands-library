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
import net.legacy.library.player.util.RKeyUtil;
import net.legacy.library.player.util.OutageLog;
import net.legacy.library.player.util.StreamRetentionUtil;
import org.apache.commons.lang3.tuple.Pair;
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
 * Task responsible for invoking and managing {@link RStreamAccepterInterface}
 * implementations to process tasks from Redis streams.
 *
 * <p>This class scans specified base packages and class loaders for classes annotated
 * with {@link RStreamAccepterRegister}, instantiates them, and schedules periodic
 * checks on the Redis streams to process incoming tasks.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:06
 */
@Getter
public class RStreamAccepterInvokeTask implements TaskInterface<VirtualThreadScheduledFuture> {

    private final LegacyPlayerDataService legacyPlayerDataService;
    private final List<String> basePackages;
    private final List<ClassLoader> classLoaders;
    private final Duration interval;

    private final Set<Class<?>> annotatedClasses;
    private final Set<RStreamAccepterInterface> accepters;
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
     * created: the state from before is in L2 or the database, and replaying what the stream still holds would apply
     * old updates over it again.
     */
    private volatile StreamMessageId readCursor;

    /**
     * Logs failed rounds once per outage.
     */
    private final OutageLog roundLog = new OutageLog("Reading the player stream");

    /**
     * Constructs a new {@link RStreamAccepterInvokeTask}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     */
    public RStreamAccepterInvokeTask(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        this.legacyPlayerDataService = legacyPlayerDataService;
        this.basePackages = basePackages;
        this.classLoaders = classLoaders;
        this.interval = interval;
        this.annotatedClasses = Sets.newConcurrentHashSet();
        this.accepters = Sets.newConcurrentHashSet();
        this.acceptedId = Sets.newConcurrentHashSet();
        this.readCursor = StreamRetentionUtil.lastMessageId(
                legacyPlayerDataService.getL2Cache().getResource().getStream(RKeyUtil.getRStreamNameKey(legacyPlayerDataService)));
        updateAccepter();
    }

    /**
     * Factory method to create a new {@link RStreamAccepterInvokeTask}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to be used
     * @param basePackages            the list of base packages to scan for annotated accepters
     * @param classLoaders            the list of class loaders to use for scanning
     * @param interval                the interval at which to invoke the task processing
     * @return a new instance of {@link RStreamAccepterInvokeTask}
     */
    public static RStreamAccepterInvokeTask of(LegacyPlayerDataService legacyPlayerDataService, List<String> basePackages, List<ClassLoader> classLoaders, Duration interval) {
        return new RStreamAccepterInvokeTask(legacyPlayerDataService, basePackages, classLoaders, interval);
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
                accepters.add((RStreamAccepterInterface) clazz.getDeclaredConstructor().newInstance());
            } catch (Exception exception) {
                Log.error("Failed to add RStreamAccepter", exception);
            }
        });
    }

    /**
     * Starts the scheduled task that periodically checks and processes tasks from Redis streams.
     *
     * @return {@inheritDoc}
     */
    @Override
    public VirtualThreadScheduledFuture start() {
        Runnable runnable = () -> {
            // A failed round must not end the schedule: an exception thrown out of it would stop every later round
            try {
                RedisCacheServiceInterface redisCacheService = legacyPlayerDataService.getL2Cache();
                RedissonClient redissonClient = redisCacheService.getResource();

                /*
                 * Each LegacyPlayerDataService has its own RStream communication
                 * which will not contain data from other LegacyPlayerDataService
                 */
                RStream<Object, Object> rStream = redissonClient.getStream(RKeyUtil.getRStreamNameKey(legacyPlayerDataService));

                // Expired messages and those past the retention are removed here: messages are read once, from a cursor
                StreamRetentionUtil.bound(rStream, legacyPlayerDataService.getStreamRetention(), "expiration-time", true);

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
                 * New messages are read once, from where the previous round stopped: reading the stream whole every round
                 * costs as much as the stream is long, on every server, every round
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
     * it handled ({@link RStreamAccepterInterface#isRecordLimit()} is {@code false}) receives it again every round while
     * it stays in the stream, as it did when the whole stream was read every round; on those later deliveries only such
     * accepters receive it.
     *
     * @param rStream         the stream the message is in
     * @param streamMessageId the message id
     * @param value           the message entries
     * @param firstDelivery   whether the message is read for the first time
     */
    private void dispatch(RStream<Object, Object> rStream, StreamMessageId streamMessageId, Map<Object, Object> value,
                          boolean firstDelivery) {

        // The message published by RStreamPubTask is definitely a Pair
        if (value.isEmpty()) {
            Log.error("RStream message is empty! StreamMessageId: %s", streamMessageId);
            return;
        }

        // Greater than 2 because, in addition to the data, there is also an expiration time
        if (value.size() > 2) {
            Log.error("RStream message is not a pair! StreamMessageId: %s", streamMessageId);
            return;
        }

        long expirationTime =
                Long.parseLong(value.getOrDefault("expiration-time", 0).toString());

        if (expirationTime == 0 || System.currentTimeMillis() > expirationTime) {
            rStream.remove(streamMessageId);
            redeliveredId.remove(streamMessageId);
            return;
        }

        for (Map.Entry<Object, Object> entry : value.entrySet()) {
            Object key = entry.getKey();
            String left = key.toString();

            if (left.equals("expiration-time")) {
                continue;
            }

            String right = entry.getValue().toString();
            Pair<String, String> pair = Pair.of(left, right);

            // Get all registered accepters
            for (RStreamAccepterInterface accepter : accepters) {
                String actionName = accepter.getActionName();

                // Filter action name
                if (actionName != null && !actionName.equals(left)) {
                    continue;
                }

                boolean recordLimit = accepter.isRecordLimit();
                if (recordLimit && !firstDelivery) {
                    continue;
                }
                if (!recordLimit) {
                    redeliveredId.add(streamMessageId);
                }

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
                                    submitWithVirtualThreadAsync(() -> accepter.accept(rStream, streamMessageId, legacyPlayerDataService, pair.getRight()));

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
                                    schedule(() -> accepter.accept(rStream, streamMessageId, legacyPlayerDataService, pair.getRight())).getFuture();

                            return completableFuture;
                        }
                    }.start();
                }
            }
        }
    }

}
