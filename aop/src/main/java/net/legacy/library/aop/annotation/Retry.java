package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for enabling retry mechanisms on methods.
 *
 * <p>This annotation provides automatic retry capabilities for transient failures
 * with configurable retry policies, backoff strategies, and exception handling.
 * It's designed to handle temporary network issues, service unavailability,
 * and other recoverable failures.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Retry {

    /**
     * The maximum number of retry attempts.
     *
     * <p>Value of 0 means no retries, -1 means unlimited retries.
     *
     * @return the maximum number of retry attempts
     */
    int maxAttempts() default 3;

    /**
     * The initial delay between retries in milliseconds.
     *
     * @return the initial delay
     */
    long initialDelay() default 1000; // 1 second

    /**
     * The maximum delay between retries in milliseconds.
     *
     * @return the maximum delay
     */
    long maxDelay() default 30000; // 30 seconds

    /**
     * The backoff strategy to use for retry delays.
     *
     * @return the backoff strategy
     */
    BackoffStrategy backoffStrategy() default BackoffStrategy.EXPONENTIAL;

    /**
     * The multiplier for exponential backoff.
     *
     * @return the backoff multiplier
     */
    double backoffMultiplier() default 2.0;

    /**
     * The jitter factor to add randomness to retry delays.
     *
     * <p>Value between 0.0 and 1.0. 0.0 means no jitter.
     *
     * @return the jitter factor
     */
    double jitterFactor() default 0.1;

    /**
     * The exception types that should trigger a retry.
     *
     * <p>If empty, all exceptions will trigger a retry.
     *
     * @return array of exception classes
     */
    Class<? extends Throwable>[] retryOn() default {};

    /**
     * The exception types that should not trigger a retry.
     *
     * <p>These exceptions will be immediately propagated without retrying.
     *
     * @return array of exception classes
     */
    Class<? extends Throwable>[] ignoreExceptions() default {};

    /**
     * The fallback method to call when all retries are exhausted.
     *
     * <p>The fallback method must have the same signature as the annotated method.
     * If not specified, the last exception will be thrown.
     *
     * @return the fallback method name
     */
    String fallbackMethod() default "";

    /**
     * Whether to include the original exception in the fallback method call.
     *
     * <p>When true, the fallback method should accept an additional Throwable parameter.
     *
     * @return true to include exception in fallback
     */
    boolean includeExceptionInFallback() default false;

    /**
     * Whether to propagate context between retry attempts.
     *
     * <p>This includes trace context, transaction context, and other contextual information.
     *
     * @return true to propagate context
     */
    boolean propagateContext() default true;

    /**
     * The timeout for each retry attempt in milliseconds.
     *
     * <p>0 means no timeout.
     *
     * @return the retry timeout
     */
    long timeout() default 0;

    /**
     * Enumeration of backoff strategies.
     */
    enum BackoffStrategy {
        /**
         * Fixed delay between retries.
         */
        FIXED,

        /**
         * Exponential backoff with optional multiplier.
         */
        EXPONENTIAL,

        /**
         * Linear backoff with constant increment.
         */
        LINEAR,

        /**
         * Random delay between min and max values.
         */
        RANDOM
    }

}