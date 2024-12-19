package me.qwqdev.library.annotation.service;

import java.net.URL;
import java.util.Collection;

/**
 * Service interface for processing annotations using custom annotation processors.
 *
 * <p>
 * This interface defines methods for processing annotations within classes in the specified base package or
 * from a collection of URLs. It allows for processing using default or custom annotation processors.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2024-12-19 17:00
 */
public interface AnnotationProcessingService {
    /**
     * Processes annotations within the specified base package using the default annotation processors.
     *
     * <p>
     * This method scans the specified base package and its sub-packages for classes annotated with specific
     * annotations, and then processes them using default annotation processors.
     *
     * @param basePackage the base package to scan for annotated classes
     * @param classLoader optional class loaders to use for classpath scanning; if not provided, the default class loader is used
     */
    void processAnnotations(String basePackage, ClassLoader... classLoader);

    /**
     * Processes annotations within the specified collection of URLs using default annotation processors.
     *
     * <p>
     * This method scans the classes located at the provided URLs for annotations and processes them using
     * default annotation processors. The URLs can represent locations like JAR files, directories, or classpath entries.
     *
     * @param urls The collection of URLs to scan for annotated classes
     */
    void processAnnotations(Collection<URL> urls);

    /**
     * Processes annotations within the specified collection of URLs using a specific custom annotation processor.
     *
     * <p>
     * This method allows for processing annotations in the classes located at the specified URLs using a custom
     * annotation processor, which allows for more fine-grained control over the processing logic.
     *
     * @param urls         The collection of URLs to scan for annotated classes
     * @param handlerClass The class of the custom annotation processor to handle annotation processing
     */
    void processAnnotations(Collection<URL> urls, Class<? extends CustomAnnotationProcessor> handlerClass);
}
