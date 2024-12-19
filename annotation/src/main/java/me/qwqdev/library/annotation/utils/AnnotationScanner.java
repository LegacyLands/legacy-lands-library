package me.qwqdev.library.annotation.utils;

import lombok.experimental.UtilityClass;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Collection;
import java.util.Set;

/**
 * Utility class for scanning and retrieving classes annotated with a specified annotation.
 *
 * @author NaerQAQ
 * @version 1.0
 * @since 2024/1/7
 */
@UtilityClass
public class AnnotationScanner {
    /**
     * Find annotated classes set.
     *
     * @param annotationClass the annotation class
     * @return the set
     */
    public static Set<Class<?>> findAnnotatedClasses(Collection<URL> urls, Class<? extends Annotation> annotationClass) {
        return new Reflections(new ConfigurationBuilder().setUrls(urls)).getTypesAnnotatedWith(annotationClass);
    }
}