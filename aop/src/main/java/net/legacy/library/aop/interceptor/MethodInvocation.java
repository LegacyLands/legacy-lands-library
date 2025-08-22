package net.legacy.library.aop.interceptor;

/**
 * Functional interface representing a method invocation in the interceptor chain.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
@FunctionalInterface
public interface MethodInvocation {

    /**
     * Continues the execution of the interceptor chain or invokes the target method.
     *
     * @return the result of the method invocation from the next interceptor or target method
     * @throws Throwable if the invocation fails at any point in the chain
     */
    Object proceed() throws Throwable;

}