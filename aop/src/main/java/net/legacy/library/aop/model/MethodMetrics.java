package net.legacy.library.aop.model;

import lombok.ToString;

import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Container for method execution metrics.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ToString
public class MethodMetrics {

    private final LongAdder invocationCount = new LongAdder();
    private final LongAdder totalDuration = new LongAdder();
    private final LongAdder failureCount = new LongAdder();
    private final AtomicLong minDuration = new AtomicLong(Long.MAX_VALUE);
    private final AtomicLong maxDuration = new AtomicLong(0);

    /**
     * Records a method execution with its duration.
     *
     * @param duration the execution duration in milliseconds
     */
    public void recordExecution(long duration) {
        invocationCount.increment();
        totalDuration.add(duration);
        minDuration.updateAndGet(current -> Math.min(current, duration));
        maxDuration.updateAndGet(current -> Math.max(current, duration));
    }

    /**
     * Increments the failure count.
     */
    public void incrementFailures() {
        failureCount.increment();
    }

    /**
     * Gets the total number of method invocations.
     *
     * @return the invocation count
     */
    public long getInvocationCount() {
        return invocationCount.sum();
    }

    /**
     * Gets the total duration of all method invocations.
     *
     * @return the total duration in milliseconds
     */
    public long getTotalDuration() {
        return totalDuration.sum();
    }

    /**
     * Gets the average duration of method invocations.
     *
     * @return the average duration in milliseconds
     */
    public long getAverageDuration() {
        long count = invocationCount.sum();
        return count > 0 ? totalDuration.sum() / count : 0;
    }

    /**
     * Gets the minimum duration of any method invocation.
     *
     * @return the minimum duration in milliseconds
     */
    public long getMinDuration() {
        return minDuration.get() == Long.MAX_VALUE ? 0 : minDuration.get();
    }

    /**
     * Gets the maximum duration of any method invocation.
     *
     * @return the maximum duration in milliseconds
     */
    public long getMaxDuration() {
        return maxDuration.get();
    }

    /**
     * Gets the total number of failed method invocations.
     *
     * @return the failure count
     */
    public long getFailureCount() {
        return failureCount.sum();
    }

}