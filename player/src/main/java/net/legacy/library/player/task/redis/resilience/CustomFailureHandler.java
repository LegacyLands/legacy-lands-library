package net.legacy.library.player.task.redis.resilience;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

/**
 * A custom implementation of {@link FailureHandler} that wraps a retry policy
 * and compensation action, providing access to the retry policy for configuration.
 *
 * @author qwq-dev
 * @since 2025-06-07 16:00
 */
@Getter
@RequiredArgsConstructor
public class CustomFailureHandler implements FailureHandler {

    private final RetryPolicy retryPolicy;
    private final CompensationAction compensationAction;

    /**
     * {@inheritDoc}
     */
    @Override
    public FailureHandlingResult handle(FailureContext context) {
        if (context.getAttemptNumber() <= retryPolicy.getMaxAttempts() &&
                retryPolicy.shouldRetry(context.getException())) {
            return FailureHandlingResult.retry(retryPolicy.calculateDelay(context.getAttemptNumber()));
        }

        return FailureHandlingResult.giveUp(compensationAction);
    }

}