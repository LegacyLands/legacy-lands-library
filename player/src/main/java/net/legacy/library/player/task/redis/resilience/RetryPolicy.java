package net.legacy.library.player.task.redis.resilience;

import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.function.Predicate;

/**
 * Configuration for retry behavior when stream accepter operations fail.
 *
 * <p>This immutable configuration class defines when and how to retry failed operations,
 * including retry limits, delay calculations, and conditions for retrying specific
 * exception types. It supports both fixed and exponential backoff strategies.
 *
 * @author qwq-dev
 * @see FailureHandler
 * @see FailureContext
 * @see RetryCounterType
 * @since 2025-06-06 16:30
 */
@Getter
@Builder
public class RetryPolicy {

    /**
     * Maximum number of retry attempts (excluding the initial attempt)
     */
    @Builder.Default
    private final int maxAttempts = 3;

    /**
     * Base delay between retry attempts
     */
    @Builder.Default
    private final Duration baseDelay = Duration.ofSeconds(1);

    /**
     * Maximum delay between retry attempts (for exponential backoff)
     */
    @Builder.Default
    private final Duration maxDelay = Duration.ofMinutes(5);

    /**
     * Multiplier for exponential backoff
     */
    @Builder.Default
    private final double backoffMultiplier = 2.0;

    /**
     * Whether to use exponential backoff
     */
    @Builder.Default
    private final boolean exponentialBackoff = true;

    /**
     * Predicate to determine if an exception should trigger a retry
     */
    @Builder.Default
    private final Predicate<Exception> retryCondition = exception -> true;

    /**
     * Type of retry counter to use for tracking attempts
     */
    @Builder.Default
    private final RetryCounterType retryCounterType = RetryCounterType.LOCAL;

    /**
     * Pattern for keys that require distributed counting in HYBRID mode
     */
    @Builder.Default
    private final String distributedKeyPattern = "critical:.*";

    /**
     * Predicate for custom distributed counter selection in HYBRID mode
     */
    @Builder.Default
    private final Predicate<String> useDistributedPredicate = key -> key.matches("critical:.*");

    /**
     * Creates a default retry policy with sensible defaults.
     *
     * <p>Default configuration:
     * <ul>
     *   <li>Maximum attempts: 3</li>
     *   <li>Base delay: 1 second</li>
     *   <li>Maximum delay: 5 minutes</li>
     *   <li>Backoff multiplier: 2.0</li>
     *   <li>Exponential backoff: enabled</li>
     *   <li>Retry condition: retry all exceptions</li>
     * </ul>
     *
     * @return a new {@link RetryPolicy} with default settings
     */
    public static RetryPolicy defaultPolicy() {
        return RetryPolicy.builder().build();
    }

    /**
     * Creates a retry policy that never retries.
     *
     * <p>This policy immediately gives up on the first failure without any retry attempts.
     * Useful for operations that should fail fast or when retries are not desired.
     *
     * @return a new {@link RetryPolicy} with zero retry attempts
     */
    public static RetryPolicy noRetry() {
        return RetryPolicy.builder()
                .maxAttempts(0)
                .build();
    }

    /**
     * Creates a retry policy that only retries for specific exception types.
     *
     * <p>The policy will check if the failed exception is an instance of any of the
     * specified exception types (including subclasses). It also checks the cause
     * of the exception if the top-level exception doesn't match. Only matching exceptions
     * will trigger retry attempts.
     *
     * @param exceptionTypes the exception types that should trigger retries
     * @return a new {@link RetryPolicy} that retries only the specified exception types
     */
    public static RetryPolicy forExceptionTypes(Class<?>... exceptionTypes) {
        return RetryPolicy.builder()
                .retryCondition(exception -> {
                    // Check the exception itself
                    for (Class<?> type : exceptionTypes) {
                        if (type.isInstance(exception)) {
                            return true;
                        }
                    }

                    // Check the cause of the exception
                    Throwable cause = exception.getCause();
                    if (cause != null) {
                        for (Class<?> type : exceptionTypes) {
                            if (type.isInstance(cause)) {
                                return true;
                            }
                        }
                    }

                    return false;
                })
                .build();
    }

    /**
     * Creates a retry policy with hybrid counting for mixed workloads.
     *
     * <p>This policy intelligently selects between local and distributed counting
     * based on the operation key. It's ideal for applications with both critical
     * and non-critical operations.
     *
     * <p>Default behavior:
     * <ul>
     *   <li>Uses distributed counting for keys matching the provided pattern</li>
     *   <li>Uses local counting for all other keys</li>
     *   <li>Standard retry configuration (3 attempts, 1s base delay)</li>
     * </ul>
     *
     * @param criticalKeyPattern regex pattern for operations requiring distributed counting
     * @return a new {@link RetryPolicy} with hybrid counting
     */
    public static RetryPolicy withHybridCounting(String criticalKeyPattern) {
        return RetryPolicy.builder()
                .retryCounterType(RetryCounterType.HYBRID)
                .distributedKeyPattern(criticalKeyPattern)
                .useDistributedPredicate(key -> key.matches(criticalKeyPattern))
                .build();
    }

    /**
     * Creates a retry policy with distributed counting for all operations.
     *
     * <p>This policy ensures globally consistent retry tracking across all
     * server instances. Use this when retry count accuracy is more important
     * than the slight performance overhead of distributed counting.
     *
     * @return a new {@link RetryPolicy} with distributed counting
     */
    public static RetryPolicy withDistributedCounting() {
        return RetryPolicy.builder()
                .retryCounterType(RetryCounterType.DISTRIBUTED)
                .build();
    }

    /**
     * Calculates the delay for a specific attempt number.
     *
     * <p>If exponential backoff is disabled, returns the base delay.
     * If exponential backoff is enabled, calculates delay using the formula:
     * {@code baseDelay * (backoffMultiplier ^ (attemptNumber - 1))}
     *
     * <p>The calculated delay is capped at {@link #maxDelay} to prevent
     * excessive wait times.
     *
     * @param attemptNumber the attempt number (1-based) for which to calculate the delay
     * @return the calculated delay duration, never exceeding {@link #maxDelay}
     */
    public Duration calculateDelay(int attemptNumber) {
        if (!exponentialBackoff) {
            return baseDelay;
        }

        long delayMillis = (long) (baseDelay.toMillis() * Math.pow(backoffMultiplier, attemptNumber - 1));
        Duration calculatedDelay = Duration.ofMillis(delayMillis);

        return calculatedDelay.compareTo(maxDelay) > 0 ? maxDelay : calculatedDelay;
    }

    /**
     * Determines if the given exception should trigger a retry.
     *
     * <p>Applies the configured retry condition predicate to determine whether
     * the specified exception warrants a retry attempt.
     *
     * @param exception the exception to evaluate for retry eligibility
     * @return {@code true} if the exception should trigger a retry, {@code false} otherwise
     * @see #retryCondition
     */
    public boolean shouldRetry(Exception exception) {
        return retryCondition.test(exception);
    }

    /**
     * Checks if this policy requires Redis for retry counting.
     *
     * <p>This is useful for initialization logic to ensure Redis is available
     * when needed for distributed or hybrid counting.
     *
     * @return {@code true} if the policy uses distributed or hybrid counting
     */
    public boolean requiresRedis() {
        return retryCounterType == RetryCounterType.DISTRIBUTED ||
                retryCounterType == RetryCounterType.HYBRID;
    }

}