package net.legacy.library.player.task.redis.resilience;

/**
 * Strategy interface for handling failures in stream accepter operations.
 *
 * <p>Implementations define how to respond to various types of failures by analyzing
 * the failure context and determining whether to retry the operation or give up
 * and execute compensation actions.
 *
 * @author qwq-dev
 * @see FailureContext
 * @see FailureHandlingResult
 * @see RetryPolicy
 * @see CompensationAction
 * @since 2025-06-06 16:30
 */
@FunctionalInterface
public interface FailureHandler {

    /**
     * A failure handler that always retries according to the default retry policy.
     *
     * <p>This handler will retry operations up to the maximum attempts specified
     * in the default retry policy, using exponential backoff delays. If retries
     * are exhausted or the exception type is not retryable, it gives up and logs
     * the failure.
     *
     * @see RetryPolicy#defaultPolicy()
     */
    FailureHandler RETRY_ALWAYS = context -> {
        RetryPolicy policy = RetryPolicy.defaultPolicy();

        if (context.getAttemptNumber() <= policy.getMaxAttempts() &&
                policy.shouldRetry(context.getException())) {
            return FailureHandlingResult.retry(policy.calculateDelay(context.getAttemptNumber()));
        }

        return FailureHandlingResult.giveUp(CompensationAction.LOG_FAILURE);
    };

    /**
     * A failure handler that immediately gives up without any retry attempts.
     *
     * <p>This handler is useful for non-transient errors that are known to be
     * permanent, such as validation errors or authentication failures. It logs
     * the failure details without attempting any retries.
     */
    FailureHandler GIVE_UP_IMMEDIATELY = context ->
            FailureHandlingResult.giveUp(CompensationAction.LOG_FAILURE);

    /**
     * Creates a failure handler that only retries for specific exception types.
     *
     * <p>This factory method creates a handler that applies the default retry policy
     * only when the failure exception is an instance of one of the specified types.
     * Other exceptions result in immediate failure with logging.
     *
     * @param exceptionTypes the exception types that should trigger retries
     * @return a new {@link FailureHandler} that retries only for specified exceptions
     */
    static FailureHandler retryForExceptions(Class<?>... exceptionTypes) {
        RetryPolicy policy = RetryPolicy.forExceptionTypes(exceptionTypes);

        return context -> {
            if (context.getAttemptNumber() <= policy.getMaxAttempts() &&
                    policy.shouldRetry(context.getException())) {
                return FailureHandlingResult.retry(policy.calculateDelay(context.getAttemptNumber()));
            }

            return FailureHandlingResult.giveUp(CompensationAction.LOG_FAILURE);
        };
    }

    /**
     * Creates a failure handler with a custom retry policy and compensation action.
     *
     * <p>This factory method allows full customization of both retry behavior
     * and compensation actions. The handler will apply the provided retry policy
     * to determine if retries are appropriate, and execute the specified
     * compensation action when giving up.
     *
     * @param retryPolicy  the retry policy to use for determining retry behavior
     * @param compensation the compensation action to execute when giving up
     * @return a new {@link FailureHandler} with the specified policy and compensation
     */
    static FailureHandler withPolicy(RetryPolicy retryPolicy, CompensationAction compensation) {
        return new CustomFailureHandler(retryPolicy, compensation);
    }

    /**
     * Handles a failure that occurred during stream accepter processing.
     *
     * <p>This method is the core decision point for failure handling. Implementations
     * should analyze the provided context and determine the appropriate response:
     * <ul>
     *   <li>Return a retry result if the operation should be attempted again</li>
     *   <li>Return a give-up result if retries are exhausted or inappropriate</li>
     * </ul>
     *
     * <p>The handler should consider factors such as:
     * <ul>
     *   <li>Exception type and root cause</li>
     *   <li>Number of previous attempts</li>
     *   <li>Available retry policy limits</li>
     *   <li>Current system state and resources</li>
     * </ul>
     *
     * @param context the failure context containing all relevant information
     * @return a {@link FailureHandlingResult} indicating whether to retry or give up
     */
    FailureHandlingResult handle(FailureContext context);

    /**
     * Gets the retry policy associated with this handler.
     *
     * <p>Default implementation returns null. Override in implementations that
     * have an associated retry policy for configuration purposes.
     *
     * @return the {@link RetryPolicy} or null if not applicable
     */
    default RetryPolicy getRetryPolicy() {
        return null;
    }

}