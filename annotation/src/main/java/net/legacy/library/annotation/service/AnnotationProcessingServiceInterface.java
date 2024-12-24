package net.legacy.library.annotation.service;

import java.net.URL;
import java.util.Collection;
import java.util.List;

/**
 * Service interface for processing annotations using custom annotation processors.
 *
 * <p>This interface defines methods for processing annotations within classes in the specified base package or
 * from a collection of URLs. It allows for processing using default or custom annotation processors.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2024-12-19 17:00
 */
public interface AnnotationProcessingServiceInterface {
    /**
     * Processes annotations within the specified base package using the default annotation processors.
     *
     * <p>This method scans the specified base package and its sub-packages for classes annotated with specific
     * annotations, and then processes them using default annotation processors.
     *
     * @param basePackage           the base package to scan for annotated classes
     * @param fromFairyIoCSingleton whether handlerClass should be injected into a singleton by the Fairy framework
     *                              If false, it will be created by reflection without parameters,
     *                              but it still supports setter injection with Fairy {@link io.fairyproject.container.Autowired} annotation
     * @param classLoader           optional class loaders to use for classpath scanning; if not provided, the default class loader is used
     */
    void processAnnotations(String basePackage, boolean fromFairyIoCSingleton, ClassLoader... classLoader);

    /**
     * Processes annotations within the specified list of base packages using the default annotation processors.
     *
     * <p>This method scans the specified base packages and their sub-packages for classes annotated with specific
     * annotations, and then processes them using default annotation processors. The method supports scanning
     * multiple base packages and allows for optional class loaders to be specified for classpath scanning.
     *
     * @param basePackages          the list of base packages to scan for annotated classes
     * @param fromFairyIoCSingleton whether handlerClass should be injected into a singleton by the Fairy framework
     *                              If false, it will be created by reflection without parameters,
     *                              but it still supports setter injection with Fairy {@link io.fairyproject.container.Autowired} annotation
     * @param classLoader           optional class loaders to use for classpath scanning; if not provided, the default class loader is used
     */
    void processAnnotations(List<String> basePackages, boolean fromFairyIoCSingleton, ClassLoader... classLoader);

    /**
     * Processes annotations within the specified collection of URLs using default annotation processors.
     *
     * <p>This method scans the classes located at the provided URLs for annotations and processes them using
     * default annotation processors. The URLs can represent locations like JAR files, directories, or classpath entries.
     *
     * @param urls                  the collection of URLs to scan for annotated classes
     * @param fromFairyIoCSingleton whether handlerClass should be injected into a singleton by the Fairy framework
     *                              If false, it will be created by reflection without parameters,
     *                              but it still supports setter injection with Fairy {@link io.fairyproject.container.Autowired} annotation
     */
    void processAnnotations(Collection<URL> urls, boolean fromFairyIoCSingleton);

    /**
     * Processes annotations within the specified collection of URLs using a specific custom annotation processor.
     *
     * <p>This method allows for processing annotations in the classes located at the specified URLs using a custom
     * annotation processor, which allows for more fine-grained control over the processing logic.
     *
     * @param urls                  the collection of URLs to scan for annotated classes
     * @param handlerClass          the class of the custom annotation processor to handle annotation processing
     * @param fromFairyIoCSingleton whether handlerClass should be injected into a singleton by the Fairy framework
     *                              If false, it will be created by reflection without parameters,
     *                              but it still supports setter injection with Fairy {@link io.fairyproject.container.Autowired} annotation
     */
    void processAnnotations(Collection<URL> urls, Class<? extends CustomAnnotationProcessor> handlerClass, boolean fromFairyIoCSingleton);
}
