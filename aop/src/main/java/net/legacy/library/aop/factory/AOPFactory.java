package net.legacy.library.aop.factory;

import io.fairyproject.container.Containers;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.service.AOPService;

import java.util.List;

/**
 * Factory for creating AOP-enhanced objects.
 *
 * <p>This factory provides convenient methods to create objects with
 * AOP capabilities, integrating with the Fairy dependency injection.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@RequiredArgsConstructor
public class AOPFactory {

    private final AOPService aopService;

    /**
     * Creates an instance of the specified class with AOP capabilities.
     *
     * @param clazz the class to instantiate
     * @param <T>   the type of the object
     * @return the AOP-enhanced instance
     */
    public <T> T create(Class<T> clazz) {
        try {
            // Create instance using reflection or Fairy's container
            T instance = clazz.getDeclaredConstructor().newInstance();

            // Apply AOP
            return aopService.createProxy(instance);
        } catch (Exception exception) {
            Log.error("Failed to create AOP-enhanced instance of %s: %s",
                    clazz.getName(), exception.getMessage());
            throw new RuntimeException("Failed to create AOP instance", exception);
        }
    }

    /**
     * Creates an instance with custom interceptors.
     *
     * @param clazz        the class to instantiate
     * @param interceptors additional interceptors to apply
     * @param <T>          the type of the object
     * @return the AOP-enhanced instance
     */
    public <T> T create(Class<T> clazz, List<MethodInterceptor> interceptors) {
        try {
            // Create instance using reflection or Fairy's container
            T instance = clazz.getDeclaredConstructor().newInstance();

            // Apply AOP with custom interceptors
            return aopService.createProxy(instance, interceptors);
        } catch (Exception exception) {
            Log.error("Failed to create AOP-enhanced instance of %s with custom interceptors: %s",
                    clazz.getName(), exception.getMessage());
            throw new RuntimeException("Failed to create AOP instance", exception);
        }
    }

    /**
     * Enhances an existing object with AOP capabilities.
     *
     * @param instance the object instance
     * @param <T>      the type of the object
     * @return the AOP-enhanced instance
     */
    public <T> T enhance(T instance) {
        if (instance == null) {
            throw new IllegalArgumentException("Instance cannot be null");
        }

        return aopService.createProxy(instance);
    }

    /**
     * Enhances an existing object with custom interceptors.
     *
     * @param instance     the object instance
     * @param interceptors additional interceptors to apply
     * @param <T>          the type of the object
     * @return the AOP-enhanced instance
     */
    public <T> T enhance(T instance, List<MethodInterceptor> interceptors) {
        if (instance == null || interceptors == null) {
            throw new IllegalArgumentException("Instance and interceptors cannot be null");
        }

        return aopService.createProxy(instance, interceptors);
    }

    /**
     * Creates a singleton instance with AOP capabilities.
     * The instance is cached and the same instance is returned on subsequent calls.
     *
     * @param clazz the class to instantiate
     * @param <T>   the type of the object
     * @return the AOP-enhanced singleton instance
     */
    public <T> T createSingleton(Class<T> clazz) {
        // Try to get from Fairy's container first
        T instance = Containers.get(clazz);
        return instance == null ? create(clazz) : instance;
    }

}