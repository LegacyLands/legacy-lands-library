package net.legacy.library.aop.model;

import lombok.Getter;
import lombok.RequiredArgsConstructor;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Represents the execution context for an aspect.
 *
 * <p>This class holds information about the method being intercepted,
 * including the target object, method arguments, and any contextual data.
 * It ensures ClassLoader isolation by maintaining separate contexts per ClassLoader.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@Getter
@RequiredArgsConstructor
public class AspectContext {
    private final Object target;
    private final Method method;
    private final Object[] arguments;
    private final ClassLoader classLoader;
    private final Map<String, Object> attributes = new ConcurrentHashMap<>();

    /**
     * Creates a new context for the given method invocation.
     *
     * @param target    the target object
     * @param method    the method being invoked
     * @param arguments the method arguments
     * @return a new AspectContext
     */
    public static AspectContext create(Object target, Method method, Object[] arguments) {
        ClassLoader loader = target != null ? target.getClass().getClassLoader() :
                method.getDeclaringClass().getClassLoader();
        return new AspectContext(target, method, arguments, loader);
    }

    /**
     * Sets a context attribute.
     *
     * @param key   the attribute key
     * @param value the attribute value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Gets a context attribute.
     *
     * @param key the attribute key
     * @return the attribute value, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Checks if this context belongs to the specified ClassLoader.
     *
     * @param loader the ClassLoader to check
     * @return true if this context belongs to the specified ClassLoader
     */
    public boolean belongsTo(ClassLoader loader) {
        return Objects.equals(classLoader, loader);
    }
}