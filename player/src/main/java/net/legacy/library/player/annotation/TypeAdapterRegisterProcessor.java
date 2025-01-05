package net.legacy.library.player.annotation;

import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;
import net.legacy.library.commons.util.GsonUtil;

/**
 * @author qwq-dev
 * @since 2025-01-05 18:58
 */
@AnnotationProcessor(TypeAdapterRegister.class)
public class TypeAdapterRegisterProcessor implements CustomAnnotationProcessor {
    @Override
    public void process(Class<?> clazz) throws Exception {
        Object instance = clazz.getDeclaredConstructor().newInstance();
        Class<?> classType = clazz.getAnnotation(TypeAdapterRegister.class).classType();
        GsonUtil.customizeGsonBuilder((gsonBuilder) -> gsonBuilder.registerTypeAdapter(classType, instance));
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("Failed to process TypeAdapterRegister annotation", exception);
    }
}
