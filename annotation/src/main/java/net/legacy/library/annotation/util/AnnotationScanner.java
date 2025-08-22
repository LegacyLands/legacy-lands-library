package net.legacy.library.annotation.util;

import lombok.experimental.UtilityClass;
import org.reflections.Reflections;
import org.reflections.util.ClasspathHelper;
import org.reflections.util.ConfigurationBuilder;

import java.lang.annotation.Annotation;
import java.net.URL;
import java.util.Collection;
import java.util.Set;

/**
 * Utility class for scanning and retrieving classes annotated with a specified annotation.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2024-12-19 17:00
 */
@UtilityClass
public class AnnotationScanner {

    /**
     * Finds a set of classes annotated with the specified annotation within the given package and its sub-packages.
     *
     * <p>This method scans the classes in the provided base package and its sub-packages for the specified annotation.
     * It supports optional class loaders to scan classes loaded by non-default class loaders.
     *
     * @param basePackage     the base package to scan for annotated classes
     * @param annotationClass the annotation class to look for
     * @param classLoaders    optional class loaders to use for classpath scanning; if not provided, the default class loader is used
     * @return a set of classes annotated with the specified annotation
     */
    public static Set<Class<?>> findAnnotatedClasses(String basePackage, Class<? extends Annotation> annotationClass, ClassLoader... classLoaders) {
        return findAnnotatedClasses(ClasspathHelper.forPackage(basePackage, classLoaders), annotationClass);
    }

    /**
     * Finds a set of classes annotated with the specified annotation from the provided URLs.
     *
     * <p>This method allows scanning classes from specific URLs rather than scanning a package or using class loaders.
     * The provided collection of URLs will be used to locate and scan the classes for the specified annotation.
     *
     * @param urls            the collection of URLs to scan for annotated classes
     * @param annotationClass the annotation class to look for
     * @return a set of classes annotated with the specified annotation
     */
    public static Set<Class<?>> findAnnotatedClasses(Collection<URL> urls, Class<? extends Annotation> annotationClass) {
        return new Reflections(new ConfigurationBuilder().setUrls(urls)).getTypesAnnotatedWith(annotationClass);
    }

}