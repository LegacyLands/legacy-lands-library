package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for ensuring thread-safe method execution with configurable thread management strategies.
 *
 * <p>Methods annotated with {@code @AsyncSafe} are automatically wrapped with appropriate thread
 * management logic to ensure safe execution in multithreaded environments. The annotation provides
 * fine-grained control over thread selection, synchronization behavior, and timeout handling,
 * making it particularly suitable for Minecraft plugin development where thread safety is critical.
 *
 * @author qwq-dev
 * @version 1.0
 * @see ThreadType
 * @see net.legacy.library.aop.aspect.AsyncSafeAspect
 * @since 2025-06-19 17:41
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface AsyncSafe {

    /**
     * Specifies the target thread type for method execution.
     *
     * <p>The default value is {@link ThreadType#SYNC}, ensuring safe execution for methods that
     * require main thread access (such as most Minecraft API operations).
     *
     * @return the target thread type for method execution
     * @see ThreadType
     */
    ThreadType target() default ThreadType.SYNC;

    /**
     * Specifies the maximum execution time in milliseconds for async operations.
     *
     * <p>This timeout applies to methods executed with {@link ThreadType#ASYNC} and
     * {@link ThreadType#VIRTUAL} target types. If the method execution exceeds this
     * timeout, a {@link java.util.concurrent.TimeoutException} will be thrown.
     *
     * <p>The timeout is designed to prevent indefinite blocking and ensure system
     * responsiveness. For {@link ThreadType#SYNC} operations, this value is ignored
     * as synchronous operations execute immediately on the main thread.
     *
     * <p>Default value is 30,000 milliseconds (30 seconds), which provides a reasonable
     * balance between allowing complex operations to complete and preventing system hangs.
     *
     * @return the maximum execution timeout in milliseconds
     */
    long timeout() default 30000L;

    /**
     * Controls whether re-entrant method calls are permitted.
     *
     * <p>When set to {@code false} (the default), the aspect prevents recursive calls to the
     * same method from the same thread, protecting against infinite recursion and stack overflow
     * errors. This is particularly important for methods that might inadvertently call themselves
     * through complex call chains.
     *
     * <p>When set to {@code true}, re-entrant calls are allowed, which may be necessary for
     * certain algorithms or callback patterns that legitimately require recursive execution.
     * Use this option with caution and ensure proper termination conditions exist.
     *
     * <p>Re-entrance checking is performed per-thread, so concurrent calls from different
     * threads are always allowed regardless of this setting.
     *
     * @return {@code true} if re-entrant calls are allowed, {@code false} to prevent recursion
     */
    boolean allowReentrant() default false;

    /**
     * Specifies the name of the custom executor to use when target is {@link ThreadType#CUSTOM}
     *
     * @return the name of the custom executor to use
     */
    String customExecutor() default "";

    /**
     * Specifies the name of the custom lock strategy when target is {@link ThreadType#CUSTOM}
     *
     * @return the name of the custom lock strategy to use
     */
    String customLockStrategy() default "";

    /**
     * Specifies the name of the custom timeout handler when target is {@link ThreadType#CUSTOM}
     *
     * @return the name of the custom timeout handler to use
     */
    String customTimeoutHandler() default "";

    /**
     * Additional configuration properties for custom execution strategies
     *
     * @return array of configuration properties
     */
    String[] customProperties() default {};

    /**
     * Enumeration of available thread execution strategies for the {@code @AsyncSafe} annotation.
     *
     * <p>Each thread type provides different execution characteristics and is suitable for
     * different use cases within the Minecraft plugin environment. The choice of thread type
     * affects performance, safety, and API accessibility.
     */
    enum ThreadType {
        /**
         * Executes the method on the main server thread (synchronous execution).
         *
         * <p>This is the safest option for methods that need to interact with the Minecraft
         * server API, as most Bukkit/Spigot operations are not thread-safe and must be
         * performed on the main thread. This includes player manipulation, world modification,
         * and most game state changes.
         *
         * <p>Use this mode when thread safety and API compatibility are more important
         * than performance, or when the operation is quick enough not to cause server lag.
         */
        SYNC,

        /**
         * Executes the method on a background thread from the async thread pool.
         *
         * <p>This option is suitable for CPU-intensive operations, I/O operations, or
         * database queries that should not block the main server thread. However, methods
         * executed in async mode cannot directly interact with most Minecraft APIs.
         *
         * <p>Use this mode for operations that are performance-critical and do not require
         * direct server API access, such as data processing, file operations, or network calls.
         */
        ASYNC,

        /**
         * Executes the method on a virtual thread for efficient concurrent processing.
         *
         * <p>Virtual threads (available in Java 19+) provide lightweight concurrency with
         * minimal overhead, making them ideal for I/O-bound operations or when handling
         * many concurrent tasks. Like async threads, virtual threads cannot directly
         * interact with most Minecraft APIs.
         *
         * <p>Use this mode for high-concurrency scenarios or when dealing with many
         * simultaneous operations that involve waiting (such as network requests or
         * database operations).
         */
        VIRTUAL,

        /**
         * Executes the method using custom executor and strategies.
         *
         * <p>This mode allows complete customization of execution behavior through
         * user-defined executors, lock strategies, and timeout handlers. Custom
         * execution strategies must be registered with the AsyncSafeAspect before use.
         *
         * <p>Use this mode when none of the predefined thread types meet your specific
         * requirements, such as custom thread pools, specialized synchronization
         * mechanisms, or integration with external frameworks.
         */
        CUSTOM
    }

}