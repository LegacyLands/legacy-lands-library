package net.legacy.library.annotation.util;

import com.google.common.collect.Sets;
import lombok.experimental.UtilityClass;
import org.reflections.util.ClasspathHelper;

import java.net.URL;
import java.util.Collection;
import java.util.List;

/**
 * Utility class for reflection-related operations.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:35
 */
@UtilityClass
public class ReflectUtil {

    /**
     * Resolves URLs for the given base packages and class loaders.
     * This effectively gets all URLs on the classpath that are within the specified packages.
     *
     * @param basePackages The list of base packages to resolve URLs for.  These should be fully qualified package names (e.g., "com.example.myapp")
     * @param classLoaders The list of class loaders to use for resolving URLs. This is important in environments with multiple class loaders
     *                     (like plugin systems), where you may need to search for resources in multiple places
     * @return A collection of URLs representing the resources found within the specified
     * packages and class loaders.  The collection uses a Set, so duplicate URLs are avoided
     */
    public static Collection<URL> resolveUrlsForPackages(List<String> basePackages, List<ClassLoader> classLoaders) {
        Collection<URL> urls = Sets.newHashSet();
        basePackages.forEach(basePackage -> urls.addAll(ClasspathHelper.forPackage(basePackage, classLoaders.toArray(new ClassLoader[0]))));
        return urls;
    }

    /**
     * Resolves URLs for the given base packages and class loaders.
     * This is a convenience method that allows passing class loaders as a varargs array instead of a List.
     *
     * @param basePackages The list of base packages to resolve URLs for
     * @param classLoader  The class loaders to use for resolving URLs
     * @return A collection of URLs representing the resources found within the specified packages and class loaders
     */
    public static Collection<URL> resolveUrlsForPackages(List<String> basePackages, ClassLoader... classLoader) {
        return resolveUrlsForPackages(basePackages, List.of(classLoader));
    }

}
