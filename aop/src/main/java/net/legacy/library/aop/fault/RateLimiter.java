package net.legacy.library.aop.fault;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Rate limiter implementation for controlling method execution frequency.
 *
 * <p>This class provides various rate limiting strategies to prevent system overload
 * and abuse. It supports fixed window, sliding window, token bucket, and leaky bucket
 * algorithms.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class RateLimiter {

    private final String name;
    private final RateLimiterConfig config;
    private final ReentrantLock lock = new ReentrantLock();

    // Rate limiting state
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());
    private final AtomicInteger currentWindowCount = new AtomicInteger(0);
    private final AtomicInteger previousWindowCount = new AtomicInteger(0);

    // Token bucket specific state
    private final AtomicInteger tokens = new AtomicInteger(0);
    private final AtomicLong lastTokenRefill = new AtomicLong(System.currentTimeMillis());

    // Metrics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong permittedRequests = new AtomicLong(0);
    private final AtomicLong rejectedRequests = new AtomicLong(0);

    /**
     * Constructs a new rate limiter.
     *
     * @param name   the rate limiter name
     * @param config the rate limiter configuration
     */
    public RateLimiter(String name, RateLimiterConfig config) {
        this.name = name;
        this.config = config;

        // Initialize tokens for token bucket strategy
        if (config.getStrategy() == RateLimitStrategy.TOKEN_BUCKET) {
            tokens.set(config.getLimit());
        }
    }

    /**
     * Attempts to acquire a permit for method execution.
     *
     * @return true if the permit was acquired, false otherwise
     */
    public boolean tryAcquire() {
        return tryAcquire(1);
    }

    /**
     * Attempts to acquire a permit for method execution.
     *
     * @param permits the number of permits to acquire
     * @return true if the permit was acquired, false otherwise
     */
    public boolean tryAcquire(int permits) {
        totalRequests.incrementAndGet();

        lock.lock();
        try {
            boolean allowed = false;

            switch (config.getStrategy()) {
                case FIXED_WINDOW:
                    allowed = tryAcquireFixedWindow(permits);
                    break;
                case SLIDING_WINDOW:
                    allowed = tryAcquireSlidingWindow(permits);
                    break;
                case TOKEN_BUCKET:
                    allowed = tryAcquireTokenBucket(permits);
                    break;
                case LEAKY_BUCKET:
                    allowed = tryAcquireLeakyBucket(permits);
                    break;
                default:
            }

            if (allowed) {
                permittedRequests.incrementAndGet();
            } else {
                rejectedRequests.incrementAndGet();
            }

            return allowed;
        } finally {
            lock.unlock();
        }
    }

    /**
     * Waits for the next available slot in the rate limit window.
     *
     * @return true if the permit was acquired
     * @throws InterruptedException if the thread was interrupted
     */
    public boolean acquire() throws InterruptedException {
        return acquire(1, Long.MAX_VALUE);
    }

    /**
     * Waits for the next available slot in the rate limit window.
     *
     * @param permits     the number of permits to acquire
     * @param maxWaitTime the maximum time to wait in milliseconds
     * @return true if the permit was acquired
     * @throws InterruptedException if the thread was interrupted
     */
    public boolean acquire(int permits, long maxWaitTime) throws InterruptedException {
        totalRequests.incrementAndGet();

        long startTime = System.currentTimeMillis();

        while (true) {
            if (tryAcquire(permits)) {
                permittedRequests.incrementAndGet();
                return true;
            }

            long elapsed = System.currentTimeMillis() - startTime;
            if (elapsed >= maxWaitTime) {
                rejectedRequests.incrementAndGet();
                return false;
            }

            // Calculate wait time until next window
            long waitTime = calculateWaitTime();
            if (waitTime > maxWaitTime - elapsed) {
                waitTime = maxWaitTime - elapsed;
            }

            if (waitTime > 0) {
                // noinspection BusyWait
                Thread.sleep(waitTime);
            }
        }
    }

    /**
     * Gets the rate limiter metrics.
     *
     * @return the rate limiter metrics
     */
    public RateLimiterMetrics getMetrics() {
        return new RateLimiterMetrics(
                totalRequests.get(),
                permittedRequests.get(),
                rejectedRequests.get(),
                getCurrentWindowUsage(),
                getRemainingPermits()
        );
    }

    /**
     * Resets the rate limiter state.
     */
    public void reset() {
        lock.lock();
        try {
            windowStartTime.set(System.currentTimeMillis());
            currentWindowCount.set(0);
            previousWindowCount.set(0);
            tokens.set(config.getLimit());
            lastTokenRefill.set(System.currentTimeMillis());
        } finally {
            lock.unlock();
        }
    }

    /**
     * Implements fixed window rate limiting.
     *
     * @param permits the number of permits to acquire
     * @return true if the permit was acquired
     */
    private boolean tryAcquireFixedWindow(int permits) {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        // Check if we need to start a new window
        if (currentTime - windowStart >= config.getPeriod()) {
            windowStartTime.set(currentTime);
            currentWindowCount.set(0);
        }

        // Check if we have enough permits in the current window
        if (currentWindowCount.get() + permits <= config.getLimit()) {
            currentWindowCount.addAndGet(permits);
            return true;
        }

        return false;
    }

    /**
     * Implements sliding window rate limiting.
     *
     * @param permits the number of permits to acquire
     * @return true if the permit was acquired
     */
    private boolean tryAcquireSlidingWindow(int permits) {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        // Calculate the overlap with the previous window
        long windowProgress = currentTime - windowStart;
        double windowRatio = (double) windowProgress / config.getPeriod();

        // If we've moved to a new window, rotate the windows
        if (windowProgress >= config.getPeriod()) {
            previousWindowCount.set(currentWindowCount.get());
            currentWindowCount.set(0);
            windowStartTime.set(currentTime);
            windowRatio = 0.0;
        }

        // Calculate the weighted count from the previous window
        int previousWindowWeight = (int) (previousWindowCount.get() * (1.0 - windowRatio));
        int totalCount = previousWindowWeight + currentWindowCount.get();

        // Check if we have enough permits
        if (totalCount + permits <= config.getLimit()) {
            currentWindowCount.addAndGet(permits);
            return true;
        }

        return false;
    }

    /**
     * Implements token bucket rate limiting.
     *
     * @param permits the number of permits to acquire
     * @return true if the permit was acquired
     */
    private boolean tryAcquireTokenBucket(int permits) {
        refillTokens();

        int currentTokens = tokens.get();
        if (currentTokens >= permits) {
            return tokens.compareAndSet(currentTokens, currentTokens - permits);
        }

        return false;
    }

    /**
     * Implements leaky bucket rate limiting.
     *
     * @param permits the number of permits to acquire
     * @return true if the permit was acquired
     */
    private boolean tryAcquireLeakyBucket(int permits) {
        long currentTime = System.currentTimeMillis();

        // Calculate how many tokens have leaked since last check
        long timeSinceLastLeak = currentTime - lastTokenRefill.get();
        double leakRate = (double) config.getLimit() / config.getPeriod();
        int leakedTokens = (int) (timeSinceLastLeak * leakRate / 1000.0);

        if (leakedTokens > 0) {
            int newTokens = Math.min(tokens.get() + leakedTokens, config.getLimit());
            tokens.set(newTokens);
            lastTokenRefill.set(currentTime);
        }

        // Check if we have enough tokens
        if (tokens.get() + permits <= config.getLimit()) {
            tokens.addAndGet(permits);
            return true;
        }

        return false;
    }

    /**
     * Refills tokens for token bucket strategy.
     */
    private void refillTokens() {
        long currentTime = System.currentTimeMillis();
        long timeSinceLastRefill = currentTime - lastTokenRefill.get();

        if (timeSinceLastRefill >= 1000) { // Refill every second
            double refillRate = (double) config.getLimit() / (config.getPeriod() / 1000.0);
            int tokensToAdd = (int) (timeSinceLastRefill * refillRate / 1000.0);

            int currentTokens = tokens.get();
            int newTokens = Math.min(currentTokens + tokensToAdd, config.getLimit());

            if (tokens.compareAndSet(currentTokens, newTokens)) {
                lastTokenRefill.set(currentTime);
            }
        }
    }

    /**
     * Calculates the wait time until the next available slot.
     *
     * @return the wait time in milliseconds
     */
    private long calculateWaitTime() {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();
        long windowEnd = windowStart + config.getPeriod();

        return Math.max(0, windowEnd - currentTime);
    }

    /**
     * Gets the current window usage.
     *
     * @return the current window usage
     */
    private double getCurrentWindowUsage() {
        if (config.getLimit() == 0) {
            return 0.0;
        }

        return (double) currentWindowCount.get() / config.getLimit();
    }

    /**
     * Gets the remaining permits in the current window.
     *
     * @return the remaining permits
     */
    private int getRemainingPermits() {
        return switch (config.getStrategy()) {
            case FIXED_WINDOW, SLIDING_WINDOW -> Math.max(0, config.getLimit() - currentWindowCount.get());
            case TOKEN_BUCKET -> tokens.get();
            case LEAKY_BUCKET -> Math.max(0, config.getLimit() - tokens.get());
        };
    }

    /**
     * Enumeration of rate limiting strategies.
     */
    public enum RateLimitStrategy {
        FIXED_WINDOW,
        SLIDING_WINDOW,
        TOKEN_BUCKET,
        LEAKY_BUCKET
    }

    /**
     * Configuration for the rate limiter.
     */
    @Getter
    @RequiredArgsConstructor
    public static class RateLimiterConfig {

        private final int limit;
        private final long period;
        private final RateLimitStrategy strategy;

    }

    /**
     * Rate limiter metrics.
     */
    @Getter
    @RequiredArgsConstructor
    public static class RateLimiterMetrics {

        private final long totalRequests;
        private final long permittedRequests;
        private final long rejectedRequests;
        private final double currentWindowUsage;
        private final int remainingPermits;

        public double getRejectionRate() {
            return totalRequests == 0 ? 0.0 : (double) rejectedRequests / totalRequests;
        }

    }

    /**
     * Exception thrown when rate limit is exceeded.
     */
    public static class RateLimitExceededException extends RuntimeException {

        public RateLimitExceededException(String message) {
            super(message);
        }

        public RateLimitExceededException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
