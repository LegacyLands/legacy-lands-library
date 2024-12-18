package me.qwqdev.library.annotation.service;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.AccessLevel;
import lombok.NoArgsConstructor;
import me.qwqdev.library.annotation.utils.AnnotationScanner;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collection;
import java.util.Set;

/**
 * Service implementation for processing annotations using custom annotation processors.
 *
 * @author NaerQAQ
 * @version 1.0
 * @since 2024 /1/7
 */
@InjectableComponent
@NoArgsConstructor(access = AccessLevel.PRIVATE)
public class AnnotationProcessingServiceImpl implements AnnotationProcessingService {
    /**
     * {@inheritDoc}
     *
     * @param basePackage {@inheritDoc}
     * @param urls        {@inheritDoc}
     */
    @Override
    public void processAnnotations(String basePackage, Collection<URL> urls) {
        Set<Class<?>> annotatedClasses = AnnotationScanner.findAnnotatedClasses(basePackage, urls, AnnotationProcessor.class);

        System.out.println("a");
        System.out.println((long) annotatedClasses.size());

        for (Class<?> annotatedClass : annotatedClasses) {
            System.out.println(annotatedClass.getName());
        }

        annotatedClasses
                .stream()
                .filter(CustomAnnotationProcessor.class::isAssignableFrom)
                .forEach(handlerClass -> processAnnotations(basePackage, urls, handlerClass.asSubclass(CustomAnnotationProcessor.class)));
    }

    /**
     * {@inheritDoc}
     *
     * @param basePackage  {@inheritDoc}
     * @param urls         {@inheritDoc}
     * @param handlerClass {@inheritDoc}
     */
    @Override
    public void processAnnotations(String basePackage, Collection<URL> urls, Class<? extends CustomAnnotationProcessor> handlerClass) {
        CustomAnnotationProcessor customAnnotationProcessor;

        try {
            customAnnotationProcessor = handlerClass.getDeclaredConstructor().newInstance();
        } catch (InvocationTargetException exception) {
            Log.error("An invocation target exception occurred while processing annotations", exception);
            return;
        } catch (InstantiationException exception) {
            Log.error("An instance of the annotation processor could not be created", exception);
            return;
        } catch (IllegalAccessException exception) {
            Log.error("An illegal access exception occurred while processing annotations", exception);
            return;
        } catch (NoSuchMethodException exception) {
            Log.error("The default constructor of the annotation processor could not be found", exception);
            return;
        }

        AnnotationProcessor annotationProcessingService = handlerClass.getAnnotation(AnnotationProcessor.class);

        if (annotationProcessingService == null) {
            Log.error("AnnotationProcessor annotation not found on handlerClass: " + handlerClass.getName());
            return;
        }

        Class<? extends Annotation> annotationClazz = annotationProcessingService.value();

        if (annotationClazz == null) {
            Log.error("Annotation class is null");
            return;
        }

        for (Class<?> aClass : AnnotationScanner.findAnnotatedClasses(basePackage, urls, annotationClazz)) {
            try {
                customAnnotationProcessor.after(annotationClazz);
                customAnnotationProcessor.process(aClass);
                customAnnotationProcessor.before(annotationClazz);
            } catch (Exception exception) {
                customAnnotationProcessor.exception(aClass, exception);
            } finally {
                customAnnotationProcessor.finallyAfter(annotationClazz);
            }
        }
    }
}