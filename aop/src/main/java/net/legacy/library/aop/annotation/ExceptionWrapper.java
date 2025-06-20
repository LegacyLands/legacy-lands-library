package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Wraps method exceptions with a custom exception type.
 *
 * <p>Methods annotated with {@code @ExceptionWrapper} will have their exceptions
 * caught and wrapped with the specified exception type, providing consistent
 * error handling across the application.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface ExceptionWrapper {
    /**
     * The exception class to wrap thrown exceptions with.
     * The class must have a constructor that accepts (String message, Throwable cause).
     *
     * @return the wrapper exception class
     */
    Class<? extends Throwable> wrapWith();

    /**
     * Custom error message. Supports placeholders:
     * {method}, {args}, {original}
     *
     * @return the error message
     */
    String message() default "Method execution failed";

    /**
     * Whether to log the original exception before wrapping.
     *
     * @return true if the original exception should be logged
     */
    boolean logOriginal() default true;

    /**
     * Exception types that should not be wrapped.
     *
     * @return array of exception classes to exclude from wrapping
     */
    Class<? extends Throwable>[] exclude() default {};
}