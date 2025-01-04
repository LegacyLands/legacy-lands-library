package net.legacy.library.annotation.util;

import com.google.common.collect.Sets;
import lombok.experimental.UtilityClass;
import org.reflections.util.ClasspathHelper;

import java.net.URL;
import java.util.Collection;
import java.util.List;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:35
 */
@UtilityClass
public class ReflectUtil {
    public static Collection<URL> resolveUrlsForPackages(List<String> basePackages, List<ClassLoader> classLoaders) {
        Collection<URL> urls = Sets.newHashSet();
        basePackages.forEach(basePackage -> urls.addAll(ClasspathHelper.forPackage(basePackage, classLoaders.toArray(new ClassLoader[0]))));
        return urls;
    }

    public static Collection<URL> resolveUrlsForPackages(List<String> basePackages, ClassLoader... classLoader) {
        return resolveUrlsForPackages(basePackages, List.of(classLoader));
    }
}
