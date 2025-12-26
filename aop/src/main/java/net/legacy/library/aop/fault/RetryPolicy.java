package net.legacy.library.aop.fault;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Retry mechanism for handling transient failures.
 *
 * <p>This class provides retry capabilities with configurable policies, backoff
 * strategies, and exception handling. It's designed to handle temporary network
 * issues, service unavailability, and other recoverable failures.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class RetryPolicy {

    private final String name;
    private final RetryConfig config;
    private final ScheduledExecutorService scheduler;

    /**
     * Constructs a new retry policy.
     *
     * @param name      the retry policy name
     * @param config    the retry configuration
     * @param scheduler the scheduler for delayed retries
     */
    public RetryPolicy(String name, RetryConfig config, ScheduledExecutorService scheduler) {
        this.name = name;
        this.config = config;
        this.scheduler = scheduler;
    }

    /**
     * Executes a task with retry logic.
     *
     * @param task the task to execute
     * @param <T>  the return type
     * @return a CompletableFuture that completes with the result
     */
    public <T> CompletableFuture<T> executeWithRetry(RetryTask<T> task) {
        CompletableFuture<T> resultFuture = new CompletableFuture<>();
        AtomicInteger attemptCount = new AtomicInteger(0);

        executeAttempt(task, resultFuture, attemptCount);

        return resultFuture;
    }

    /**
     * Executes a single attempt of the task.
     *
     * @param task         the task to execute
     * @param resultFuture the result future
     * @param attemptCount the current attempt count
     * @param <T>          the return type
     */
    private <T> void executeAttempt(RetryTask<T> task, CompletableFuture<T> resultFuture,
                                    AtomicInteger attemptCount) {
        int currentAttempt = attemptCount.incrementAndGet();

        if (currentAttempt > 1) {
            // Calculate delay before retry
            long delay = calculateDelay(currentAttempt - 1);
            scheduler.schedule(() -> attemptExecution(task, resultFuture, attemptCount, currentAttempt), delay, TimeUnit.MILLISECONDS);
        } else {
            // Execute immediately for first attempt
            attemptExecution(task, resultFuture, attemptCount, currentAttempt);
        }
    }

    /**
     * Attempts to execute the task.
     *
     * @param task           the task to execute
     * @param resultFuture   the result future
     * @param attemptCount   the current attempt count
     * @param currentAttempt the current attempt number
     * @param <T>            the return type
     */
    private <T> void attemptExecution(RetryTask<T> task, CompletableFuture<T> resultFuture,
                                      AtomicInteger attemptCount, int currentAttempt) {
        try {
            // Execute with timeout if specified
            CompletableFuture<T> taskFuture;
            if (config.getTimeout() > 0) {
                taskFuture = task.executeWithTimeout(config.getTimeout());
            } else {
                taskFuture = task.execute();
            }

            taskFuture.whenComplete((result, throwable) -> {
                if (throwable != null) {
                    handleFailure(task, resultFuture, attemptCount, throwable, currentAttempt);
                } else {
                    resultFuture.complete(result);
                }
            });
        } catch (Throwable throwable) {
            handleFailure(task, resultFuture, attemptCount, throwable, currentAttempt);
        }
    }

    /**
     * Handles execution failure.
     *
     * @param task           the task that failed
     * @param resultFuture   the result future
     * @param attemptCount   the current attempt count
     * @param throwable      the failure exception
     * @param currentAttempt the current attempt number
     * @param <T>            the return type
     */
    private <T> void handleFailure(RetryTask<T> task, CompletableFuture<T> resultFuture,
                                   AtomicInteger attemptCount, Throwable throwable,
                                   int currentAttempt) {
        // Check if we should retry
        if (shouldRetry(throwable, currentAttempt)) {
            // Schedule next attempt
            executeAttempt(task, resultFuture, attemptCount);
        } else {
            // All attempts exhausted or should not retry
            resultFuture.completeExceptionally(throwable);
        }
    }

    /**
     * Checks if we should retry based on the exception and attempt count.
     *
     * @param throwable      the exception that occurred
     * @param currentAttempt the current attempt number
     * @return true if we should retry
     */
    private boolean shouldRetry(Throwable throwable, int currentAttempt) {
        // Check if we've exceeded max attempts
        if (config.getMaxAttempts() >= 0 && currentAttempt >= config.getMaxAttempts()) {
            return false;
        }

        // Check ignore exceptions first
        for (Class<? extends Throwable> ignoreType : config.getIgnoreExceptions()) {
            if (ignoreType.isInstance(throwable)) {
                return false;
            }
        }

        // If no retry predicate is specified, retry all exceptions
        if (config.getRetryOn().length == 0) {
            return true;
        }

        // Check retry predicate
        for (Class<? extends Throwable> retryType : config.getRetryOn()) {
            if (retryType.isInstance(throwable)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Calculates the delay before the next retry.
     *
     * @param attemptNumber the attempt number (0-based)
     * @return the delay in milliseconds
     */
    private long calculateDelay(int attemptNumber) {
        long delay;

        switch (config.getBackoffStrategy()) {
            case EXPONENTIAL:
                delay = (long) (config.getInitialDelay() * Math.pow(config.getBackoffMultiplier(), attemptNumber - 1));
                break;

            case LINEAR:
                delay = config.getInitialDelay() + (attemptNumber - 1) * config.getInitialDelay();
                break;

            case RANDOM:
                long minDelay = config.getInitialDelay();
                long maxDelay = config.getMaxDelay();
                delay = minDelay + (long) (Math.random() * (maxDelay - minDelay));
                break;
            case FIXED:

            default:
                delay = config.getInitialDelay();
        }

        // Apply jitter
        if (config.getJitterFactor() > 0.0) {
            double jitterRange = delay * config.getJitterFactor();
            double jitter = (Math.random() - 0.5) * 2 * jitterRange;
            delay = Math.max(0, (long) (delay + jitter));
        }

        // Cap at maximum delay
        return Math.min(delay, config.getMaxDelay());
    }

    /**
     * Enumeration of backoff strategies.
     */
    public enum BackoffStrategy {
        FIXED,
        EXPONENTIAL,
        LINEAR,
        RANDOM
    }

    /**
     * Task interface for retry execution.
     *
     * @param <T> the return type
     */
    @FunctionalInterface
    public interface RetryTask<T> {

        /**
         * Executes the task.
         *
         * @return a CompletableFuture that completes with the result
         */
        CompletableFuture<T> execute();

        /**
         * Executes the task with a timeout.
         *
         * @param timeout the timeout in milliseconds
         * @return a CompletableFuture that completes with the result
         */
        default CompletableFuture<T> executeWithTimeout(long timeout) {
            CompletableFuture<T> future = execute();

            // Create a timeout future
            CompletableFuture<T> timeoutFuture = new CompletableFuture<>();

            // Schedule timeout
            CompletableFuture.delayedExecutor(timeout, TimeUnit.MILLISECONDS)
                    .execute(() -> {
                        if (!future.isDone()) {
                            timeoutFuture.completeExceptionally(
                                    new RetryTimeoutException("Task execution timed out after " + timeout + "ms")
                            );
                        }
                    });

            // Complete when either the task or timeout completes
            future.acceptEither(timeoutFuture, result -> {
                if (!timeoutFuture.isDone()) {
                    timeoutFuture.complete(result);
                }
            });

            return timeoutFuture;
        }

    }

    /**
     * Configuration for the retry policy.
     */
    @Getter
    @RequiredArgsConstructor
    public static class RetryConfig {

        private final int maxAttempts;
        private final long initialDelay;
        private final long maxDelay;
        private final BackoffStrategy backoffStrategy;
        private final double backoffMultiplier;
        private final double jitterFactor;
        private final Class<? extends Throwable>[] retryOn;
        private final Class<? extends Throwable>[] ignoreExceptions;
        private final long timeout;
        private final boolean propagateContext;

        public boolean shouldPropagateContext() {
            return propagateContext;
        }

    }

    /**
     * Exception thrown when retry execution times out.
     */
    public static class RetryTimeoutException extends RuntimeException {

        public RetryTimeoutException(String message) {
            super(message);
        }

        public RetryTimeoutException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}