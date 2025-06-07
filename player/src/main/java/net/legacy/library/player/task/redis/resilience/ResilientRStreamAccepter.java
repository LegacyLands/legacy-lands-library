package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import io.fairyproject.mc.scheduler.MCScheduler;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A resilient wrapper for {@link RStreamAccepterInterface} that provides structured failure handling
 * with retry capabilities and compensation actions.
 *
 * <p>This wrapper intercepts exceptions during accept() method execution and applies
 * configurable retry policies and compensation strategies.
 *
 * @author qwq-dev
 * @since 2025-06-06 16:30
 */
@Getter
@RequiredArgsConstructor
public class ResilientRStreamAccepter implements RStreamAccepterInterface {
    private final RStreamAccepterInterface delegate;
    private final FailureHandler failureHandler;
    private final ScheduledExecutorService scheduler;
    private final RetryCounter retryCounter;

    /**
     * Creates a resilient wrapper with default failure handling.
     *
     * <p>Uses {@link FailureHandler#RETRY_ALWAYS} which implements a standard
     * retry policy with exponential backoff and logging compensation action.
     * Uses local retry counting by default.
     *
     * @param accepter  the original stream accepter to wrap with resilience
     * @param scheduler the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientRStreamAccepter} with default failure handling
     */
    public static ResilientRStreamAccepter wrap(RStreamAccepterInterface accepter, ScheduledExecutorService scheduler) {
        return new ResilientRStreamAccepter(accepter, FailureHandler.RETRY_ALWAYS, scheduler,
                LocalRetryCounter.create());
    }

    /**
     * Creates a resilient wrapper with custom failure handling.
     *
     * <p>Allows full control over retry behavior and compensation actions
     * through the provided failure handler. Uses local retry counting by default.
     *
     * @param accepter       the original stream accepter to wrap with resilience
     * @param failureHandler the failure handler that defines retry and compensation behavior
     * @param scheduler      the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientRStreamAccepter} with the specified failure handling
     */
    public static ResilientRStreamAccepter wrap(RStreamAccepterInterface accepter,
                                                FailureHandler failureHandler,
                                                ScheduledExecutorService scheduler) {
        return new ResilientRStreamAccepter(accepter, failureHandler, scheduler,
                LocalRetryCounter.create());
    }

    /**
     * Creates a resilient wrapper with a custom retry counter.
     *
     * <p>This factory method provides full control over retry counting strategy,
     * allowing the use of distributed, local, or hybrid counters based on
     * application requirements.
     *
     * @param accepter       the original stream accepter to wrap with resilience
     * @param failureHandler the failure handler that defines retry and compensation behavior
     * @param scheduler      the scheduler service to use for delayed retry execution
     * @param retryCounter   the retry counter implementation to use
     * @return a new {@link ResilientRStreamAccepter} with custom retry counting
     */
    public static ResilientRStreamAccepter wrapWithCounter(
            RStreamAccepterInterface accepter,
            FailureHandler failureHandler,
            ScheduledExecutorService scheduler,
            RetryCounter retryCounter) {
        return new ResilientRStreamAccepter(accepter, failureHandler, scheduler, retryCounter);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public String getActionName() {
        return delegate.getActionName();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public boolean isRecordLimit() {
        return delegate.isRecordLimit();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public boolean useVirtualThread() {
        return delegate.useVirtualThread();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public MCScheduler getMCScheduler() {
        return delegate.getMCScheduler();
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public ExecutorService getVirtualThreadPerTaskExecutor() {
        return delegate.getVirtualThreadPerTaskExecutor();
    }

    /**
     * {@inheritDoc}
     *
     * @param rStream                 {@inheritDoc}
     * @param streamMessageId         {@inheritDoc}
     * @param legacyPlayerDataService {@inheritDoc}
     * @param data                    {@inheritDoc}
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyPlayerDataService legacyPlayerDataService, String data) {
        executeWithResilience(rStream, streamMessageId, legacyPlayerDataService, data);
    }

    /**
     * Executes the accept method with resilience handling
     */
    private void executeWithResilience(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                                       LegacyPlayerDataService legacyPlayerDataService, String data) {
        try {
            // Attempt to execute the original accept method
            delegate.accept(rStream, streamMessageId, legacyPlayerDataService, data);

            // Success - clear any retry tracking
            String retryKey = generateRetryKey(streamMessageId, data);
            retryCounter.reset(retryKey);
        } catch (Exception exception) {
            handleFailure(exception, rStream, streamMessageId, legacyPlayerDataService, data);
        }
    }

    private void handleFailure(Exception exception, RStream<Object, Object> rStream,
                               StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService,
                               String data) {
        // Generate a unique key for this message
        String retryKey = generateRetryKey(streamMessageId, data);

        // Use the configured retry counter asynchronously
        retryCounter.increment(retryKey).whenComplete((currentAttempt, throwable) -> {
            if (throwable != null) {
                Log.error("Failed to increment retry counter for key: " + retryKey, throwable);
                // Fallback to local counter logic
                currentAttempt = 1;
            }

            processRetryDecision(exception, rStream, streamMessageId, legacyPlayerDataService,
                    data, retryKey, currentAttempt);
        });
    }

    private void processRetryDecision(Exception exception, RStream<Object, Object> rStream,
                                      StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService,
                                      String data, String retryKey, int currentAttempt) {

        FailureContext context = FailureContext.builder()
                .exception(exception)
                .stream(rStream)
                .messageId(streamMessageId)
                .taskData(data)
                .actionName(getActionName())
                .attemptNumber(currentAttempt)
                .maxAttempts(failureHandler.getRetryPolicy() != null ?
                        failureHandler.getRetryPolicy().getMaxAttempts() : 3)
                .failureTimestamp(System.currentTimeMillis())
                .build();

        FailureHandlingResult result = failureHandler.handle(context);

        if (result.isShouldRetry()) {
            scheduleRetry(result.getRetryDelay(), rStream, streamMessageId, legacyPlayerDataService, data);
        } else {
            // Give up and execute compensation
            retryCounter.reset(retryKey);
            executeCompensation(result.getCompensationAction(), context);
        }
    }

    private void scheduleRetry(Duration delay, RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                               LegacyPlayerDataService legacyPlayerDataService, String data) {

        Log.info("Scheduling retry for message %s after delay of %sms",
                streamMessageId, delay.toMillis());

        scheduler.schedule(() -> executeWithResilience(rStream, streamMessageId, legacyPlayerDataService, data), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void executeCompensation(CompensationAction compensation, FailureContext context) {
        if (compensation == null) {
            return;
        }

        try {
            compensation.execute(context);
        } catch (Exception compensationException) {
            Log.error("Compensation action failed for message %s",
                    context.getMessageId(), compensationException);
        }
    }

    /**
     * Generates a unique retry key for the given message.
     *
     * <p>The key format includes the action name, message ID, and a hash of the data
     * to ensure uniqueness even for messages with the same ID but different content.
     *
     * @param streamMessageId the message ID from the stream
     * @param data            the message data
     * @return a unique key for retry tracking
     */
    private String generateRetryKey(StreamMessageId streamMessageId, String data) {
        String actionName = getActionName() != null ? getActionName() : "default";
        int dataHash = data != null ? data.hashCode() : 0;
        return String.format("%s:%s:%d", actionName, streamMessageId.toString(), dataHash);
    }

    /**
     * Closes resources associated with this resilient accepter.
     *
     * <p>This method should be called during shutdown to clean up resources,
     * particularly when using distributed retry counters that may hold
     * connections to external systems.
     */
    public void close() {
        if (retryCounter != null) {
            retryCounter.close();
        }
    }
}