package net.legacy.library.aop.pointcut;

import java.lang.reflect.Method;

/**
 * Represents a pointcut that defines where aspects should be applied.
 *
 * <p>A pointcut is a predicate that matches join points (method executions in this implementation).
 * It provides the foundation for flexible aspect application beyond simple annotation matching.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:05
 */
public interface Pointcut {
    /**
     * Tests whether this pointcut matches the given method on the target class.
     *
     * <p>This method is called to determine if an aspect should be applied to a specific
     * method invocation. Implementations should be efficient as this may be called
     * frequently during proxy creation and method invocation.
     *
     * @param method the method to test
     * @param targetClass the class declaring the method (may be different from method.getDeclaringClass() due to inheritance)
     * @return {@code true} if the pointcut matches, {@code false} otherwise
     */
    boolean matches(Method method, Class<?> targetClass);
    
    /**
     * Tests whether this pointcut matches any method in the given class.
     *
     * <p>This is a coarse-grained filter that can quickly eliminate classes that
     * definitely won't have any matching methods, improving performance.
     *
     * @param targetClass the class to test
     * @return {@code true} if the pointcut might match methods in this class, {@code false} if it definitely won't
     */
    boolean matchesClass(Class<?> targetClass);
}