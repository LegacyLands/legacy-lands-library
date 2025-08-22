package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import lombok.Builder;
import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.function.Predicate;

/**
 * Hybrid implementation of {@link RetryCounter} that dynamically chooses between
 * local and distributed counting strategies based on configurable criteria.
 *
 * <p>This implementation provides the best of both worlds by allowing developers
 * to define rules that determine when to use local counting (for performance)
 * versus distributed counting (for consistency). The selection can be based on
 * various factors such as operation type, criticality, or custom predicates.
 * It dynamically selects counter type per operation, supports custom predicates
 * for selection logic, handles failures with configurable fallback strategies,
 * and uses local counting when possible for optimal performance.
 *
 * <p>Common usage patterns include using distributed counting for payment operations,
 * local counting for read-heavy operations, and switching to distributed counting
 * during high-criticality periods.
 *
 * @author qwq-dev
 * @see RetryCounter
 * @see LocalRetryCounter
 * @see DistributedRetryCounter
 * @since 2025-06-07 10:00
 */
@Getter
@Builder
@RequiredArgsConstructor
public class HybridRetryCounter implements RetryCounter {

    /**
     * Local counter for high-performance scenarios
     */
    private final LocalRetryCounter localCounter;

    /**
     * Distributed counter for consistency-critical scenarios
     */
    private final DistributedRetryCounter distributedCounter;

    /**
     * Predicate to determine when to use distributed counting
     */
    private final Predicate<String> useDistributedPredicate;

    /**
     * Whether to fall back to local counting if distributed fails
     */
    @Builder.Default
    private final boolean fallbackToLocal = true;

    /**
     * Creates a hybrid counter with a simple key-pattern based selection.
     *
     * <p>Keys matching the pattern will use distributed counting, others will use local.
     * This is a convenient factory method for common use cases.
     *
     * @param localCounter       the local counter implementation
     * @param distributedCounter the distributed counter implementation
     * @param criticalKeyPattern regex pattern for keys requiring distributed counting
     * @return a new {@link HybridRetryCounter} instance
     */
    public static HybridRetryCounter createWithPattern(
            LocalRetryCounter localCounter,
            DistributedRetryCounter distributedCounter,
            String criticalKeyPattern) {
        return HybridRetryCounter.builder()
                .localCounter(localCounter)
                .distributedCounter(distributedCounter)
                .useDistributedPredicate(key -> key.matches(criticalKeyPattern))
                .build();
    }

    /**
     * Creates a hybrid counter that uses distributed counting for specified prefixes.
     *
     * <p>This factory method is useful when certain operation types (identified by
     * key prefixes) require distributed counting.
     *
     * @param localCounter       the local counter implementation
     * @param distributedCounter the distributed counter implementation
     * @param criticalPrefixes   array of key prefixes requiring distributed counting
     * @return a new {@link HybridRetryCounter} instance
     */
    public static HybridRetryCounter createWithPrefixes(
            LocalRetryCounter localCounter,
            DistributedRetryCounter distributedCounter,
            String... criticalPrefixes) {
        return HybridRetryCounter.builder()
                .localCounter(localCounter)
                .distributedCounter(distributedCounter)
                .useDistributedPredicate(key -> {
                    for (String prefix : criticalPrefixes) {
                        if (key.startsWith(prefix)) {
                            return true;
                        }
                    }
                    return false;
                })
                .build();
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> increment(String key) {
        RetryCounter selectedCounter = selectCounter(key);

        if (selectedCounter == distributedCounter && fallbackToLocal) {
            // Try distributed first, fallback to local on failure
            return distributedCounter.increment(key)
                    .exceptionally(throwable -> {
                        // Log the failure and fallback to local
                        logDistributedFailure(key, throwable);
                        return localCounter.increment(key).join();
                    });
        }

        return selectedCounter.increment(key);
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
        RetryCounter selectedCounter = selectCounter(key);

        if (selectedCounter == distributedCounter && fallbackToLocal) {
            // Try distributed first, fallback to local on failure
            return distributedCounter.increment(key, ttl)
                    .exceptionally(throwable -> {
                        // Log the failure and fallback to local
                        logDistributedFailure(key, throwable);
                        return localCounter.increment(key, ttl).join();
                    });
        }

        return selectedCounter.increment(key, ttl);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> get(String key) {
        RetryCounter selectedCounter = selectCounter(key);

        if (selectedCounter == distributedCounter && fallbackToLocal) {
            // Try distributed first, fallback to local on failure
            return distributedCounter.get(key)
                    .exceptionally(throwable -> {
                        logDistributedFailure(key, throwable);
                        return localCounter.get(key).join();
                    });
        }

        return selectedCounter.get(key);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> reset(String key) {
        // Reset both counters to ensure consistency
        CompletableFuture<Void> localReset = localCounter.reset(key);
        CompletableFuture<Void> distributedReset = distributedCounter.reset(key)
                .exceptionally(throwable -> {
                    logDistributedFailure(key, throwable);
                    return null;
                });

        return CompletableFuture.allOf(localReset, distributedReset);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Boolean> exists(String key) {
        RetryCounter selectedCounter = selectCounter(key);

        if (selectedCounter == distributedCounter && fallbackToLocal) {
            // Check both counters and return true if either has the key
            return distributedCounter.exists(key)
                    .exceptionally(throwable -> {
                        logDistributedFailure(key, throwable);
                        return false;
                    })
                    .thenCombine(localCounter.exists(key), (distExists, localExists) ->
                            distExists || localExists);
        }

        return selectedCounter.exists(key);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public RetryCounterType getType() {
        return RetryCounterType.HYBRID;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        localCounter.close();
        distributedCounter.close();
    }

    /**
     * Selects the appropriate counter based on the configured predicate.
     *
     * @param key the key to evaluate
     * @return the selected {@link RetryCounter} implementation
     */
    private RetryCounter selectCounter(String key) {
        if (useDistributedPredicate.test(key)) {
            return distributedCounter;
        }
        return localCounter;
    }

    /**
     * Logs distributed counter failures for monitoring and debugging.
     *
     * @param key       the key that failed
     * @param throwable the exception that occurred
     */
    private void logDistributedFailure(String key, Throwable throwable) {
        Log.error(
                "Distributed retry counter failed for key: %s, falling back to local counter",
                key,
                throwable
        );
    }

    /**
     * Checks if a specific key would use distributed counting.
     *
     * @param key the key to check
     * @return true if the key would use distributed counting, false for local
     */
    public boolean wouldUseDistributed(String key) {
        return useDistributedPredicate.test(key);
    }

}