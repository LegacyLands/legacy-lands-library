package net.legacy.library.player.task.redis.resilience;

import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Local implementation of {@link RetryCounter} that tracks retry attempts in memory.
 *
 * <p>This implementation uses a {@link ConcurrentHashMap} to store retry counts locally
 * within the current JVM instance. It provides excellent performance with minimal latency
 * but does not persist across server restarts or share state between different instances.
 * The implementation offers sub-millisecond operations with no network overhead, thread-safe
 * operations within a single JVM instance, and automatic expiration using scheduled cleanup,
 * though counts are lost on restart.
 *
 * <p>This implementation is ideal for high-frequency retry scenarios where performance is
 * critical, operations that can tolerate retry count loss on server restart, and single-server
 * deployments or sticky-session architectures.
 *
 * @author qwq-dev
 * @see RetryCounter
 * @see DistributedRetryCounter
 * @since 2025-06-07 10:00
 */
@RequiredArgsConstructor
public class LocalRetryCounter implements RetryCounter {

    /**
     * Thread-safe map storing retry counts for each key
     */
    private final ConcurrentMap<String, AtomicInteger> counters = new ConcurrentHashMap<>();

    /**
     * Thread-safe map storing expiration timestamps for TTL support
     */
    private final ConcurrentMap<String, Long> expirationTimes = new ConcurrentHashMap<>();

    /**
     * Scheduler for TTL cleanup tasks
     */
    private final ScheduledExecutorService cleanupScheduler;

    /**
     * Flag to track if cleanup task is running
     */
    private final AtomicBoolean cleanupTaskRunning = new AtomicBoolean(false);

    /**
     * Creates a new LocalRetryCounter with automatic TTL cleanup.
     *
     * <p>The cleanup task runs every minute to remove expired entries, keeping
     * memory usage under control for long-running applications.
     *
     * @return a new {@link LocalRetryCounter} instance with cleanup enabled
     */
    public static LocalRetryCounter create() {
        ScheduledExecutorService scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable);
            thread.setName("LocalRetryCounter-Cleanup");
            thread.setDaemon(true);
            return thread;
        });

        LocalRetryCounter counter = new LocalRetryCounter(scheduler);
        counter.startCleanupTask();
        return counter;
    }

    /**
     * Creates a new LocalRetryCounter without automatic cleanup.
     *
     * <p>Use this factory method when you want to manage cleanup manually or
     * when TTL support is not needed. Be aware that without cleanup, memory
     * usage may grow over time.
     *
     * @return a new {@link LocalRetryCounter} instance without automatic cleanup
     */
    public static LocalRetryCounter createWithoutCleanup() {
        return new LocalRetryCounter(null);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> increment(String key) {
        return CompletableFuture.completedFuture(
                counters.computeIfAbsent(key, k -> new AtomicInteger(0))
                        .incrementAndGet()
        );
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

        int newCount = counters.computeIfAbsent(key, k -> new AtomicInteger(0))
                .incrementAndGet();

        if (ttl != null && !ttl.isZero() && !ttl.isNegative()) {
            expirationTimes.put(key, System.currentTimeMillis() + ttl.toMillis());
        }

        return CompletableFuture.completedFuture(newCount);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Integer> get(String key) {
        AtomicInteger counter = counters.get(key);
        return CompletableFuture.completedFuture(counter != null ? counter.get() : 0);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> reset(String key) {
        counters.remove(key);
        expirationTimes.remove(key);
        return CompletableFuture.completedFuture(null);
    }

    /**
     * {@inheritDoc}
     *
     * @param key {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Boolean> exists(String key) {
        return CompletableFuture.completedFuture(counters.containsKey(key));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public RetryCounterType getType() {
        return RetryCounterType.LOCAL;
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        if (cleanupScheduler != null && !cleanupScheduler.isShutdown()) {
            cleanupScheduler.shutdown();
            try {
                if (!cleanupScheduler.awaitTermination(5, TimeUnit.SECONDS)) {
                    cleanupScheduler.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                cleanupScheduler.shutdownNow();
            }
        }
        counters.clear();
        expirationTimes.clear();
    }

    /**
     * Starts the periodic cleanup task for expired entries.
     *
     * <p>The cleanup task runs every minute and removes entries that have
     * exceeded their TTL. This prevents memory leaks in long-running applications.
     */
    private void startCleanupTask() {
        if (cleanupScheduler != null && cleanupTaskRunning.compareAndSet(false, true)) {
            cleanupScheduler.scheduleAtFixedRate(this::cleanupExpiredEntries, 1, 1, TimeUnit.MINUTES);
        }
    }

    /**
     * Removes expired entries from the counter maps.
     *
     * <p>This method is called periodically by the cleanup scheduler to remove
     * entries that have exceeded their TTL, preventing unbounded memory growth.
     */
    private void cleanupExpiredEntries() {
        try {
            long currentTime = System.currentTimeMillis();
            expirationTimes.entrySet().removeIf(entry -> {
                if (entry.getValue() < currentTime) {
                    String key = entry.getKey();
                    counters.remove(key);
                    return true;
                }
                return false;
            });
        } catch (Exception exception) {
            Log.error("Error during LocalRetryCounter cleanup", exception);
        }
    }

    /**
     * Gets the current size of the counter map.
     *
     * <p>This method is useful for monitoring memory usage and debugging.
     *
     * @return the number of keys currently tracked
     */
    public int size() {
        return counters.size();
    }

    /**
     * Clears all counters and expiration times.
     *
     * <p>This method removes all tracked retry counts but keeps the counter
     * instance active for future use.
     */
    public void clear() {
        counters.clear();
        expirationTimes.clear();
    }

}