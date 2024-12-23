package me.qwqdev.library.commons.injector.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;
import java.lang.invoke.VarHandle;

/**
 * Annotation to mark a field for automatic {@link VarHandle} injection.
 *
 * @author qwq-dev
 * @since 2024-12-23 16:15
 */
@Target(ElementType.FIELD)
@Retention(RetentionPolicy.RUNTIME)
public @interface VarHandleAutoInjection {
    /**
     * The name of the target field for which the {@link VarHandle} is being generated.
     *
     * @return the field name
     */
    String fieldName();

    /**
     * The name of the static method used to generate the {@link VarHandle}.
     *
     * <p>The specified static method must contain two parameters: {@link String} and {@link Class}
     *
     * @return the static method name, or an empty string if not specified
     */
    String staticMethodName() default "";

    /**
     * The fully qualified name of the class containing the static method used to generate the {@link VarHandle}.
     *
     * <p>The class must be loadable by the current thread's context {@link ClassLoader}.
     *
     * @return the fully qualified class name, or an empty string if not specified
     */
    String staticMethodPackage() default "";
}
