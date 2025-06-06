package net.legacy.library.player.task.redis.resilience;

/**
 * Strategy interface for handling failures in stream accepter operations.
 *
 * <p>Implementations define how to respond to various types of failures by analyzing
 * the failure context and determining whether to retry the operation or give up
 * and execute compensation actions.
 *
 * <p>The failure handler is the central decision-making component of the resilience
 * framework, responsible for:
 * <ul>
 *   <li>Analyzing failure context (exception type, attempt count, etc.)</li>
 *   <li>Deciding whether to retry or give up</li>
 *   <li>Calculating retry delays when retrying</li>
 *   <li>Selecting appropriate compensation actions when giving up</li>
 * </ul>
 *
 * <p>Example implementation:
 * <pre>{@code
 * FailureHandler customHandler = context -> {
 *     if (context.getAttemptNumber() <= 3 &&
 *         context.getException() instanceof IOException) {
 *         return FailureHandlingResult.retry(Duration.ofSeconds(5));
 *     }
 *     return FailureHandlingResult.giveUp(CompensationAction.LOG_FAILURE);
 * };
 * }</pre>
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
     * A failure handler that never retries and immediately gives up.
     *
     * <p>This handler will immediately execute the log failure compensation action
     * without attempting any retries. Useful when operations should fail fast or
     * when retries are known to be ineffective.
     */
    FailureHandler GIVE_UP_IMMEDIATELY = context ->
            FailureHandlingResult.giveUp(CompensationAction.LOG_FAILURE);

    /**
     * Creates a failure handler that retries only for specific exception types.
     *
     * <p>The created handler will only attempt retries when the failure is caused
     * by one of the specified exception types (including subclasses). Other
     * exception types will result in immediate failure with logging compensation.
     *
     * @param exceptionTypes the exception types that should trigger retries
     * @return a new {@link FailureHandler} that retries only specified exception types
     * @see RetryPolicy#forExceptionTypes(Class[])
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
        return context -> {
            if (context.getAttemptNumber() <= retryPolicy.getMaxAttempts() &&
                    retryPolicy.shouldRetry(context.getException())) {
                return FailureHandlingResult.retry(retryPolicy.calculateDelay(context.getAttemptNumber()));
            }

            return FailureHandlingResult.giveUp(compensation);
        };
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
     *   <li>Time since first failure</li>
     *   <li>System state and resource availability</li>
     * </ul>
     *
     * @param context the failure context containing all relevant information
     * @return the result of failure handling, indicating whether to retry or give up
     */
    FailureHandlingResult handle(FailureContext context);
}