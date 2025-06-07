package net.legacy.library.player.task.redis.resilience;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for tracking retry attempts in resilient stream processing.
 *
 * <p>This interface defines the contract for retry counters that can track the number
 * of retry attempts for specific operations. Implementations can provide different
 * strategies such as local in-memory counting or distributed counting using external
 * storage systems.
 *
 * <p>All operations are designed to be non-blocking and thread-safe. Implementations
 * must ensure proper synchronization for concurrent access. The interface provides
 * methods to atomically increment and return the new count, retrieve current count
 * without modification, clear the counter for a specific key, and support optional
 * time-to-live for automatic cleanup.
 *
 * @author qwq-dev
 * @see LocalRetryCounter
 * @see DistributedRetryCounter
 * @see HybridRetryCounter
 * @since 2025-06-07 10:00
 */
public interface RetryCounter {
    /**
     * Increments the retry count for the specified key and returns the new count.
     *
     * <p>This operation must be atomic to ensure correct counting in concurrent
     * environments. The returned value represents the count after incrementing.
     *
     * @param key the unique identifier for the retry operation
     * @return a {@link CompletableFuture} containing the new retry count after increment
     */
    CompletableFuture<Integer> increment(String key);

    /**
     * Increments the retry count for the specified key with a time-to-live.
     *
     * <p>Similar to {@link #increment(String)}, but the counter will automatically
     * expire after the specified duration. This is useful for implementing time-windowed
     * retry policies or automatic cleanup of stale counters.
     *
     * @param key the unique identifier for the retry operation
     * @param ttl the time-to-live duration for this counter
     * @return a {@link CompletableFuture} containing the new retry count after increment
     */
    CompletableFuture<Integer> increment(String key, Duration ttl);

    /**
     * Retrieves the current retry count for the specified key without modifying it.
     *
     * <p>If the key doesn't exist, implementations should return 0 rather than null
     * or throwing an exception.
     *
     * @param key the unique identifier for the retry operation
     * @return a {@link CompletableFuture} containing the current retry count, or 0 if not found
     */
    CompletableFuture<Integer> get(String key);

    /**
     * Resets the retry count for the specified key.
     *
     * <p>This operation removes the counter entirely, so subsequent calls to
     * {@link #get(String)} will return 0 until the counter is incremented again.
     *
     * @param key the unique identifier for the retry operation
     * @return a {@link CompletableFuture} that completes when the reset is done
     */
    CompletableFuture<Void> reset(String key);

    /**
     * Checks if a retry counter exists for the specified key.
     *
     * <p>This can be useful for distinguishing between a counter that has been
     * set to 0 and a counter that doesn't exist.
     *
     * @param key the unique identifier for the retry operation
     * @return a {@link CompletableFuture} containing true if the counter exists, false otherwise
     */
    CompletableFuture<Boolean> exists(String key);

    /**
     * Gets the type of this retry counter.
     *
     * <p>This method helps identify the implementation strategy being used,
     * which can be useful for monitoring, debugging, or adaptive behavior.
     *
     * @return the {@link RetryCounterType} of this implementation
     */
    RetryCounterType getType();

    /**
     * Closes any resources associated with this retry counter.
     *
     * <p>Implementations should release any connections, clear caches, and
     * perform necessary cleanup. This method should be idempotent.
     */
    void close();
}