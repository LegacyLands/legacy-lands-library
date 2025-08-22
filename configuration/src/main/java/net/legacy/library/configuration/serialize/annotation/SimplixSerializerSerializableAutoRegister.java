package net.legacy.library.configuration.serialize.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * @author qwq-dev
 * @since 2024-12-13 12:47
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimplixSerializerSerializableAutoRegister {

}
