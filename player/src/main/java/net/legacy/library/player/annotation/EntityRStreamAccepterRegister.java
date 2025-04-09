package net.legacy.library.player.annotation;

import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for registering entity Redis stream accepters.
 *
 * <p>Classes annotated with {@code EntityRStreamAccepterRegister} will be automatically
 * discovered and registered as entity Redis stream accepters. Implementations using
 * this annotation must implement the {@link EntityRStreamAccepterInterface}.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityRStreamAccepterRegister {
    /**
     * The name of the task that this accepter handles.
     * If not specified, the accepter will handle all tasks.
     *
     * @return the task name, or an empty string to handle all tasks
     */
    String taskName() default "";
} 