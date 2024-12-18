package me.qwqdev.library.annotation.service;

import java.lang.annotation.Annotation;
import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Meta-annotation used to mark custom annotation processors.
 *
 * <p>This annotation specifies the type of annotation that the processor handles.
 *
 * @author NaerQAQ
 * @version 1.0
 * @since 2024/1/7
 */
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface AnnotationProcessor {
    /**
     * The type of annotation that the processor handles.
     *
     * @return The class of the handled annotation
     */
    Class<? extends Annotation> value();
}