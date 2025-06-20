package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Automatically logs method invocations and results.
 *
 * <p>Methods annotated with {@code @Logged} will have their entry, exit, and
 * optionally their arguments and return values logged.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Logged {
    /**
     * The logging level to use.
     *
     * @return the log level
     */
    LogLevel level() default LogLevel.DEBUG;

    /**
     * Whether to include method arguments in the log.
     *
     * @return true if arguments should be logged
     */
    boolean includeArgs() default false;

    /**
     * Whether to include the return value in the log.
     *
     * @return true if return value should be logged
     */
    boolean includeResult() default false;

    /**
     * Custom message format. Supports placeholders:
     * {method}, {args}, {result}, {duration}
     *
     * @return the message format
     */
    String format() default "";

    /**
     * Log levels for the logging aspect.
     */
    enum LogLevel {
        TRACE,
        DEBUG,
        INFO,
        WARN,
        ERROR
    }
}