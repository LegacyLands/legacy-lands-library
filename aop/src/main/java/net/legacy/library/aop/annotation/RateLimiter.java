package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for enabling rate limiting on methods.
 *
 * <p>This annotation provides rate limiting capabilities to control the number
 * of method executions within a specified time window. It supports various
 * rate limiting strategies including fixed window, sliding window, and token bucket.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface RateLimiter {

    /**
     * The maximum number of calls allowed within the time window.
     *
     * @return the maximum number of calls
     */
    int limit();

    /**
     * The time window duration in milliseconds.
     *
     * @return the time window duration
     */
    long period();

    /**
     * The rate limiting strategy to use.
     *
     * @return the rate limiting strategy
     */
    RateLimitStrategy strategy() default RateLimitStrategy.FIXED_WINDOW;

    /**
     * The key expression for key-based rate limiting.
     *
     * <p>Supports placeholders like {#paramName} for method parameters,
     * {#user.id} for user ID, {#remoteAddress} for client IP, etc.
     *
     * @return the key expression
     */
    String keyExpression() default "";

    /**
     * The fallback method to call when rate limit is exceeded.
     *
     * <p>The fallback method must have the same signature as the annotated method.
     * If not specified, a RateLimitExceededException will be thrown.
     *
     * @return the fallback method name
     */
    String fallbackMethod() default "";

    /**
     * Whether to wait for the next available slot when the limit is reached.
     *
     * <p>When true, the method will wait until the next time window.
     * When false, it will immediately throw an exception.
     *
     * @return true to wait for next slot
     */
    boolean waitForNextSlot() default false;

    /**
     * The maximum wait time in milliseconds when waitForNextSlot is true.
     *
     * @return the maximum wait time
     */
    long maxWaitTime() default 5000; // 5 seconds

    /**
     * Whether to enable distributed rate limiting across multiple instances.
     *
     * <p>When enabled, uses a distributed coordination mechanism like Redis.
     *
     * @return true to enable distributed rate limiting
     */
    boolean distributed() default false;

    /**
     * The distributed lock timeout in milliseconds for distributed rate limiting.
     *
     * @return the distributed lock timeout
     */
    long distributedLockTimeout() default 5000; // 5 seconds

    /**
     * The name of the rate limiter instance.
     *
     * <p>If not specified, the class name and method name will be used.
     *
     * @return the rate limiter name
     */
    String name() default "";

    /**
     * Enumeration of rate limiting strategies.
     */
    enum RateLimitStrategy {
        /**
         * Fixed window rate limiting with periodic resets.
         */
        FIXED_WINDOW,

        /**
         * Sliding window rate limiting with smooth transitions.
         */
        SLIDING_WINDOW,

        /**
         * Token bucket algorithm with burst handling.
         */
        TOKEN_BUCKET,

        /**
         * Leaky bucket algorithm with constant output rate.
         */
        LEAKY_BUCKET
    }

}