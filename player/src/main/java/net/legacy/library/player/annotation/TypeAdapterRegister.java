package net.legacy.library.player.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation to register a custom Gson type adapter for a specific class type.
 *
 * <p>Classes annotated with {@code @TypeAdapterRegister} must implement both
 * {@link com.google.gson.JsonSerializer} and {@link com.google.gson.JsonDeserializer}
 * for the specified {@code classType}.
 *
 * <p>This annotation is processed at runtime by the
 * {@link net.legacy.library.player.annotation.TypeAdapterRegisterProcessor}
 * to automatically register the type adapter with the Gson builder.
 *
 * @author qwq-dev
 * @since 2025-01-05 18:57
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface TypeAdapterRegister {

    /**
     * Specifies the class type for which the type adapter is being registered.
     *
     * @return the class type to register the adapter for
     */
    Class<?> classType();

}