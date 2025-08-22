package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Marks a method for performance monitoring.
 *
 * <p>Methods annotated with {@code @Monitored} will have their execution time tracked
 * and can trigger warnings if they exceed the specified threshold.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Monitored {

    /**
     * The name of the monitored operation.
     *
     * @return the operation name
     */
    String name();

    /**
     * The threshold in milliseconds that triggers a warning.
     *
     * @return the warning threshold in milliseconds
     */
    long warnThreshold() default 1000L;

    /**
     * Whether to include method arguments in the monitoring data.
     *
     * @return true if arguments should be included
     */
    boolean includeArgs() default false;

}