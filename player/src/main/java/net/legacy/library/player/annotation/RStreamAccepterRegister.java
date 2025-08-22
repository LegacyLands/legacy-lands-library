package net.legacy.library.player.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to mark a class as an RStream Accepter Registration.
 *
 * <p>Classes annotated with {@code @RStreamAccepterRegister} are recognized by the
 * annotation processing system and registered to handle specific Redis stream actions.
 *
 * <p>This annotation should be applied to classes that implement the
 * {@link net.legacy.library.player.task.redis.RStreamAccepterInterface}.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:19
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface RStreamAccepterRegister {

}
