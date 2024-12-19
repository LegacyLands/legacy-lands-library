package me.qwqdev.library.annotation.service;

import java.net.URL;
import java.util.Collection;

/**
 * Service interface for processing annotations using custom annotation processors.
 *
 * @author NaerQAQ
 * @version 1.0
 * @since 2024 /1/7
 */
public interface AnnotationProcessingService {
    /**
     * Processes annotations within the specified base package using default annotation processors.
     *
     * @param urls        The collection of URLs to scan for annotated classes
     */
    void processAnnotations(Collection<URL> urls);

    /**
     * Processes annotations within the specified base package using a specific annotation processor.
     *
     * @param basePackage  The base package to scan for annotated classes
     * @param urls         The collection of URLs to scan for annotated classes
     * @param handlerClass The class of the custom annotation processor
     */
    void processAnnotations(Collection<URL> urls, Class<? extends CustomAnnotationProcessor> handlerClass);
}
