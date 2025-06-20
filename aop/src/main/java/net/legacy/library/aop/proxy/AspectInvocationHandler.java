package net.legacy.library.aop.proxy;

import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.service.ClassLoaderIsolationService;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.util.List;

/**
 * InvocationHandler that applies method interceptors.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@RequiredArgsConstructor
public class AspectInvocationHandler implements InvocationHandler {
    private final Object target;
    private final List<MethodInterceptor> interceptors;
    private final ClassLoaderIsolationService isolationService;

    @Override
    public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
        // Handle Object methods
        if (method.getDeclaringClass() == Object.class) {
            return method.invoke(target, args);
        }

        // Find the actual method on the target class (which has the annotations)
        Method targetMethod = findTargetMethod(method);

        // Fallback to proxy method if target method not found
        targetMethod = targetMethod == null ? method : targetMethod;

        // Make targetMethod effectively final for lambda
        final Method effectiveTargetMethod = targetMethod;

        // Create aspect context with the target method
        AspectContext context = AspectContext.create(target, effectiveTargetMethod, args);

        // Find applicable interceptors
        List<MethodInterceptor> applicableInterceptors = interceptors.stream()
                .filter(interceptor -> interceptor.supports(effectiveTargetMethod))
                .filter(interceptor -> shouldApplyInterceptor(context, interceptor))
                .toList();

        // Create invocation chain with the actual method
        MethodInvocation invocation = createInvocationChain(
                target, effectiveTargetMethod, args, applicableInterceptors, 0
        );

        // Execute the chain
        return invocation.proceed();
    }

    private Method findTargetMethod(Method proxyMethod) {
        try {
            // Try to find the method in the target class with the same signature
            return target.getClass().getMethod(proxyMethod.getName(), proxyMethod.getParameterTypes());
        } catch (NoSuchMethodException noSuchMethodException) {
            // If not found as public method, try declared methods (including protected/private)
            try {
                Method method = target.getClass().getDeclaredMethod(proxyMethod.getName(), proxyMethod.getParameterTypes());
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException innerNoSuchMethodException) {
                Log.error("Could not find target method for proxy method: %s", proxyMethod.getName());
                return null;
            }
        }
    }

    private boolean shouldApplyInterceptor(AspectContext context, MethodInterceptor interceptor) {
        ClassLoader interceptorLoader = interceptor.getClass().getClassLoader();
        return isolationService.shouldApplyAspect(context, interceptorLoader);
    }

    private MethodInvocation createInvocationChain(
            Object target, Method method, Object[] args,
            List<MethodInterceptor> interceptors, int index) {

        if (index >= interceptors.size()) {
            // End of chain - invoke the actual method
            return () -> {
                try {
                    return method.invoke(target, args);
                } catch (InvocationTargetException invocationTargetException) {
                    // Unwrap the actual exception
                    throw invocationTargetException.getTargetException();
                }
            };
        }

        // Create chain recursively
        MethodInterceptor interceptor = interceptors.get(index);
        return () -> {
            AspectContext context = AspectContext.create(target, method, args);
            MethodInvocation next = createInvocationChain(
                    target, method, args, interceptors, index + 1
            );
            return interceptor.intercept(context, next);
        };
    }
}