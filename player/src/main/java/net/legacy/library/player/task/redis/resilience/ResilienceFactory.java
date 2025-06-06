package net.legacy.library.player.task.redis.resilience;

import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;

import java.io.IOException;
import java.net.ConnectException;
import java.net.SocketTimeoutException;
import java.time.Duration;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;

/**
 * Factory for creating resilient stream accepters with predefined configurations.
 * Provides convenient methods for common resilience patterns.
 *
 * @author qwq-dev
 * @since 2025-06-06 16:30
 */
public class ResilienceFactory {
    private static final ScheduledExecutorService DEFAULT_SCHEDULER =
            Executors.newScheduledThreadPool(2, runnable -> {
                Thread thread = new Thread(runnable, "resilience-scheduler");
                thread.setDaemon(true);
                return thread;
            });

    /**
     * Creates a resilient wrapper with default settings (3 retries, exponential backoff).
     *
     * <p>This is the most commonly used factory method, providing a balanced approach
     * to error handling with reasonable retry attempts and delay progression.
     *
     * @param accepter the original stream accepter to wrap with resilience capabilities
     * @return a new {@link ResilientRStreamAccepter} with default configuration
     */
    public static ResilientRStreamAccepter createDefault(RStreamAccepterInterface accepter) {
        return ResilientRStreamAccepter.wrap(accepter, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper for entity stream accepter with default settings.
     *
     * <p>This is the most commonly used factory method for entity accepters, providing
     * a balanced approach to error handling with reasonable retry attempts and delay progression.
     *
     * @param accepter the original entity stream accepter to wrap with resilience capabilities
     * @return a new {@link ResilientEntityRStreamAccepter} with default configuration
     */
    public static ResilientEntityRStreamAccepter createDefault(EntityRStreamAccepterInterface accepter) {
        return ResilientEntityRStreamAccepter.wrap(accepter, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper that retries only for network-related exceptions.
     *
     * <p>This factory method creates a wrapper that specifically handles network failures
     * such as connection timeouts, socket exceptions, and general I/O errors. Non-network
     * exceptions (like validation errors) will not trigger retries.
     *
     * <p>Targeted exceptions include:
     * <ul>
     *   <li>{@link ConnectException} - Connection refused or failed</li>
     *   <li>{@link SocketTimeoutException} - Socket read/write timeouts</li>
     *   <li>{@link IOException} - General I/O errors</li>
     * </ul>
     *
     * @param accepter the original stream accepter to wrap with network-specific resilience
     * @return a new {@link ResilientRStreamAccepter} configured for network error handling
     */
    public static ResilientRStreamAccepter createForNetworkErrors(RStreamAccepterInterface accepter) {
        FailureHandler handler = FailureHandler.retryForExceptions(
                ConnectException.class,
                SocketTimeoutException.class,
                IOException.class
        );
        return ResilientRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper for entity accepter that retries only for network-related exceptions.
     *
     * <p>This factory method creates a wrapper that specifically handles network failures
     * such as connection timeouts, socket exceptions, and general I/O errors. Non-network
     * exceptions (like validation errors) will not trigger retries.
     *
     * <p>Targeted exceptions include:
     * <ul>
     *   <li>{@link ConnectException} - Connection refused or failed</li>
     *   <li>{@link SocketTimeoutException} - Socket read/write timeouts</li>
     *   <li>{@link IOException} - General I/O errors</li>
     * </ul>
     *
     * @param accepter the original entity stream accepter to wrap with network-specific resilience
     * @return a new {@link ResilientEntityRStreamAccepter} configured for network error handling
     */
    public static ResilientEntityRStreamAccepter createForNetworkErrors(EntityRStreamAccepterInterface accepter) {
        FailureHandler handler = FailureHandler.retryForExceptions(
                ConnectException.class,
                SocketTimeoutException.class,
                IOException.class
        );
        return ResilientEntityRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper with fast retry configuration (short delays, more attempts).
     *
     * <p>This configuration is suitable for handling transient errors that are likely
     * to resolve quickly, such as temporary resource contention or brief network hiccups.
     * Uses 5 maximum attempts with 100ms base delay, growing to maximum 5 seconds with
     * a 1.5x backoff multiplier.
     *
     * <p>Upon final failure, both logs the error and removes the message from the stream.
     *
     * @param accepter the original stream accepter to wrap with fast retry resilience
     * @return a new {@link ResilientRStreamAccepter} configured for rapid retry attempts
     */
    public static ResilientRStreamAccepter createFastRetry(RStreamAccepterInterface accepter) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(5))
                .backoffMultiplier(1.5)
                .build();

        FailureHandler handler = FailureHandler.withPolicy(policy,
                CompensationAction.composite(CompensationAction.LOG_FAILURE, CompensationAction.REMOVE_MESSAGE));

        return ResilientRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper for entity accepter with fast retry configuration.
     *
     * <p>This configuration is suitable for handling transient errors that are likely
     * to resolve quickly, such as temporary resource contention or brief network hiccups.
     * Uses 5 maximum attempts with 100ms base delay, growing to maximum 5 seconds with
     * a 1.5x backoff multiplier.
     *
     * <p>Upon final failure, both logs the error and removes the message from the stream.
     *
     * @param accepter the original entity stream accepter to wrap with fast retry resilience
     * @return a new {@link ResilientEntityRStreamAccepter} configured for rapid retry attempts
     */
    public static ResilientEntityRStreamAccepter createFastRetry(EntityRStreamAccepterInterface accepter) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofMillis(100))
                .maxDelay(Duration.ofSeconds(5))
                .backoffMultiplier(1.5)
                .build();

        FailureHandler handler = FailureHandler.withPolicy(policy,
                CompensationAction.composite(CompensationAction.LOG_FAILURE, CompensationAction.REMOVE_MESSAGE));

        return ResilientEntityRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper with conservative retry configuration (longer delays, fewer attempts).
     *
     * <p>This configuration is suitable for handling errors that may take time to resolve,
     * such as downstream service outages or database connectivity issues. Uses only 2
     * maximum attempts with 5-second base delay, growing to maximum 2 minutes with
     * a 3.0x backoff multiplier.
     *
     * <p>Upon final failure, only logs the error without removing the message, allowing
     * for manual intervention or later retry by other processes.
     *
     * @param accepter the original stream accepter to wrap with conservative retry resilience
     * @return a new {@link ResilientRStreamAccepter} configured for conservative retry attempts
     */
    public static ResilientRStreamAccepter createConservativeRetry(RStreamAccepterInterface accepter) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofMinutes(2))
                .backoffMultiplier(3.0)
                .build();

        FailureHandler handler = FailureHandler.withPolicy(policy, CompensationAction.LOG_FAILURE);

        return ResilientRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper for entity accepter with conservative retry configuration.
     *
     * <p>This configuration is suitable for handling errors that may take time to resolve,
     * such as downstream service outages or database connectivity issues. Uses only 2
     * maximum attempts with 5-second base delay, growing to maximum 2 minutes with
     * a 3.0x backoff multiplier.
     *
     * <p>Upon final failure, only logs the error without removing the message, allowing
     * for manual intervention or later retry by other processes.
     *
     * @param accepter the original entity stream accepter to wrap with conservative retry resilience
     * @return a new {@link ResilientEntityRStreamAccepter} configured for conservative retry attempts
     */
    public static ResilientEntityRStreamAccepter createConservativeRetry(EntityRStreamAccepterInterface accepter) {
        RetryPolicy policy = RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelay(Duration.ofSeconds(5))
                .maxDelay(Duration.ofMinutes(2))
                .backoffMultiplier(3.0)
                .build();

        FailureHandler handler = FailureHandler.withPolicy(policy, CompensationAction.LOG_FAILURE);

        return ResilientEntityRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper that never retries but logs failures.
     *
     * <p>This configuration is useful when you want to monitor failures without
     * automatic retry behavior. All exceptions are immediately passed to the
     * compensation action, which logs the failure details.
     *
     * <p>Use this when:
     * <ul>
     *   <li>Errors are expected to be permanent (validation failures)</li>
     *   <li>Manual intervention is required for each failure</li>
     *   <li>You want to track failure rates without retry overhead</li>
     * </ul>
     *
     * @param accepter the original stream accepter to wrap with no-retry resilience
     * @return a new {@link ResilientRStreamAccepter} configured to log failures without retries
     */
    public static ResilientRStreamAccepter createNoRetry(RStreamAccepterInterface accepter) {
        return ResilientRStreamAccepter.wrap(accepter, FailureHandler.GIVE_UP_IMMEDIATELY, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a resilient wrapper for entity accepter that never retries but logs failures.
     *
     * <p>This configuration is useful when you want to monitor failures without
     * automatic retry behavior. All exceptions are immediately passed to the
     * compensation action, which logs the failure details.
     *
     * <p>Use this when:
     * <ul>
     *   <li>Errors are expected to be permanent (validation failures)</li>
     *   <li>Manual intervention is required for each failure</li>
     *   <li>You want to track failure rates without retry overhead</li>
     * </ul>
     *
     * @param accepter the original entity stream accepter to wrap with no-retry resilience
     * @return a new {@link ResilientEntityRStreamAccepter} configured to log failures without retries
     */
    public static ResilientEntityRStreamAccepter createNoRetry(EntityRStreamAccepterInterface accepter) {
        return ResilientEntityRStreamAccepter.wrap(accepter, FailureHandler.GIVE_UP_IMMEDIATELY, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a custom resilient wrapper with specified retry policy and compensation action.
     *
     * <p>This method provides maximum flexibility for configuring resilience behavior.
     * You can specify exactly how retries should be handled and what actions should
     * be taken when all retry attempts are exhausted.
     *
     * @param accepter     the original stream accepter to wrap with custom resilience
     * @param policy       the retry policy defining when and how to retry failures
     * @param compensation the action to execute when all retry attempts are exhausted
     * @return a new {@link ResilientRStreamAccepter} configured with the specified policy and compensation
     */
    public static ResilientRStreamAccepter createCustom(RStreamAccepterInterface accepter,
                                                        RetryPolicy policy,
                                                        CompensationAction compensation) {
        FailureHandler handler = FailureHandler.withPolicy(policy, compensation);
        return ResilientRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Creates a custom resilient wrapper for entity accepter with specified retry policy and compensation action.
     *
     * <p>This method provides maximum flexibility for configuring resilience behavior.
     * You can specify exactly how retries should be handled and what actions should
     * be taken when all retry attempts are exhausted.
     *
     * @param accepter     the original entity stream accepter to wrap with custom resilience
     * @param policy       the retry policy defining when and how to retry failures
     * @param compensation the action to execute when all retry attempts are exhausted
     * @return a new {@link ResilientEntityRStreamAccepter} configured with the specified policy and compensation
     */
    public static ResilientEntityRStreamAccepter createCustom(EntityRStreamAccepterInterface accepter,
                                                              RetryPolicy policy,
                                                              CompensationAction compensation) {
        FailureHandler handler = FailureHandler.withPolicy(policy, compensation);
        return ResilientEntityRStreamAccepter.wrap(accepter, handler, DEFAULT_SCHEDULER);
    }

    /**
     * Shuts down the default scheduler used by all factory methods.
     *
     * <p>This method should be called when shutting down the application to ensure
     * proper cleanup of resources. Once called, no new resilient accepters should
     * be created using this factory, as they will not function correctly.
     *
     * <p>The shutdown is graceful and will allow currently scheduled retry attempts
     * to complete before terminating the scheduler threads.
     *
     * <p><strong>Important:</strong> This affects all resilient accepters created
     * through this factory. Call this only during application shutdown.
     */
    public static void shutdown() {
        DEFAULT_SCHEDULER.shutdown();
    }
}