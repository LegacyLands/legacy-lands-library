package net.legacy.library.player.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as an Entity RStream Accepter Registration.
 *
 * <p>Classes annotated with {@code @EntityRStreamAccepterRegister} are recognized by the
 * annotation processing system and registered to handle specific Redis stream actions.
 *
 * <p>This annotation should be applied to classes that implement the
 * {@link net.legacy.library.player.task.redis.EntityRStreamAccepterInterface}.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface EntityRStreamAccepterRegister {
} 