package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * The {@link AOPInterceptor} is a custom annotation that marks a class as an AOP interceptor
 * for automatic registration within the AOP system.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 12:00
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AOPInterceptor {

    /**
     * Determines whether this interceptor should be registered as a global interceptor.
     * Global interceptors are applied to all AOP-enabled methods.
     *
     * @return true if this is a global interceptor, false otherwise
     */
    boolean global() default false;

    /**
     * The order in which this interceptor should be executed relative to other interceptors.
     * Lower values indicate higher priority (executed earlier).
     *
     * @return the execution order, default is 0
     */
    int order() default 0;

    /**
     * Determines whether the annotated interceptor is created by Fairy IoC.
     * If false, the interceptor must have a no-argument constructor.
     *
     * @return true if created by Fairy IoC, false otherwise
     */
    boolean isFromFairyIoC() default true;

}