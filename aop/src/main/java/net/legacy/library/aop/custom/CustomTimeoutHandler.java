package net.legacy.library.aop.custom;

import net.legacy.library.aop.model.AspectContext;

import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for custom timeout handling in AsyncSafe aspect.
 *
 * <p>Custom timeout handlers allow specialized timeout behavior such as
 * retry mechanisms, graceful degradation, or custom exception handling.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
public interface CustomTimeoutHandler {

    /**
     * Handles timeout scenarios for async method execution
     *
     * @param context    the aspect context
     * @param future     the future that timed out
     * @param timeout    the timeout value that was exceeded
     * @param properties configuration properties
     * @return the result to return (maybe a fallback value)
     * @throws Throwable if timeout should result in an exception
     */
    Object handleTimeout(AspectContext context, CompletableFuture<?> future,
                         long timeout, Properties properties) throws Throwable;

    /**
     * Gets the name of this timeout handler
     *
     * @return the handler name
     */
    String getName();

    /**
     * Initializes the timeout handler with configuration properties
     *
     * @param properties configuration properties
     */
    @SuppressWarnings("unused")
    default void initialize(Properties properties) {
        // Default implementation does nothing
    }

    /**
     * Called before a method execution to prepare for potential timeout
     *
     * @param context    the aspect context
     * @param timeout    the configured timeout
     * @param properties configuration properties
     */
    @SuppressWarnings("unused")
    default void beforeExecution(AspectContext context, long timeout, Properties properties) {
        // Default implementation does nothing
    }

    /**
     * Called after successful method execution
     *
     * @param context       the aspect context
     * @param result        the execution result
     * @param executionTime the actual execution time
     * @param properties    configuration properties
     */
    @SuppressWarnings("unused")
    default void afterExecution(AspectContext context, Object result,
                                long executionTime, Properties properties) {
        // Default implementation does nothing
    }

    /**
     * Releases any resources associated with this timeout handler
     */
    default void shutdown() {
        // Default implementation does nothing
    }

}