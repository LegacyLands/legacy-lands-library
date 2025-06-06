package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import io.fairyproject.mc.scheduler.MCScheduler;
import lombok.RequiredArgsConstructor;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A resilient wrapper for {@link EntityRStreamAccepterInterface} that provides structured failure handling
 * with retry capabilities and compensation actions.
 *
 * <p>This wrapper intercepts exceptions during accept() method execution and applies
 * configurable retry policies and compensation strategies.
 *
 * @author qwq-dev
 * @since 2025-06-06 16:30
 */
@RequiredArgsConstructor
public class ResilientEntityRStreamAccepter implements EntityRStreamAccepterInterface {
    private final EntityRStreamAccepterInterface delegate;
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
     * @param accepter  the original entity stream accepter to wrap with resilience
     * @param scheduler the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientEntityRStreamAccepter} with default failure handling
     */
    public static ResilientEntityRStreamAccepter wrap(EntityRStreamAccepterInterface accepter, ScheduledExecutorService scheduler) {
        return new ResilientEntityRStreamAccepter(accepter, FailureHandler.RETRY_ALWAYS, scheduler);
    }

    /**
     * Creates a resilient wrapper with custom failure handling.
     *
     * <p>Allows full control over retry behavior and compensation actions
     * through the provided failure handler.
     *
     * @param accepter       the original entity stream accepter to wrap with resilience
     * @param failureHandler the failure handler that defines retry and compensation behavior
     * @param scheduler      the scheduler service to use for delayed retry execution
     * @return a new {@link ResilientEntityRStreamAccepter} with the specified failure handling
     */
    public static ResilientEntityRStreamAccepter wrap(EntityRStreamAccepterInterface accepter,
                                                      FailureHandler failureHandler,
                                                      ScheduledExecutorService scheduler) {
        return new ResilientEntityRStreamAccepter(accepter, failureHandler, scheduler);
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
     * @param legacyEntityDataService {@inheritDoc}
     * @param data                    {@inheritDoc}
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyEntityDataService legacyEntityDataService, String data) {
        executeWithResilience(rStream, streamMessageId, legacyEntityDataService, data);
    }

    private void executeWithResilience(RStream<Object, Object> stream, StreamMessageId id,
                                       LegacyEntityDataService service, String data) {
        try {
            // Attempt to execute the original accept method
            delegate.accept(stream, id, service, data);

            // Success - clear any retry tracking
            retryAttempts.remove(id);
        } catch (Exception exception) {
            handleFailure(exception, stream, id, service, data);
        }
    }

    private void handleFailure(Exception exception, RStream<Object, Object> stream,
                               StreamMessageId id, LegacyEntityDataService service, String data) {
        int currentAttempt = retryAttempts.compute(id, (messageId, attempts) ->
                attempts == null ? 1 : attempts + 1
        );

        FailureContext context = FailureContext.builder()
                .exception(exception)
                .stream(stream)
                .messageId(id)
                .taskData(data)
                .actionName(getActionName())
                .attemptNumber(currentAttempt)
                .maxAttempts(3)
                .failureTimestamp(System.currentTimeMillis())
                .build();

        FailureHandlingResult result = failureHandler.handle(context);

        if (result.isShouldRetry()) {
            scheduleRetry(result.getRetryDelay(), stream, id, service, data);
        } else {
            // Give up and execute compensation
            retryAttempts.remove(id);
            executeCompensation(result.getCompensationAction(), context);
        }
    }

    private void scheduleRetry(Duration delay, RStream<Object, Object> stream, StreamMessageId id,
                               LegacyEntityDataService service, String data) {
        Log.info("Scheduling entity stream retry for message %s after delay of %sms",
                id, delay.toMillis());

        scheduler.schedule(() -> executeWithResilience(stream, id, service, data), delay.toMillis(), TimeUnit.MILLISECONDS);
    }

    private void executeCompensation(CompensationAction compensation, FailureContext context) {
        if (compensation == null) {
            return;
        }

        try {
            compensation.execute(context);
        } catch (Exception compensationException) {
            Log.error("Entity stream compensation action failed for message %s",
                    context.getMessageId(), compensationException);
        }
    }

    /**
     * Gets the current retry count for a message.
     */
    public int getRetryCount(StreamMessageId messageId) {
        return retryAttempts.getOrDefault(messageId, 0);
    }

    /**
     * Clears retry tracking for a specific message.
     */
    public void clearRetryTracking(StreamMessageId messageId) {
        retryAttempts.remove(messageId);
    }

    /**
     * Gets the total number of messages currently being tracked for retries.
     */
    public int getTrackedMessageCount() {
        return retryAttempts.size();
    }
}