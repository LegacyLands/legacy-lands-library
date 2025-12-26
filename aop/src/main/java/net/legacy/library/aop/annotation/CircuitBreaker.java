package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for enabling circuit breaker pattern on methods.
 *
 * <p>This annotation provides fault tolerance by automatically monitoring method
 * failures and temporarily blocking calls when failure thresholds are exceeded.
 * It implements the circuit breaker pattern with configurable thresholds and
 * recovery strategies.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface CircuitBreaker {

    /**
     * The failure rate threshold to open the circuit (0.0 to 1.0).
     *
     * <p>When the failure rate exceeds this threshold, the circuit opens.
     *
     * @return the failure rate threshold
     */
    double failureRateThreshold() default 0.5;

    /**
     * The number of consecutive failures required to open the circuit.
     *
     * <p>If both failureRateThreshold and failureCount are specified, the circuit
     * will open when either condition is met.
     *
     * @return the failure count threshold
     */
    int failureCountThreshold() default 5;

    /**
     * The minimum number of calls required to calculate the failure rate.
     *
     * <p>This prevents premature circuit opening due to insufficient data.
     *
     * @return the minimum call count
     */
    int minimumNumberOfCalls() default 10;

    /**
     * The sliding window size in milliseconds for failure rate calculation.
     *
     * @return the sliding window size
     */
    long slidingWindowSize() default 60000; // 1 minute

    /**
     * The wait duration in milliseconds before moving to half-open state.
     *
     * @return the wait duration
     */
    long waitDurationInOpenState() default 30000; // 30 seconds

    /**
     * The name of a method that supplies the wait duration in open state dynamically.
     *
     * <p>The method must be accessible from the target class, take no arguments,
     * and return a {@code long} representing milliseconds. When specified,
     * this takes precedence over {@link #waitDurationInOpenState()}.
     *
     * @return the supplier method name for dynamic open duration configuration
     */
    String openDurationSupplier() default "";

    /**
     * The permitted number of calls in half-open state for testing.
     *
     * @return the permitted calls in half-open state
     */
    int permittedNumberOfCallsInHalfOpenState() default 3;

    /**
     * The maximum wait duration in milliseconds for method execution.
     *
     * <p>If the method execution exceeds this duration, it's considered a failure.
     *
     * @return the timeout duration
     */
    long timeoutDuration() default 0; // 0 means no timeout

    /**
     * The fallback method to call when the circuit is open or on timeout.
     *
     * <p>The fallback method must have the same signature as the annotated method.
     * If not specified, a CircuitBreakerOpenException will be thrown.
     *
     * @return the fallback method name
     */
    String fallbackMethod() default "";

    /**
     * The exception types that should trigger the circuit breaker.
     *
     * <p>If empty, all exceptions will trigger the circuit breaker.
     *
     * @return array of exception classes
     */
    Class<? extends Throwable>[] recordFailurePredicate() default {};

    /**
     * The exception types that should be ignored when calculating failure rates.
     *
     * <p>These exceptions will not count toward the failure threshold.
     *
     * @return array of exception classes
     */
    Class<? extends Throwable>[] ignoreExceptions() default {};

    /**
     * Whether to automatically transition from half-open to closed on success.
     *
     * @return true to automatically close on success
     */
    boolean automaticTransitionFromOpenToHalfOpen() default true;

    /**
     * Whether to enable metrics collection for the circuit breaker.
     *
     * @return true to enable metrics
     */
    boolean enableMetrics() default true;

    /**
     * The name of the circuit breaker instance.
     *
     * <p>If not specified, the class name and method name will be used.
     *
     * @return the circuit breaker name
     */
    String name() default "";

}