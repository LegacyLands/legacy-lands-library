package net.legacy.library.aop.registry;

import net.legacy.library.aop.aspect.AsyncSafeAspect;
import net.legacy.library.aop.aspect.CircuitBreakerAspect;
import net.legacy.library.aop.aspect.DistributedTransactionAspect;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.aspect.RetryAspect;
import net.legacy.library.aop.aspect.SecurityAspect;
import net.legacy.library.aop.aspect.TracingAspect;
import net.legacy.library.aop.aspect.ValidationAspect;
import net.legacy.library.aop.interceptor.MethodInterceptor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing interceptor instances.
 *
 * <p>This registry provides a centralized location to store and retrieve
 * interceptor instances, particularly those with special methods that
 * need to be accessed directly (e.g., monitoring metrics, shutdown).
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 12:00
 */
public class InterceptorRegistry {

    private static final Map<Class<? extends MethodInterceptor>, MethodInterceptor> registry = new ConcurrentHashMap<>();

    /**
     * Registers an interceptor instance.
     *
     * @param interceptor the interceptor to register
     */
    public static void register(MethodInterceptor interceptor) {
        registry.put(interceptor.getClass(), interceptor);
    }

    /**
     * Gets an interceptor instance by its class.
     *
     * @param interceptorClass the interceptor class
     * @param <T>              the interceptor type
     * @return the interceptor instance, or {@code null} if not found
     */
    @SuppressWarnings("unchecked")
    public static <T extends MethodInterceptor> T get(Class<T> interceptorClass) {
        return (T) registry.get(interceptorClass);
    }

    /**
     * Gets the MonitoringAspect instance.
     *
     * @return the MonitoringAspect, or {@code null} if not registered
     */
    public static MonitoringAspect getMonitoringAspect() {
        return get(MonitoringAspect.class);
    }

    /**
     * Gets the AsyncSafeAspect instance.
     *
     * @return the AsyncSafeAspect, or {@code null} if not registered
     */
    public static AsyncSafeAspect getAsyncSafeAspect() {
        return get(AsyncSafeAspect.class);
    }

    /**
     * Gets the DistributedTransactionAspect instance.
     *
     * @return the DistributedTransactionAspect, or {@code null} if not registered
     */
    public static DistributedTransactionAspect getDistributedTransactionAspect() {
        return get(DistributedTransactionAspect.class);
    }

    /**
     * Gets the SecurityAspect instance.
     *
     * @return the SecurityAspect, or {@code null} if not registered
     */
    public static SecurityAspect getSecurityAspect() {
        return get(SecurityAspect.class);
    }

    /**
     * Gets the CircuitBreakerAspect instance.
     *
     * @return the CircuitBreakerAspect, or {@code null} if not registered
     */
    public static CircuitBreakerAspect getCircuitBreakerAspect() {
        return get(CircuitBreakerAspect.class);
    }

    /**
     * Gets the RetryAspect instance.
     *
     * @return the RetryAspect, or {@code null} if not registered
     */
    public static RetryAspect getRetryAspect() {
        return get(RetryAspect.class);
    }

    /**
     * Gets the ValidationAspect instance.
     *
     * @return the ValidationAspect, or {@code null} if not registered
     */
    public static ValidationAspect getValidationAspect() {
        return get(ValidationAspect.class);
    }

    /**
     * Gets the TracingAspect instance.
     *
     * @return the TracingAspect, or {@code null} if not registered
     */
    public static TracingAspect getTracingAspect() {
        return get(TracingAspect.class);
    }

    /**
     * Clears the registry.
     */
    public static void clear() {
        registry.clear();
    }

}