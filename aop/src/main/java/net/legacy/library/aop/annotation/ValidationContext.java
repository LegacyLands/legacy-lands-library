package net.legacy.library.aop.annotation;

import lombok.Getter;

import java.lang.reflect.Parameter;

/**
 * Context object containing validation information.
 *
 * <p>This class encapsulates all validation-related information including
 * the parameter being validated, method context, and validation rules.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class ValidationContext {

    private final Parameter parameter;
    private final Object value;
    private final String methodName;
    private final ValidInput annotation;

    public ValidationContext(Parameter parameter, Object value, String methodName, ValidInput annotation) {
        this.parameter = parameter;
        this.value = value;
        this.methodName = methodName;
        this.annotation = annotation;
    }

    public String getParameterName() {
        return parameter.getName();
    }

    public Class<?> getParameterType() {
        return parameter.getType();
    }

}
