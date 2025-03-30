package net.legacy.library.annotation.util;

import lombok.AllArgsConstructor;
import lombok.Getter;
import org.reflections.Reflections;
import org.reflections.util.ConfigurationBuilder;

import java.net.URL;
import java.util.List;
import java.util.Set;
import java.util.function.Predicate;

/**
 * Utility for scanning classes based on custom predicates.
 *
 * <p>This class provides methods to scan classes from specified packages using
 * custom filter predicates, supporting multiple class loaders.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Getter
@AllArgsConstructor(staticName = "of")
public class ClassScanner {
    /**
     * List of base packages to scan.
     */
    private final List<String> basePackages;
    
    /**
     * Predicate to filter class loaders.
     */
    private final Predicate<ClassLoader> classLoaderFilter;
    
    /**
     * Predicate to filter classes.
     */
    private final Predicate<Class<?>> classFilter;
    
    /**
     * List of class loaders to use for scanning.
     */
    private final List<ClassLoader> classLoaders;
    
    /**
     * Scans for classes that match the specified predicates.
     *
     * @return a set of classes that match the specified predicates
     */
    public Set<Class<?>> scan() {
        // Resolve URLs for the base packages
        java.util.Collection<URL> urls = ReflectUtil.resolveUrlsForPackages(basePackages, classLoaders);
        
        // Configure and create the Reflections instance
        ConfigurationBuilder configBuilder = new ConfigurationBuilder()
                .setUrls(urls)
                .setClassLoaders(classLoaders.toArray(new ClassLoader[0]));
        
        Reflections reflections = new Reflections(configBuilder);
        
        // Get all classes from the scanner
        Set<Class<?>> allClasses = reflections.getSubTypesOf(Object.class);
        
        // Filter classes based on the predicates
        return allClasses.stream()
                .filter(clazz -> classLoaderFilter.test(clazz.getClassLoader()))
                .filter(classFilter)
                .collect(java.util.stream.Collectors.toSet());
    }
} 