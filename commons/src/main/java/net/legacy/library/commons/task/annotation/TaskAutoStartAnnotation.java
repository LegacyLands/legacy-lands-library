package net.legacy.library.commons.task.annotation;

import net.legacy.library.commons.task.TaskInterface;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * <p>The {@link  TaskAutoStartAnnotation} is a custom annotation that marks a class for
 * auto-start behavior within the task scheduling system.
 *
 * <p>When a class annotated with {@link TaskAutoStartAnnotation} is detected,
 * the {@link TaskAutoStartAnnotationProcessor} will automatically call its {@link TaskInterface#start()} method
 * (assuming it implements {@link TaskInterface}).
 *
 * <p>Once the annotation processor detects the above class, it will instantiate
 * and invoke its {@link TaskInterface#start()} method, ensuring tasks are automatically initialized.
 *
 * @author qwq-dev
 * @since 2024-12-24 11:47
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TaskAutoStartAnnotation {

    /**
     * Determines whether the annotated class is created by @code Fairy IoC}.
     *
     * @return is created by @code Fairy IoC} or not
     */
    boolean isFromFairyIoC() default true;

}
