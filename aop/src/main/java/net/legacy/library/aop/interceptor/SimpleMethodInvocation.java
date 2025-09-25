package net.legacy.library.aop.interceptor;

/**
 * Simple implementation of MethodInvocation that captures arguments.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
public class SimpleMethodInvocation implements MethodInvocation {

    private final MethodInvocation delegate;
    private final Object[] arguments;

    public SimpleMethodInvocation(MethodInvocation delegate, Object[] arguments) {
        this.delegate = delegate;
        this.arguments = arguments != null ? arguments.clone() : new Object[0];
    }

    @Override
    public Object proceed() throws Throwable {
        return delegate.proceed();
    }

    @Override
    public Object[] getArguments() {
        return arguments.clone();
    }

}