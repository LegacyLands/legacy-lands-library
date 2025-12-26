package net.legacy.library.aop.annotation;

/**
 * Interface for custom input validators.
 *
 * <p>This interface allows users to define custom validation logic
 * for method parameters. Validators can implement complex validation
 * rules beyond simple length, pattern, and range checks.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface InputValidator {

    /**
     * Validates the input value.
     *
     * @param value   the value to validate
     * @param context the validation context
     * @return true if valid, false otherwise
     */
    boolean isValid(Object value, ValidationContext context);

    /**
     * Gets the error message to use when validation fails.
     *
     * @param value   the invalid value
     * @param context the validation context
     * @return error message
     */
    String getErrorMessage(Object value, ValidationContext context);

}