package net.legacy.library.aop.custom;

import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.util.Properties;

/**
 * Interface for custom execution strategies in AsyncSafe aspect.
 *
 * <p>Custom executors provide complete control over method execution,
 * allowing integration with external frameworks or specialized threading models.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
public interface CustomExecutor {

    /**
     * Executes the method invocation with custom logic
     *
     * @param context the aspect context containing method and target information
     * @param invocation the method invocation to execute
     * @param properties additional configuration properties
     * @return the result of method execution
     * @throws Throwable if execution fails
     */
    Object execute(AspectContext context, MethodInvocation invocation, Properties properties) throws Throwable;

    /**
     * Gets the name of this custom executor
     *
     * @return the executor name
     */
    String getName();

    /**
     * Initializes the executor with configuration properties
     *
     * @param properties configuration properties
     */
    @SuppressWarnings("unused")
    default void initialize(Properties properties) {
        // Default implementation does nothing
    }

    /**
     * Shuts down the executor and releases resources
     */
    default void shutdown() {
        // Default implementation does nothing
    }

    /**
     * Checks if this executor supports the given method context
     *
     * @param context the aspect context
     * @return true if supported, false otherwise
     */
    @SuppressWarnings("unused")
    default boolean supports(AspectContext context) {
        return true;
    }

}