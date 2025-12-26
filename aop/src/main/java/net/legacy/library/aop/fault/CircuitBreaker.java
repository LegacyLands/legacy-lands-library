package net.legacy.library.aop.fault;

import lombok.Getter;

import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Circuit breaker implementation for fault tolerance.
 *
 * <p>This class implements the circuit breaker pattern to prevent cascading failures
 * when external services become unavailable. It monitors failure rates and
 * automatically opens the circuit when thresholds are exceeded.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class CircuitBreaker {

    private final String name;
    private final CircuitBreakerConfig config;
    private final ReentrantLock stateLock = new ReentrantLock();

    // Circuit state
    private final AtomicReference<CircuitState> state = new AtomicReference<>(CircuitState.CLOSED);
    private final AtomicInteger failureCount = new AtomicInteger(0);
    private final AtomicInteger successCount = new AtomicInteger(0);
    private final AtomicLong lastFailureTime = new AtomicLong(0);
    private final AtomicLong windowStartTime = new AtomicLong(System.currentTimeMillis());

    // Metrics
    private final AtomicLong totalCalls = new AtomicLong(0);
    private final AtomicLong successfulCalls = new AtomicLong(0);
    private final AtomicLong failedCalls = new AtomicLong(0);
    private final AtomicLong timeoutCalls = new AtomicLong(0);

    /**
     * Constructs a new circuit breaker.
     *
     * @param name   the circuit breaker name
     * @param config the circuit breaker configuration
     */
    public CircuitBreaker(String name, CircuitBreakerConfig config) {
        this.name = name;
        this.config = config;
    }

    /**
     * Records a successful call.
     */
    public void recordSuccess() {
        stateLock.lock();
        try {
            totalCalls.incrementAndGet();
            successfulCalls.incrementAndGet();

            CircuitState currentState = state.get();

            if (currentState == CircuitState.HALF_OPEN) {
                // In half-open state, success transitions to closed
                if (successCount.incrementAndGet() >= config.getPermittedNumberOfCallsInHalfOpenState()) {
                    transitionToClosed();
                }
            } else if (currentState == CircuitState.CLOSED) {
                // Reset failure count on success in closed state
                failureCount.set(0);
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Records a failed call.
     *
     * @param throwable the exception that caused the failure
     */
    public void recordFailure(Throwable throwable) {
        stateLock.lock();
        try {
            totalCalls.incrementAndGet();
            failedCalls.incrementAndGet();

            CircuitState currentState = state.get();

            // Check if this failure should be recorded
            if (!shouldRecordFailure(throwable)) {
                return;
            }

            failureCount.incrementAndGet();
            lastFailureTime.set(System.currentTimeMillis());

            if (currentState == CircuitState.HALF_OPEN) {
                // In half-open state, any failure immediately opens the circuit
                transitionToOpen();
            } else if (currentState == CircuitState.CLOSED) {
                // In closed state, check if we should open the circuit
                if (shouldOpenCircuit()) {
                    transitionToOpen();
                }
            }
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Records a timeout call.
     */
    public void recordTimeout() {
        stateLock.lock();
        try {
            totalCalls.incrementAndGet();
            timeoutCalls.incrementAndGet();

            // Treat timeout as a failure
            recordFailure(new CircuitBreakerTimeoutException("Method call timed out"));
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Checks if a call is permitted.
     *
     * @return true if the call is permitted
     */
    public boolean isCallPermitted() {
        CircuitState currentState = state.get();

        return switch (currentState) {
            case CLOSED -> true;
            case OPEN -> {
                // Check if we should transition to half-open
                if (config.isAutomaticTransitionFromOpenToHalfOpen() &&
                        shouldTransitionToHalfOpen()) {
                    transitionToHalfOpen();
                    yield true;
                }
                yield false;
            }
            case HALF_OPEN ->
                // In half-open state, only permit limited number of calls
                    successCount.get() < config.getPermittedNumberOfCallsInHalfOpenState();
        };
    }

    /**
     * Gets the current circuit state.
     *
     * @return the current circuit state
     */
    public CircuitState getState() {
        return state.get();
    }

    /**
     * Gets the circuit breaker metrics.
     *
     * @return the circuit breaker metrics
     */
    public CircuitBreakerMetrics getMetrics() {
        return new CircuitBreakerMetrics(
                totalCalls.get(),
                successfulCalls.get(),
                failedCalls.get(),
                timeoutCalls.get(),
                calculateFailureRate(),
                state.get()
        );
    }

    /**
     * Resets the circuit breaker to closed state.
     */
    public void reset() {
        stateLock.lock();
        try {
            state.set(CircuitState.CLOSED);
            failureCount.set(0);
            successCount.set(0);
            windowStartTime.set(System.currentTimeMillis());
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Checks if a failure should be recorded based on exception types.
     *
     * @param throwable the exception to check
     * @return true if the failure should be recorded
     */
    private boolean shouldRecordFailure(Throwable throwable) {
        // Check ignore exceptions first
        for (Class<? extends Throwable> ignoreType : config.getIgnoreExceptions()) {
            if (ignoreType.isInstance(throwable)) {
                return false;
            }
        }

        // If no record failure predicate is specified, record all exceptions
        if (config.getRecordFailurePredicate().length == 0) {
            return true;
        }

        // Check record failure predicate
        for (Class<? extends Throwable> recordType : config.getRecordFailurePredicate()) {
            if (recordType.isInstance(throwable)) {
                return true;
            }
        }

        return false;
    }

    /**
     * Checks if the circuit should be opened.
     *
     * @return true if the circuit should be opened
     */
    private boolean shouldOpenCircuit() {
        int totalCallsInWindow = getTotalCallsInWindow();

        if (totalCallsInWindow < config.getMinimumNumberOfCalls()) {
            return false;
        }

        // Check failure rate threshold
        double failureRate = calculateFailureRate();
        if (failureRate >= config.getFailureRateThreshold()) {
            return true;
        }

        // Check failure count threshold
        return failureCount.get() >= config.getFailureCountThreshold();
    }

    /**
     * Checks if the circuit should transition to half-open state.
     *
     * @return true if the circuit should transition to half-open state
     */
    private boolean shouldTransitionToHalfOpen() {
        long timeSinceLastFailure = System.currentTimeMillis() - lastFailureTime.get();
        return timeSinceLastFailure >= config.getWaitDurationInOpenState();
    }

    /**
     * Calculates the failure rate in the current window.
     *
     * @return the failure rate (0.0 to 1.0)
     */
    private double calculateFailureRate() {
        int totalCallsInWindow = getTotalCallsInWindow();
        if (totalCallsInWindow == 0) {
            return 0.0;
        }

        return (double) failureCount.get() / totalCallsInWindow;
    }

    /**
     * Gets the total calls in the current sliding window.
     *
     * @return the total calls in the window
     */
    private int getTotalCallsInWindow() {
        long currentTime = System.currentTimeMillis();
        long windowStart = windowStartTime.get();

        // Reset window if it has expired
        if (currentTime - windowStart > config.getSlidingWindowSize()) {
            windowStartTime.set(currentTime);
            failureCount.set(0);
            return 0;
        }

        return (int) totalCalls.get();
    }

    /**
     * Transitions the circuit to open state.
     */
    private void transitionToOpen() {
        state.set(CircuitState.OPEN);
        failureCount.set(0);
        successCount.set(0);
    }

    /**
     * Transitions the circuit to half-open state.
     */
    private void transitionToHalfOpen() {
        state.set(CircuitState.HALF_OPEN);
        failureCount.set(0);
        successCount.set(0);
    }

    /**
     * Transitions the circuit to closed state.
     */
    private void transitionToClosed() {
        state.set(CircuitState.CLOSED);
        failureCount.set(0);
        successCount.set(0);
    }

    /**
     * Enumeration of circuit breaker states.
     */
    public enum CircuitState {
        CLOSED,      // Normal operation, calls are allowed
        OPEN,        // Circuit is open, calls are blocked
        HALF_OPEN    // Testing state, limited calls allowed
    }

    /**
     * Configuration for the circuit breaker.
     */
    @Getter
    public static class CircuitBreakerConfig {

        private final double failureRateThreshold;
        private final int failureCountThreshold;
        private final int minimumNumberOfCalls;
        private final long slidingWindowSize;
        private final long waitDurationInOpenState;
        private final int permittedNumberOfCallsInHalfOpenState;
        private final long timeoutDuration;
        private final boolean automaticTransitionFromOpenToHalfOpen;
        private final Class<? extends Throwable>[] recordFailurePredicate;
        private final Class<? extends Throwable>[] ignoreExceptions;

        public CircuitBreakerConfig(double failureRateThreshold, int failureCountThreshold,
                                    int minimumNumberOfCalls, long slidingWindowSize,
                                    long waitDurationInOpenState, int permittedNumberOfCallsInHalfOpenState,
                                    long timeoutDuration, boolean automaticTransitionFromOpenToHalfOpen,
                                    Class<? extends Throwable>[] recordFailurePredicate,
                                    Class<? extends Throwable>[] ignoreExceptions) {
            this.failureRateThreshold = failureRateThreshold;
            this.failureCountThreshold = failureCountThreshold;
            this.minimumNumberOfCalls = minimumNumberOfCalls;
            this.slidingWindowSize = slidingWindowSize;
            this.waitDurationInOpenState = waitDurationInOpenState;
            this.permittedNumberOfCallsInHalfOpenState = permittedNumberOfCallsInHalfOpenState;
            this.timeoutDuration = timeoutDuration;
            this.automaticTransitionFromOpenToHalfOpen = automaticTransitionFromOpenToHalfOpen;
            this.recordFailurePredicate = recordFailurePredicate;
            this.ignoreExceptions = ignoreExceptions;
        }

    }

    /**
     * Circuit breaker metrics.
     */
    @Getter
    public static class CircuitBreakerMetrics {

        private final long totalCalls;
        private final long successfulCalls;
        private final long failedCalls;
        private final long timeoutCalls;
        private final double failureRate;
        private final CircuitState state;

        public CircuitBreakerMetrics(long totalCalls, long successfulCalls, long failedCalls,
                                     long timeoutCalls, double failureRate, CircuitState state) {
            this.totalCalls = totalCalls;
            this.successfulCalls = successfulCalls;
            this.failedCalls = failedCalls;
            this.timeoutCalls = timeoutCalls;
            this.failureRate = failureRate;
            this.state = state;
        }

        public double getSuccessRate() {
            return totalCalls == 0 ? 0.0 : (double) successfulCalls / totalCalls;
        }

    }

    /**
     * Exception thrown when circuit breaker is open.
     */
    public static class CircuitBreakerOpenException extends RuntimeException {

        public CircuitBreakerOpenException(String message) {
            super(message);
        }

        public CircuitBreakerOpenException(String message, Throwable cause) {
            super(message, cause);
        }

    }

    /**
     * Exception thrown when method call times out.
     */
    public static class CircuitBreakerTimeoutException extends RuntimeException {

        public CircuitBreakerTimeoutException(String message) {
            super(message);
        }

    }

}
