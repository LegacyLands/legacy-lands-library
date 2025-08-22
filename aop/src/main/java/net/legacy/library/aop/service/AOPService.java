package net.legacy.library.aop.service;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.aspect.AsyncSafeAspect;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.model.MethodMetrics;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.registry.InterceptorRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Core service implementation for managing AOP (Aspect-Oriented Programming) functionality with ClassLoader isolation.
 *
 * <p>This service serves as the central orchestrator for all AOP operations, providing comprehensive
 * proxy creation capabilities with interceptor management, lifecycle control, and resource cleanup.
 * The service is designed to operate in multi-ClassLoader environments, ensuring proper isolation
 * between different plugin contexts in Minecraft server environments.
 *
 * <p>The service integrates with the annotation processing system to automatically discover and
 * register interceptors, while maintaining thread-safe operations through concurrent data structures.
 * It supports both global interceptors that apply to all proxies and class-specific interceptors
 * for targeted aspect application.
 *
 * @author qwq-dev
 * @version 1.0
 * @see AspectProxyFactory
 * @see ClassLoaderIsolationService
 * @see InterceptorRegistry
 * @see MethodInterceptor
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@RequiredArgsConstructor
public class AOPService {

    private final AspectProxyFactory proxyFactory;
    private final ClassLoaderIsolationService isolationService;

    private final Map<Class<?>, List<MethodInterceptor>> globalInterceptors = new ConcurrentHashMap<>();

    /**
     * Creates a dynamic proxy for the specified target object with comprehensive AOP capabilities.
     *
     * <p>This method automatically discovers and applies all relevant interceptors for the target
     * object's class, including both global interceptors and class-specific interceptors. The
     * proxy creation process includes ClassLoader isolation and graceful fallback mechanisms.
     *
     * <p>If no interceptors are applicable to the target object, the original object is returned
     * unchanged to maintain optimal performance. In case of proxy creation failure, the method
     * falls back to returning the original object while logging the error for debugging purposes.
     *
     * @param target the target object to be proxied, must not be {@code null}
     * @param <T>    the type of the target object
     * @return the proxied object with AOP capabilities, or the original object if no interceptors apply or proxy creation fails
     * @throws IllegalArgumentException if the target object is {@code null}
     * @see AspectProxyFactory#createProxy(Object, List)
     */
    public <T> T createProxy(T target) {
        if (target == null) {
            throw new IllegalArgumentException("Target object cannot be null");
        }

        List<MethodInterceptor> interceptors = getInterceptorsForClass(target.getClass());

        if (interceptors.isEmpty()) {
            // No interceptors, return original object
            return target;
        }

        try {
            return proxyFactory.createProxy(target, interceptors);
        } catch (Exception exception) {
            Log.error("Failed to create proxy for %s: %s", target.getClass().getName(), exception.getMessage());
            return target;
        }
    }

    /**
     * Creates a dynamic proxy with both custom and automatically discovered interceptors.
     *
     * <p>This method combines the provided custom interceptors with all applicable global and
     * class-specific interceptors discovered through the annotation processing system. The
     * resulting proxy will execute all interceptors in their defined order priority.
     *
     * <p>Custom interceptors are added first, followed by global and class-specific interceptors,
     * ensuring that explicitly provided aspects have precedence while still benefiting from
     * automatic aspect discovery and registration.
     *
     * @param target       the target object to be proxied, must not be {@code null}
     * @param interceptors additional custom interceptors to apply, must not be {@code null}
     * @param <T>          the type of the target object
     * @return the proxied object with combined AOP capabilities
     * @throws IllegalArgumentException if target or interceptors are {@code null}
     * @see #createProxy(Object)
     */
    public <T> T createProxy(T target, List<MethodInterceptor> interceptors) {
        if (target == null || interceptors == null) {
            throw new IllegalArgumentException("Target and interceptors cannot be null");
        }

        List<MethodInterceptor> allInterceptors = new ArrayList<>(interceptors);
        allInterceptors.addAll(getInterceptorsForClass(target.getClass()));

        return proxyFactory.createProxy(target, allInterceptors);
    }

    /**
     * Registers a global interceptor that applies to all proxies.
     *
     * @param interceptor the interceptor to register
     */
    public void registerGlobalInterceptor(MethodInterceptor interceptor) {
        List<MethodInterceptor> interceptors = globalInterceptors.computeIfAbsent(Object.class, k -> new ArrayList<>());
        // Avoid duplicate registration
        if (!interceptors.contains(interceptor)) {
            interceptors.add(interceptor);
        }
    }

    /**
     * Registers an interceptor for a specific class.
     *
     * @param targetClass the target class
     * @param interceptor the interceptor to register
     */
    public void registerInterceptor(Class<?> targetClass, MethodInterceptor interceptor) {
        proxyFactory.registerInterceptor(targetClass, interceptor);
    }

    /**
     * Gets the monitoring metrics for a specific operation.
     *
     * @param operationName the operation name
     * @return the metrics, or {@code null} if not found
     */
    public MethodMetrics getMonitoringMetrics(String operationName) {
        MonitoringAspect monitoringAspect = InterceptorRegistry.getMonitoringAspect();
        if (monitoringAspect == null) {
            Log.warn("MonitoringAspect not found in registry!");
            return null;
        }
        return monitoringAspect.getMetrics(operationName);
    }

    /**
     * Clears all monitoring metrics.
     */
    public void clearMonitoringMetrics() {
        MonitoringAspect monitoringAspect = InterceptorRegistry.getMonitoringAspect();
        if (monitoringAspect != null) {
            monitoringAspect.clearMetrics();
        }
    }

    /**
     * Cleans up resources for a specific ClassLoader.
     *
     * @param classLoader the ClassLoader to clean up
     */
    public void cleanupClassLoader(ClassLoader classLoader) {
        isolationService.cleanup(classLoader);
        Log.info("Cleaned up AOP resources for ClassLoader: %s", classLoader);
    }

    /**
     * Shuts down the AOP service and releases resources.
     */
    public void shutdown() {
        // Shutdown AsyncSafeAspect if available
        AsyncSafeAspect asyncSafeAspect = InterceptorRegistry.getAsyncSafeAspect();
        if (asyncSafeAspect != null) {
            asyncSafeAspect.shutdown();
        }

        globalInterceptors.clear();
        clearMonitoringMetrics();
        InterceptorRegistry.clear();
    }

    private List<MethodInterceptor> getInterceptorsForClass(Class<?> targetClass) {
        // Add global interceptors
        List<MethodInterceptor> result = new ArrayList<>(globalInterceptors.getOrDefault(Object.class, new ArrayList<>()));

        // Add class-specific interceptors (but avoid duplicates)
        List<MethodInterceptor> classSpecific = proxyFactory.getInterceptors(targetClass);
        for (MethodInterceptor interceptor : classSpecific) {
            if (!result.contains(interceptor)) {
                result.add(interceptor);
            }
        }

        return result;
    }

}