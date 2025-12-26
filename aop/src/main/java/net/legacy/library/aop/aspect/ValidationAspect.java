package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.InputValidator;
import net.legacy.library.aop.annotation.ValidInput;
import net.legacy.library.aop.annotation.ValidationContext;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect for validating input parameters.
 *
 * <p>This aspect intercepts method calls and validates parameters annotated
 * with {@link ValidInput}. It supports various validation rules including
 * required fields, pattern matching, range validation, and custom validators.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 90)
public class ValidationAspect implements MethodInterceptor {

    private final Map<Class<?>, InputValidator> validatorCache = new ConcurrentHashMap<>();

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        Parameter[] parameters = method.getParameters();
        Object[] arguments = invocation.getArguments();

        // Validate parameters
        for (int i = 0; i < parameters.length; i++) {
            Parameter parameter = parameters[i];
            ValidInput validInput = parameter.getAnnotation(ValidInput.class);

            if (validInput != null) {
                validateParameter(parameter, arguments[i], context.getMethod().getName(), validInput);
            }
        }

        // Execute method
        return invocation.proceed();
    }

    @Override
    public boolean supports(Method method) {
        for (Parameter parameter : method.getParameters()) {
            if (parameter.isAnnotationPresent(ValidInput.class)) {
                return true;
            }
        }
        return false;
    }

    @Override
    public int getOrder() {
        return 90; // High priority, before security checks
    }

    /**
     * Validates a single parameter.
     *
     * @param parameter  the parameter to validate
     * @param value      the parameter value
     * @param methodName the method name
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateParameter(Parameter parameter, Object value, String methodName, ValidInput validInput)
            throws Throwable {
        ValidationContext context = new ValidationContext(parameter, value, methodName, validInput);

        // Check required
        if (validInput.required() && value == null) {
            throw createValidationFailure(validInput, "Parameter '" + parameter.getName() + "' is required");
        }

        // Skip further validation if value is null and not required
        if (value == null) {
            return;
        }

        // Validate based on parameter type
        Class<?> type = parameter.getType();

        if (type == String.class) {
            validateString((String) value, validInput);
        } else if (type == Integer.class || type == int.class) {
            validateInteger((Integer) value, validInput);
        } else if (type == Long.class || type == long.class) {
            validateLong((Long) value, validInput);
        } else if (type == Double.class || type == double.class) {
            validateDouble((Double) value, validInput);
        } else if (type == Float.class || type == float.class) {
            validateFloat((Float) value, validInput);
        }

        // Apply custom validator
        if (validInput.validator() != InputValidator.class) {
            validateWithCustomValidator(value, context);
        }

    }

    /**
     * Validates a string parameter.
     *
     * @param value      the string value
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateString(String value, ValidInput validInput) throws Throwable {
        // Check length
        if (value.length() < validInput.minLength()) {
            throw createValidationFailure(validInput, String.format(
                    "String length must be at least %d characters", validInput.minLength()));
        }

        if (value.length() > validInput.maxLength()) {
            throw createValidationFailure(validInput, String.format(
                    "String length must not exceed %d characters", validInput.maxLength()));
        }

        // Check pattern
        if (!validInput.pattern().isEmpty() && !value.matches(validInput.pattern())) {
            throw createValidationFailure(validInput, String.format(
                    "String must match pattern: %s", validInput.pattern()));
        }
    }

    /**
     * Validates an integer parameter.
     *
     * @param value      the integer value
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateInteger(Integer value, ValidInput validInput) throws Throwable {
        if (value < validInput.min()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must be at least %.0f", validInput.min()));
        }

        if (value > validInput.max()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must not exceed %.0f", validInput.max()));
        }
    }

    /**
     * Validates a long parameter.
     *
     * @param value      the long value
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateLong(Long value, ValidInput validInput) throws Throwable {
        if (value < (long) validInput.min()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must be at least %.0f", validInput.min()));
        }

        if (value > (long) validInput.max()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must not exceed %.0f", validInput.max()));
        }
    }

    /**
     * Validates a double parameter.
     *
     * @param value      the double value
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateDouble(Double value, ValidInput validInput) throws Throwable {
        if (value < validInput.min()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must be at least %f", validInput.min()));
        }

        if (value > validInput.max()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must not exceed %f", validInput.max()));
        }
    }

    /**
     * Validates a float parameter.
     *
     * @param value      the float value
     * @param validInput the validation annotation
     * @throws Throwable if validation fails
     */
    private void validateFloat(Float value, ValidInput validInput) throws Throwable {
        if (value < (float) validInput.min()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must be at least %f", validInput.min()));
        }

        if (value > (float) validInput.max()) {
            throw createValidationFailure(validInput, String.format(
                    "Value must not exceed %f", validInput.max()));
        }
    }

    /**
     * Validates using a custom validator.
     *
     * @param value   the value to validate
     * @param context the validation context
     * @throws Throwable if validation fails
     */
    private void validateWithCustomValidator(Object value, ValidationContext context) throws Throwable {
        @SuppressWarnings("unchecked")
        InputValidator validator = validatorCache.computeIfAbsent(
                context.getAnnotation().validator(),
                validatorClass -> createValidatorInstance((Class<? extends InputValidator>) validatorClass)
        );

        if (!validator.isValid(value, context)) {
            String errorMessage = validator.getErrorMessage(value, context);
            throw createValidationFailure(context.getAnnotation(), errorMessage);
        }
    }

    /**
     * Creates a validator instance.
     *
     * @param validatorClass the validator class
     * @return the validator instance
     */
    private InputValidator createValidatorInstance(Class<? extends InputValidator> validatorClass) {
        try {
            return validatorClass.getDeclaredConstructor().newInstance();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to create validator instance: " + validatorClass.getName(), exception);
        }
    }

    /**
     * Creates a validation failure exception.
     *
     * @param validInput the validation annotation
     * @param message    the error message
     * @return the validation failure exception
     */
    private Throwable createValidationFailure(ValidInput validInput, String message) {
        Class<? extends Throwable> exceptionClass = validInput.onValidationFailure();

        // For IllegalArgumentException, create directly for reliability
        if (exceptionClass == IllegalArgumentException.class) {
            return new IllegalArgumentException(message);
        }

        // For other exception types, try reflection with fallback
        try {
            return exceptionClass
                    .getConstructor(String.class)
                    .newInstance(message);
        } catch (Exception exception) {
            Log.warn("Failed to create validation exception %s, using IllegalArgumentException: %s",
                    exceptionClass.getSimpleName(), exception.getMessage());
            return new IllegalArgumentException(message);
        }
    }

}
