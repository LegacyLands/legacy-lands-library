package net.legacy.library.player.task.redis.resilience;

/**
 * Defines the type of retry counter to use for tracking retry attempts.
 *
 * <p>This enum provides different strategies for counting retry attempts in distributed
 * systems, allowing developers to choose between local-only counting, distributed counting,
 * or a hybrid approach based on their specific requirements. LOCAL provides fast and
 * efficient counting limited to single server instances, DISTRIBUTED offers globally
 * consistent counting that survives server failures, and HYBRID uses an adaptive approach
 * that chooses based on operation criticality.
 *
 * @author qwq-dev
 * @see RetryCounter
 * @see RetryPolicy
 * @since 2025-06-07 10:00
 */
public enum RetryCounterType {
    /**
     * Local retry counter that tracks attempts only within the current server instance.
     *
     * <p>This type provides the fastest performance as it doesn't require network calls,
     * but retry counts are lost if the server crashes or restarts. Suitable for
     * non-critical operations where retry count accuracy across server failures
     * is not essential, such as high-frequency operations where performance is critical,
     * operations that can tolerate occasional over-retrying, or development and testing
     * environments.
     */
    LOCAL,

    /**
     * Distributed retry counter that tracks attempts across all server instances.
     *
     * <p>This type uses Redis or similar distributed storage to maintain retry counts,
     * ensuring consistency across server failures and restarts. While it has higher
     * latency due to network calls, it provides accurate global retry tracking. This
     * is essential for critical business operations like payments and inventory updates,
     * non-idempotent operations that must not exceed retry limits, and operations
     * requiring audit trails or compliance.
     */
    DISTRIBUTED,

    /**
     * Hybrid retry counter that intelligently chooses between local and distributed counting.
     *
     * <p>This type allows dynamic selection of counter strategy based on operation
     * characteristics. For example, it might use distributed counting for critical
     * payment operations while using local counting for less critical data queries.
     * The selection logic can be configured through {@link RetryPolicy} to consider
     * operation type and criticality, expected retry frequency, performance requirements,
     * and consistency requirements.
     */
    HYBRID
}