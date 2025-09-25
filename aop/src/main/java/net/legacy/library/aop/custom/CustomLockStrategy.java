package net.legacy.library.aop.custom;

import net.legacy.library.aop.model.AspectContext;

import java.util.Properties;
import java.util.concurrent.Callable;

/**
 * Interface for custom lock strategies in AsyncSafe aspect.
 *
 * <p>Custom lock strategies allow specialized synchronization mechanisms
 * beyond the default ReentrantLock implementation.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
public interface CustomLockStrategy {

    /**
     * Executes the given operation with appropriate locking mechanism
     *
     * @param context the aspect context
     * @param operation the operation to execute under lock
     * @param properties configuration properties
     * @param <T> the return type of the operation
     * @return the result of the operation
     * @throws Exception if execution fails
     */
    <T> T executeWithLock(AspectContext context, Callable<T> operation, Properties properties) throws Exception;

    /**
     * Gets the name of this lock strategy
     *
     * @return the strategy name
     */
    String getName();

    /**
     * Initializes the lock strategy with configuration properties
     *
     * @param properties configuration properties
     */
    @SuppressWarnings("unused")
    default void initialize(Properties properties) {
        // Default implementation does nothing
    }

    /**
     * Checks if re-entrant access is currently held for the given context
     *
     * @param context the aspect context
     * @return true if re-entrant access is detected
     */
    default boolean isReentrant(AspectContext context) {
        return false;
    }

    /**
     * Releases any resources associated with this lock strategy
     */
    default void shutdown() {
        // Default implementation does nothing
    }

}