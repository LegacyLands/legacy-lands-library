package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for validating input parameters.
 *
 * <p>This annotation provides input validation capabilities for method parameters.
 * It supports various validation rules including required fields, pattern matching,
 * range validation, and custom validators.
 *
 * <p>Usage examples:
 * <pre>
 * {@code
 * @ValidInput(
 *     required = true,
 *     minLength = 3,
 *     maxLength = 50,
 *     pattern = "[a-zA-Z0-9]+"
 * )
 * public void createUser(@ValidInput String username) {
 *     // Method implementation
 * }
 *
 * @ValidInput(
 *     min = 18,
 *     max = 120,
 *     message = "Age must be between 18 and 120"
 * )
 * public void setAge(@ValidInput int age) {
 *     // Method implementation
 * }
 * }
 * </pre>
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.PARAMETER)
@Retention(RetentionPolicy.RUNTIME)
public @interface ValidInput {

    /**
     * Whether the parameter is required.
     *
     * @return true if the parameter is required
     */
    boolean required() default false;

    /**
     * Minimum length for string parameters.
     *
     * @return minimum length
     */
    int minLength() default 0;

    /**
     * Maximum length for string parameters.
     *
     * @return maximum length
     */
    int maxLength() default Integer.MAX_VALUE;

    /**
     * Pattern to match for string parameters.
     *
     * @return regex pattern
     */
    String pattern() default "";

    /**
     * Minimum value for numeric parameters.
     *
     * @return minimum value
     */
    double min() default Double.MIN_VALUE;

    /**
     * Maximum value for numeric parameters.
     *
     * @return maximum value
     */
    double max() default Double.MAX_VALUE;

    /**
     * Custom validator class.
     *
     * @return validator class
     */
    Class<? extends InputValidator> validator() default InputValidator.class;

    /**
     * Error message to use when validation fails.
     *
     * @return error message
     */
    String message() default "Invalid input value";

    /**
     * Exception to throw when validation fails.
     *
     * @return validation exception class
     */
    Class<? extends Throwable> onValidationFailure() default IllegalArgumentException.class;

}