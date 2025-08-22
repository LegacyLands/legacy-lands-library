package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Defines a pointcut expression for aspect application in the AOP framework.
 *
 * <p>This annotation enables flexible method matching through pointcut expressions,
 * extending beyond the current annotation-based approach. It supports multiple
 * pointcut types including execution, within, and annotation-based patterns.
 *
 * <p>Example usage:
 * <pre>{@code
 * @AOPPointcut("execution(* net.legacy.library.service.*.*(..))")
 * @Monitored
 * public class ServiceMonitoring {}
 * }</pre>
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:00
 */
@Target({ElementType.TYPE, ElementType.METHOD})
@Retention(RetentionPolicy.RUNTIME)
public @interface AOPPointcut {

    /**
     * The pointcut expression defining where the aspect should be applied.
     *
     * <p>Supports the following expression types:
     * <ul>
     *   <li>{@code execution(...)}: Method execution matching</li>
     *   <li>{@code within(...)}: Type-based matching</li>
     *   <li>{@code @annotation(...)}: Annotation-based matching</li>
     * </ul>
     *
     * @return the pointcut expression
     */
    String value();

}