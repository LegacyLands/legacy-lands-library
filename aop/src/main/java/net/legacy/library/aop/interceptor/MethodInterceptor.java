package net.legacy.library.aop.interceptor;

import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;

/**
 * Core interface for method interceptors in the AOP framework, defining the contract for aspect implementation.
 *
 * <p>Method interceptors form the foundation of the AOP system, enabling the implementation of cross-cutting
 * concerns such as performance monitoring, security enforcement, logging, caching, and transaction management.
 * Each interceptor operates within a chain of responsibility pattern, allowing multiple aspects to be composed
 * and applied to the same method invocation.
 *
 * <p>Implementations must be thread-safe as they may be invoked concurrently from multiple threads.
 * The interceptor lifecycle is managed by the AOP framework, with automatic discovery and registration
 * through the {@code @AOPInterceptor} annotation mechanism.
 *
 * <p>Interceptors are executed in order of priority, determined by the {@link #getOrder()} method.
 * Lower numerical values indicate higher priority and earlier execution in the interceptor chain.
 * The framework guarantees that interceptors with the same priority maintain their registration order.
 *
 * @author qwq-dev
 * @version 1.0
 * @see AspectContext
 * @see net.legacy.library.aop.annotation.AOPInterceptor
 * @since 2025-06-19 17:41
 */
public interface MethodInterceptor {

    /**
     * Intercepts a method invocation and applies the aspect's cross-cutting logic.
     *
     * <p>This method is called when a proxied method is invoked and this interceptor
     * is applicable to the target method. Implementations should perform their
     * aspect-specific logic (before/after/around advice) and delegate to the
     * provided invocation to continue the interceptor chain.
     *
     * <p>The method may modify the invocation parameters, return value, or handle
     * exceptions as appropriate for the aspect's functionality. Care must be taken
     * to maintain the method's contract and handle exceptions appropriately.
     *
     * @param context    the aspect context containing method metadata, target object, and execution environment
     * @param invocation the method invocation callback to proceed with the interceptor chain
     * @return the result of the method invocation, potentially modified by the aspect
     * @throws Throwable if the invocation fails or the aspect encounters an error
     * @see AspectContext
     * @see MethodInvocation#proceed()
     */
    Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable;

    /**
     * Determines whether this interceptor should be applied to the specified method.
     *
     * <p>This method is called during proxy creation to determine which interceptors
     * are applicable to each method of the target object. Implementations typically
     * check for the presence of specific annotations, method signatures, or other
     * criteria that indicate the aspect should be applied.
     *
     * <p>The method should be efficient as it may be called frequently during
     * proxy initialization. Consider caching results if expensive computations
     * are required for the decision logic.
     *
     * @param method the method to evaluate for interceptor applicability
     * @return {@code true} if this interceptor should be applied to the method, {@code false} otherwise
     * @see java.lang.reflect.Method
     */
    boolean supports(Method method);

    /**
     * Defines the execution order priority of this interceptor within the aspect chain.
     *
     * <p>Interceptors are executed in ascending order of their priority values, with
     * lower numerical values indicating higher priority and earlier execution. This
     * allows fine-grained control over aspect composition and ensures that critical
     * aspects (such as security or transaction management) can be positioned
     * appropriately in the execution chain.
     *
     * <p>The default implementation returns {@link Integer#MAX_VALUE}, placing
     * interceptors at the end of the execution chain unless explicitly overridden.
     * Common priority ranges include:
     * <ul>
     *   <li>1-100: High priority aspects (security, authentication)</li>
     *   <li>100-500: Business logic aspects (monitoring, caching)</li>
     *   <li>500+: Utility aspects (logging, debugging)</li>
     * </ul>
     *
     * @return the execution order priority, where lower values indicate higher priority
     */
    default int getOrder() {
        return Integer.MAX_VALUE;
    }

}