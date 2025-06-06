package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import io.fairyproject.mc.scheduler.MCScheduler;
import lombok.RequiredArgsConstructor;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
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
@RequiredArgsConstructor
public class ResilientRStreamAccepter implements RStreamAccepterInterface {
    private final RStreamAccepterInterface delegate;
    private final FailureHandler failureHandler;
    private final ScheduledExecutorService scheduler;

    /**
     * Map to track retry attempts for each message
     */
    private final ConcurrentMap<StreamMessageId, Integer> retryAttempts = new ConcurrentHashMap<>();

    /**
     * Creates a resilient wrapper with default failure handling.
     *
     * <p>Uses {@link FailureHandler#RETRY_ALWAYS} which implements a standard
     * retry policy with exponential backoff and logging compensation action.
     *
     * @param accepter  the original stream accepter to wrap with resilience
     * @param scheduler the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientRStreamAccepter} with default failure handling
     */
    public static ResilientRStreamAccepter wrap(RStreamAccepterInterface accepter, ScheduledExecutorService scheduler) {
        return new ResilientRStreamAccepter(accepter, FailureHandler.RETRY_ALWAYS, scheduler);
    }

    /**
     * Creates a resilient wrapper with custom failure handling.
     *
     * <p>Allows full control over retry behavior and compensation actions
     * through the provided failure handler.
     *
     * @param accepter       the original stream accepter to wrap with resilience
     * @param failureHandler the failure handler that defines retry and compensation behavior
     * @param scheduler      the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientRStreamAccepter} with the specified failure handling
     */
    public static ResilientRStreamAccepter wrap(RStreamAccepterInterface accepter,
                                                FailureHandler failureHandler,
                                                ScheduledExecutorService scheduler) {
        return new ResilientRStreamAccepter(accepter, failureHandler, scheduler);
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
            retryAttempts.remove(streamMessageId);
        } catch (Exception exception) {
            handleFailure(exception, rStream, streamMessageId, legacyPlayerDataService, data);
        }
    }

    private void handleFailure(Exception exception, RStream<Object, Object> rStream,
                               StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService,
                               String data) {
        int currentAttempt = retryAttempts.compute(streamMessageId, (id, attempts) ->
                attempts == null ? 1 : attempts + 1
        );

        FailureContext context = FailureContext.builder()
                .exception(exception)
                .stream(rStream)
                .messageId(streamMessageId)
                .taskData(data)
                .actionName(getActionName())
                .attemptNumber(currentAttempt)
                .maxAttempts(3) // This could be configurable
                .failureTimestamp(System.currentTimeMillis())
                .build();

        FailureHandlingResult result = failureHandler.handle(context);

        if (result.isShouldRetry()) {
            scheduleRetry(result.getRetryDelay(), rStream, streamMessageId, legacyPlayerDataService, data);
        } else {
            // Give up and execute compensation
            retryAttempts.remove(streamMessageId);
            executeCompensation(result.getCompensationAction(), context);
        }
    }

    private void scheduleRetry(Duration delay, RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                               LegacyPlayerDataService legacyPlayerDataService, String data) {

        Log.info("Scheduling retry for message %s after delay of %sms",
                streamMessageId, delay.toMillis());

        scheduler.schedule(() -> {
            executeWithResilience(rStream, streamMessageId, legacyPlayerDataService, data);
        }, delay.toMillis(), TimeUnit.MILLISECONDS);
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
     * Gets the current retry count for a message
     */
    public int getRetryCount(StreamMessageId messageId) {
        return retryAttempts.getOrDefault(messageId, 0);
    }

    /**
     * Clears retry tracking for a specific message
     */
    public void clearRetryTracking(StreamMessageId messageId) {
        retryAttempts.remove(messageId);
    }

    /**
     * Gets the total number of messages currently being tracked for retries
     */
    public int getTrackedMessageCount() {
        return retryAttempts.size();
    }
}