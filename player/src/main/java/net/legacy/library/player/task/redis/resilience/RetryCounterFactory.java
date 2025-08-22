package net.legacy.library.player.task.redis.resilience;

import lombok.experimental.UtilityClass;
import org.redisson.api.RedissonClient;

import java.util.function.Predicate;

/**
 * Factory class for creating {@link RetryCounter} instances.
 *
 * <p>This utility class provides convenient factory methods for creating different
 * types of retry counters based on the application's requirements. It encapsulates
 * the complexity of counter initialization and provides sensible defaults.
 *
 * @author qwq-dev
 * @see RetryCounter
 * @see RetryCounterType
 * @since 2025-06-07 10:00
 */
@UtilityClass
public class RetryCounterFactory {

    /**
     * Default prefix for distributed retry counters in Redis
     */
    private static final String DEFAULT_REDIS_PREFIX = "retry:counter:";

    /**
     * Creates a retry counter based on the specified type.
     *
     * <p>For {@link RetryCounterType#HYBRID}, this method creates a simple hybrid
     * counter that uses distributed counting for keys starting with "critical:".
     *
     * @param type           the type of counter to create
     * @param redissonClient the Redis client (required for DISTRIBUTED and HYBRID types)
     * @return a new {@link RetryCounter} instance
     * @throws IllegalArgumentException if redissonClient is null for DISTRIBUTED or HYBRID types
     */
    public static RetryCounter create(RetryCounterType type, RedissonClient redissonClient) {
        return switch (type) {
            case LOCAL -> createLocal();
            case DISTRIBUTED -> {
                if (redissonClient == null) {
                    throw new IllegalArgumentException("RedissonClient is required for distributed counter");
                }
                yield createDistributed(redissonClient);
            }
            case HYBRID -> {
                if (redissonClient == null) {
                    throw new IllegalArgumentException("RedissonClient is required for hybrid counter");
                }
                yield createHybrid(redissonClient, "critical:");
            }
        };
    }

    /**
     * Creates a local retry counter with automatic cleanup.
     *
     * @return a new {@link LocalRetryCounter} instance
     */
    public static LocalRetryCounter createLocal() {
        return LocalRetryCounter.create();
    }

    /**
     * Creates a local retry counter without automatic cleanup.
     *
     * <p>Use this when you need full control over the counter lifecycle
     * or when TTL support is not required.
     *
     * @return a new {@link LocalRetryCounter} instance without cleanup
     */
    public static LocalRetryCounter createLocalWithoutCleanup() {
        return LocalRetryCounter.createWithoutCleanup();
    }

    /**
     * Creates a distributed retry counter with the default key prefix.
     *
     * @param redissonClient the Redis client to use
     * @return a new {@link DistributedRetryCounter} instance
     */
    public static DistributedRetryCounter createDistributed(RedissonClient redissonClient) {
        return DistributedRetryCounter.create(redissonClient, DEFAULT_REDIS_PREFIX);
    }

    /**
     * Creates a distributed retry counter with a custom key prefix.
     *
     * @param redissonClient the Redis client to use
     * @param keyPrefix      the prefix for all counter keys in Redis
     * @return a new {@link DistributedRetryCounter} instance
     */
    public static DistributedRetryCounter createDistributed(RedissonClient redissonClient, String keyPrefix) {
        return DistributedRetryCounter.create(redissonClient, keyPrefix);
    }

    /**
     * Creates a hybrid retry counter with pattern-based selection.
     *
     * <p>Keys matching the pattern will use distributed counting, others will use local.
     *
     * @param redissonClient     the Redis client for distributed operations
     * @param criticalKeyPattern regex pattern for keys requiring distributed counting
     * @return a new {@link HybridRetryCounter} instance
     */
    public static HybridRetryCounter createHybrid(RedissonClient redissonClient, String criticalKeyPattern) {
        LocalRetryCounter localCounter = createLocal();
        DistributedRetryCounter distributedCounter = createDistributed(redissonClient);
        return HybridRetryCounter.createWithPattern(localCounter, distributedCounter, criticalKeyPattern);
    }

    /**
     * Creates a hybrid retry counter with prefix-based selection.
     *
     * <p>Keys starting with any of the specified prefixes will use distributed counting.
     *
     * @param redissonClient   the Redis client for distributed operations
     * @param criticalPrefixes array of key prefixes requiring distributed counting
     * @return a new {@link HybridRetryCounter} instance
     */
    public static HybridRetryCounter createHybridWithPrefixes(RedissonClient redissonClient,
                                                              String... criticalPrefixes) {
        LocalRetryCounter localCounter = createLocal();
        DistributedRetryCounter distributedCounter = createDistributed(redissonClient);
        return HybridRetryCounter.createWithPrefixes(localCounter, distributedCounter, criticalPrefixes);
    }

    /**
     * Creates a hybrid retry counter with custom selection logic.
     *
     * <p>This method provides full control over the selection logic through
     * a custom predicate.
     *
     * @param redissonClient          the Redis client for distributed operations
     * @param useDistributedPredicate predicate to determine when to use distributed counting
     * @param fallbackToLocal         whether to fall back to local on distributed failures
     * @return a new {@link HybridRetryCounter} instance
     */
    public static HybridRetryCounter createHybridWithPredicate(
            RedissonClient redissonClient,
            Predicate<String> useDistributedPredicate,
            boolean fallbackToLocal) {
        LocalRetryCounter localCounter = createLocal();
        DistributedRetryCounter distributedCounter = createDistributed(redissonClient);

        return HybridRetryCounter.builder()
                .localCounter(localCounter)
                .distributedCounter(distributedCounter)
                .useDistributedPredicate(useDistributedPredicate)
                .fallbackToLocal(fallbackToLocal)
                .build();
    }

}