package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RAtomicLong;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Distributed implementation of {@link RetryCounter} using Redis for global state management.
 *
 * <p>This implementation leverages Redis atomic operations to provide a globally consistent
 * retry counter across multiple server instances. It ensures that retry counts are accurately
 * tracked even when requests are handled by different servers or when servers fail over.
 * The implementation is globally consistent across all server instances, survives server
 * restarts and failures, handles high concurrency with Redis atomic operations, though it
 * incurs network latency overhead (typically 1-5ms).
 *
 * <p>Redis operations include INCR for atomic increment of counter updates, GET to retrieve
 * current counter values, DEL to remove counters on reset, EXPIRE to set TTL for automatic
 * cleanup, and Lua scripts for atomic compound operations.
 *
 * <p>This implementation is ideal for critical business operations requiring exact retry
 * limits, multiserver deployments with load balancing, scenarios requiring retry state
 * persistence, and operations where over-retrying could cause serious issues.
 *
 * @author qwq-dev
 * @see RetryCounter
 * @see LocalRetryCounter
 * @since 2025-06-07 10:00
 */
@RequiredArgsConstructor
public class DistributedRetryCounter implements RetryCounter {
    /**
     * Redis client for distributed operations
     */
    private final RedissonClient redissonClient;
    /**
     * Prefix for all retry counter keys in Redis
     */
    private final String keyPrefix;
    /**
     * Task interface for virtual thread execution
     */
    private final TaskInterface<?> taskInterface;

    /**
     * Creates a new DistributedRetryCounter with the default key prefix and default TaskInterface.
     *
     * @param redissonClient the Redis client to use for distributed operations
     * @return a new {@link DistributedRetryCounter} instance
     */
    public static DistributedRetryCounter create(RedissonClient redissonClient) {
        return new DistributedRetryCounter(redissonClient, "retry:counter:", new TaskInterface<Object>() {
        });
    }

    /**
     * Creates a new DistributedRetryCounter with a custom key prefix and default TaskInterface.
     *
     * <p>The key prefix helps organize retry counters in Redis and avoid key
     * collisions with other data. It's recommended to use a prefix that clearly
     * identifies the purpose and scope of these counters.
     *
     * @param redissonClient the Redis client to use for distributed operations
     * @param keyPrefix      the prefix to prepend to all counter keys
     * @return a new {@link DistributedRetryCounter} instance
     */
    public static DistributedRetryCounter create(RedissonClient redissonClient, String keyPrefix) {
        return new DistributedRetryCounter(redissonClient, keyPrefix, new TaskInterface<Object>() {
        });
    }

    /**
     * Creates a new DistributedRetryCounter with custom TaskInterface for virtual thread execution.
     *
     * @param redissonClient the Redis client to use for distributed operations
     * @param keyPrefix      the prefix to prepend to all counter keys
     * @param taskInterface  the TaskInterface for virtual thread execution
     * @return a new {@link DistributedRetryCounter} instance
     */
    public static DistributedRetryCounter create(RedissonClient redissonClient, String keyPrefix, TaskInterface<?> taskInterface) {
        return new DistributedRetryCounter(redissonClient, keyPrefix, taskInterface);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> increment(String key) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RAtomicLong counter = redissonClient.getAtomicLong(buildKey(key));
                long newValue = counter.incrementAndGet();
                return Math.toIntExact(newValue);
            } catch (Exception exception) {
                Log.error("Failed to increment distributed retry counter for key: " + key, exception);
                throw new RetryCounterException("Failed to increment distributed counter", exception);
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @param ttl {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> increment(String key, Duration ttl) {
        // Validate TTL
        if (ttl != null && ttl.toMillis() > TimeUnit.DAYS.toMillis(7)) {
            return CompletableFuture.failedFuture(
                    new IllegalArgumentException("TTL cannot exceed 7 days"));
        }

        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                    // No TTL, use simple increment
                    return increment(key).join();
                }

                // Use TTLUtil for atomic increment with TTL
                Long result = TTLUtil.incrementWithTTL(redissonClient, buildKey(key), ttl.getSeconds());
                return Math.toIntExact(result);
            } catch (Exception exception) {
                Log.error("Failed to increment distributed retry counter with TTL for key: " + key, exception);
                throw new RetryCounterException("Failed to increment distributed counter with TTL", exception);
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> get(String key) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RAtomicLong counter = redissonClient.getAtomicLong(buildKey(key));
                long value = counter.get();
                return Math.toIntExact(value);
            } catch (Exception exception) {
                Log.error("Failed to get distributed retry counter for key: " + key, exception);
                return 0;
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> reset(String key) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RBucket<Object> bucket = redissonClient.getBucket(buildKey(key));
                bucket.delete();
            } catch (Exception exception) {
                Log.error("Failed to reset distributed retry counter for key: " + key, exception);
                throw new RetryCounterException("Failed to reset distributed counter", exception);
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Boolean> exists(String key) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RBucket<Object> bucket = redissonClient.getBucket(buildKey(key));
                return bucket.isExists();
            } catch (Exception exception) {
                Log.error("Failed to check existence of distributed retry counter for key: " + key, exception);
                return false;
            }
        });
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public RetryCounterType getType() {
        return RetryCounterType.DISTRIBUTED;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        // RedissonClient lifecycle is managed externally
        // We don't close it here to avoid affecting other components
    }

    /**
     * Builds the full Redis key by prepending the configured prefix.
     *
     * @param key the base key
     * @return the full Redis key with prefix
     */
    private String buildKey(String key) {
        return keyPrefix + key;
    }

    /**
     * Sets a specific value for a retry counter.
     *
     * <p>This method is primarily for testing or administrative purposes.
     * Normal retry logic should use {@link #increment(String)} instead.
     *
     * @param key   the counter key
     * @param value the value to set
     * @return a {@link CompletableFuture} that completes when the operation is done
     */
    public CompletableFuture<Void> set(String key, int value) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RAtomicLong counter = redissonClient.getAtomicLong(buildKey(key));
                counter.set(value);
            } catch (Exception exception) {
                Log.error("Failed to set distributed retry counter for key: " + key, exception);
                throw new RetryCounterException("Failed to set distributed counter", exception);
            }
        });
    }

    /**
     * Gets the remaining TTL for a counter key.
     *
     * @param key the counter key
     * @return a {@link CompletableFuture} containing the TTL in seconds, or -1 if no TTL is set
     */
    public CompletableFuture<Long> getTimeToLive(String key) {
        return taskInterface.submitWithVirtualThreadAsync(() -> {
            try {
                RBucket<Object> bucket = redissonClient.getBucket(buildKey(key));
                return bucket.remainTimeToLive() / 1000; // Convert to seconds
            } catch (Exception exception) {
                Log.error("Failed to get TTL for distributed retry counter key: " + key, exception);
                return -1L;
            }
        });
    }
}