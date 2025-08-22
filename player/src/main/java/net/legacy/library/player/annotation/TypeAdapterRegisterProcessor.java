package net.legacy.library.player.annotation;

import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;
import net.legacy.library.commons.util.GsonUtil;

/**
 * Processes classes annotated with {@link TypeAdapterRegister} to register
 * custom Gson type adapters dynamically at runtime.
 *
 * <p>This processor is automatically invoked by the annotation processing framework
 * to handle the registration of type adapters specified by the annotation.
 *
 * <p>The above class will be instantiated and registered as a type adapter for
 * {@code Pair.class} using Gson's builder.
 *
 * @author qwq-dev
 * @since 2025-01-05 18:58
 */
@AnnotationProcessor(TypeAdapterRegister.class)
public class TypeAdapterRegisterProcessor implements CustomAnnotationProcessor {

    /**
     * Processes the annotated class by instantiating it and registering it
     * as a Gson type adapter for the specified class type.
     *
     * @param clazz the class annotated with {@link TypeAdapterRegister}
     * @throws Exception if instantiation or registration fails
     */
    @Override
    public void process(Class<?> clazz) throws Exception {
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Class<?> classType = clazz.getAnnotation(TypeAdapterRegister.class).classType();
        GsonUtil.customizeGsonBuilder((gsonBuilder) -> gsonBuilder.registerTypeAdapter(classType, instance));
    }

    /**
     * Handles exceptions that occur during the processing of annotations.
     *
     * @param clazz     the class being processed when the exception occurred
     * @param exception the exception that was thrown
     */
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("Failed to process TypeAdapterRegister annotation", exception);
    }

}