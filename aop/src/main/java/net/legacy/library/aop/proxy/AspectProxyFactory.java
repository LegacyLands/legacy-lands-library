package net.legacy.library.aop.proxy;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.service.ClassLoaderIsolationService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Factory for creating proxy instances with AOP capabilities.
 *
 * <p>This factory creates dynamic proxies that intercept method calls
 * and apply registered aspects while maintaining ClassLoader isolation.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@RequiredArgsConstructor
public class AspectProxyFactory {

    private final ClassLoaderIsolationService isolationService;
    private final Map<Class<?>, List<MethodInterceptor>> interceptorCache = new ConcurrentHashMap<>();

    /**
     * Creates a proxy instance for the given target object.
     *
     * @param target       the target object to proxy
     * @param interfaces   the interfaces to implement
     * @param interceptors the method interceptors to apply
     * @param <T>          the type of the proxy
     * @return the proxy instance
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(T target, Class<?>[] interfaces, List<MethodInterceptor> interceptors) {
        ClassLoader classLoader = target.getClass().getClassLoader();

        // Sort interceptors by order
        List<MethodInterceptor> sortedInterceptors = new ArrayList<>(interceptors);
        sortedInterceptors.sort(Comparator.comparingInt(MethodInterceptor::getOrder));

        InvocationHandler handler = new AspectInvocationHandler(target, sortedInterceptors, isolationService);

        return (T) Proxy.newProxyInstance(classLoader, interfaces, handler);
    }

    /**
     * Creates a proxy instance for the given target with automatic interface detection.
     *
     * @param target       the target object to proxy
     * @param interceptors the method interceptors to apply
     * @param <T>          the type of the proxy
     * @return the proxy instance
     */
    public <T> T createProxy(T target, List<MethodInterceptor> interceptors) {
        Class<?>[] interfaces = target.getClass().getInterfaces();
        if (interfaces.length == 0) {
            throw new IllegalArgumentException(
                    "Target object must implement at least one interface for proxy creation"
            );
        }
        return createProxy(target, interfaces, interceptors);
    }

    /**
     * Registers an interceptor for a specific class.
     *
     * @param targetClass the target class
     * @param interceptor the interceptor to register
     */
    public void registerInterceptor(Class<?> targetClass, MethodInterceptor interceptor) {
        interceptorCache.computeIfAbsent(targetClass, k -> new ArrayList<>()).add(interceptor);
        Log.info("Registered interceptor %s for class %s",
                interceptor.getClass().getSimpleName(), targetClass.getName());
    }

    /**
     * Gets all registered interceptors for a class.
     *
     * @param targetClass the target class
     * @return list of interceptors
     */
    public List<MethodInterceptor> getInterceptors(Class<?> targetClass) {
        return interceptorCache.getOrDefault(targetClass, new ArrayList<>());
    }

}