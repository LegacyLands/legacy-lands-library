package net.legacy.library.annotation.service;

import io.fairyproject.container.Containers;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.util.AnnotationScanner;
import net.legacy.library.annotation.util.ReflectUtil;
import org.reflections.util.ClasspathHelper;

import java.lang.annotation.Annotation;
import java.lang.reflect.InvocationTargetException;
import java.net.URL;
import java.util.Collection;
import java.util.List;
import java.util.Set;

/**
 * Service implementation for processing annotations using custom annotation processors.
 *
 * <p>This class is a singleton class managed by {@code Fairy IoC}.
 * It is best to use dependency injection, but creating it directly is also allowed.
 *
 * <p>This class provides the core functionality for scanning and processing annotations
 * within a specified package or set of URLs. It implements the {@link AnnotationProcessingServiceInterface}
 * interface and uses reflection to dynamically instantiate and execute custom annotation processors.
 *
 * @author qwq-dev
 * @version 1.1
 * @see AnnotationProcessingServiceInterface
 * @see CustomAnnotationProcessor
 * @see AnnotationProcessor
 * @since 2024-12-19 17:00
 */
@InjectableComponent
public class AnnotationProcessingService implements AnnotationProcessingServiceInterface {
    /**
     * {@inheritDoc}
     *
     * @param basePackage           {@inheritDoc}
     * @param classLoader           {@inheritDoc}
     * @param fromFairyIoCSingleton {@inheritDoc}
     */
    @Override
    public void processAnnotations(String basePackage, boolean fromFairyIoCSingleton, ClassLoader... classLoader) {
        processAnnotations(ClasspathHelper.forPackage(basePackage, classLoader), fromFairyIoCSingleton);
    }

    /**
     * {@inheritDoc}
     *
     * @param basePackages          {@inheritDoc}
     * @param fromFairyIoCSingleton {@inheritDoc}
     * @param classLoader           {@inheritDoc}
     */
    @Override
    public void processAnnotations(List<String> basePackages, boolean fromFairyIoCSingleton, ClassLoader... classLoader) {
        processAnnotations(ReflectUtil.resolveUrlsForPackages(basePackages, classLoader), fromFairyIoCSingleton);
    }

    /**
     * {@inheritDoc}
     *
     * @param urls                  {@inheritDoc}
     * @param fromFairyIoCSingleton {@inheritDoc}
     */
    @Override
    public void processAnnotations(Collection<URL> urls, boolean fromFairyIoCSingleton) {
        Set<Class<?>> annotatedClasses = AnnotationScanner.findAnnotatedClasses(urls, AnnotationProcessor.class);

        annotatedClasses.stream()
                .filter(CustomAnnotationProcessor.class::isAssignableFrom)
                .forEach(handlerClass -> processAnnotations(urls, handlerClass.asSubclass(CustomAnnotationProcessor.class), fromFairyIoCSingleton));
    }

    /**
     * {@inheritDoc}
     *
     * @param urls                  {@inheritDoc}
     * @param handlerClass          {@inheritDoc}
     * @param fromFairyIoCSingleton {@inheritDoc}
     */
    @Override
    public void processAnnotations(Collection<URL> urls, Class<? extends CustomAnnotationProcessor> handlerClass, boolean fromFairyIoCSingleton) {
        CustomAnnotationProcessor customAnnotationProcessor;

        if (fromFairyIoCSingleton) {
            try {
                customAnnotationProcessor = Containers.get(handlerClass);
            } catch (Exception exception) {
                Log.error("An exception occurred when Fairy injected the singleton pattern", exception);
                return;
            }
        } else {
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
        }

        AnnotationProcessor annotationProcessingService = handlerClass.getAnnotation(AnnotationProcessor.class);

        if (annotationProcessingService == null) {
            Log.error("AnnotationProcessor annotation not found on handlerClass: %s", handlerClass.getName());
            return;
        }

        Class<? extends Annotation> annotationClazz = annotationProcessingService.value();

        if (annotationClazz == null) {
            Log.error("Annotation class is null");
            return;
        }

        for (Class<?> aClass : AnnotationScanner.findAnnotatedClasses(urls, annotationClazz)) {
            try {
                customAnnotationProcessor.before(aClass);
                customAnnotationProcessor.process(aClass);
                customAnnotationProcessor.after(aClass);
            } catch (Exception exception) {
                customAnnotationProcessor.exception(aClass, exception);
            } catch (Error error) {
                // Handle AssertionError and other Error types
                customAnnotationProcessor.exception(aClass, new RuntimeException("Error occurred: " + error.getMessage(), error));
            } finally {
                customAnnotationProcessor.finallyAfter(aClass);
            }
        }
    }
}