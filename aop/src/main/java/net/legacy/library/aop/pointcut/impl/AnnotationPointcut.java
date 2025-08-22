package net.legacy.library.aop.pointcut.impl;

import io.fairyproject.log.Log;
import net.legacy.library.aop.pointcut.Pointcut;

import java.lang.annotation.Annotation;
import java.lang.reflect.Method;

/**
 * Pointcut implementation that matches methods with specific annotations.
 *
 * <p>This pointcut type provides compatibility with the existing annotation-based
 * approach while integrating it into the new pointcut expression system. It supports
 * lazy loading of annotation classes to handle cases where the annotation might not
 * be available at initialization time.
 *
 * @author qwq-dev
 * @version 1.0
 * @see Pointcut
 * @see java.lang.annotation.Annotation
 * @since 2025-06-20 19:25
 */
public class AnnotationPointcut implements Pointcut {

    private final String annotationClassName;
    private Class<? extends Annotation> annotationClass;

    @SuppressWarnings("unchecked")
    public AnnotationPointcut(String annotationClassName) {
        this.annotationClassName = annotationClassName;

        try {
            // Try to load the annotation class
            Class<?> clazz = Class.forName(annotationClassName);
            if (clazz.isAnnotation()) {
                this.annotationClass = (Class<? extends Annotation>) clazz;
            } else {
                throw new IllegalArgumentException("Class " + annotationClassName + " is not an annotation");
            }
        } catch (ClassNotFoundException exception) {
            Log.warn("Annotation class not found: %s. Will use lazy loading.", annotationClassName);
            // Will try to load it later when needed
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation checks if the method has the specified annotation.
     * If the annotation class hasn't been loaded yet, it attempts lazy loading
     * using the method's class loader.
     *
     * @param method {@inheritDoc}
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matches(Method method, Class<?> targetClass) {
        if (annotationClass == null) {
            // Try to load the annotation class lazily
            tryLoadAnnotationClass(method.getDeclaringClass().getClassLoader());
            if (annotationClass == null) {
                return false;
            }
        }

        return method.isAnnotationPresent(annotationClass);
    }

    /**
     * {@inheritDoc}
     *
     * <p>This implementation always returns true because we cannot determine
     * if any method has the annotation without checking all methods.
     *
     * @param targetClass {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean matchesClass(Class<?> targetClass) {
        return true;
    }

    @SuppressWarnings("unchecked")
    private void tryLoadAnnotationClass(ClassLoader classLoader) {
        try {
            Class<?> clazz = Class.forName(annotationClassName, true, classLoader);
            if (clazz.isAnnotation()) {
                this.annotationClass = (Class<? extends Annotation>) clazz;
            }
        } catch (ClassNotFoundException exception) {
            // Still not found
        }
    }

}