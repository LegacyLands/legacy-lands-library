package net.legacy.library.player.task.redis.resilience;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Getter;

import java.time.Duration;

/**
 * Result of failure handling indicating what action should be taken.
 *
 * <p>This immutable result object encapsulates the decision made by a {@link FailureHandler}
 * and provides the necessary information for the resilience framework to proceed with
 * either retry or compensation logic.
 *
 * <p>The result can represent two possible outcomes:
 * <ul>
 *   <li><strong>Retry:</strong> The operation should be attempted again after a specified delay</li>
 *   <li><strong>Give up:</strong> The operation should be abandoned and compensation should be executed</li>
 * </ul>
 *
 * <p>Factory methods provide convenient ways to create results:
 * <pre>{@code
 * // Retry after 5 seconds
 * FailureHandlingResult.retry(Duration.ofSeconds(5));
 *
 * // Give up and execute cleanup
 * FailureHandlingResult.giveUp(CompensationAction.REMOVE_MESSAGE);
 * }</pre>
 *
 * @author qwq-dev
 * @see FailureHandler
 * @see CompensationAction
 * @since 2025-06-06 16:30
 */
@Getter
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class FailureHandlingResult {

    /**
     * Whether to retry the operation.
     *
     * <p>When {@code true}, the operation should be retried after the specified delay.
     * When {@code false}, the operation should be abandoned and compensation executed.
     */
    private final boolean shouldRetry;

    /**
     * Delay before retry (null if not retrying).
     *
     * <p>Specifies how long to wait before attempting the retry. Only meaningful
     * when {@link #shouldRetry} is {@code true}. A delay of zero indicates
     * immediate retry.
     */
    private final Duration retryDelay;

    /**
     * Compensation action to execute (null if retrying).
     *
     * <p>The compensation action to be executed when giving up. Only meaningful
     * when {@link #shouldRetry} is {@code false}. May be null to indicate
     * no compensation is needed.
     */
    private final CompensationAction compensationAction;

    /**
     * Creates a result indicating the operation should be retried after the specified delay.
     *
     * <p>The delay determines how long the resilience framework should wait before
     * attempting the operation again. The delay can be zero for immediate retry.
     *
     * @param delay the duration to wait before retrying, must not be null
     * @return a new {@link FailureHandlingResult} configured for retry with the specified delay
     * @throws NullPointerException if delay is null
     */
    public static FailureHandlingResult retry(Duration delay) {
        return new FailureHandlingResult(true, delay, null);
    }

    /**
     * Creates a result indicating the operation should be retried immediately.
     *
     * <p>Equivalent to calling {@link #retry(Duration)} with {@link Duration#ZERO}.
     * Use this when you want to retry without any delay.
     *
     * @return a new {@link FailureHandlingResult} configured for immediate retry
     */
    public static FailureHandlingResult retryImmediately() {
        return retry(Duration.ZERO);
    }

    /**
     * Creates a result indicating the operation should be abandoned with compensation.
     *
     * <p>The specified compensation action will be executed to handle the failure.
     * This is the terminal action for a failed operation that cannot be retried.
     *
     * @param compensation the compensation action to execute, may be null for no compensation
     * @return a new {@link FailureHandlingResult} configured to give up with the specified compensation
     */
    public static FailureHandlingResult giveUp(CompensationAction compensation) {
        return new FailureHandlingResult(false, null, compensation);
    }

    /**
     * Creates a result indicating the operation should be abandoned without compensation.
     *
     * <p>Equivalent to calling {@link #giveUp(CompensationAction)} with {@link CompensationAction#NONE}.
     * Use this when the failure should be ignored without any cleanup actions.
     *
     * @return a new {@link FailureHandlingResult} configured to give up silently
     */
    public static FailureHandlingResult giveUpSilently() {
        return giveUp(CompensationAction.NONE);
    }

}