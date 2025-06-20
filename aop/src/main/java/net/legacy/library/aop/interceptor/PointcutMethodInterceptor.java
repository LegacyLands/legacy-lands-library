package net.legacy.library.aop.interceptor;

import net.legacy.library.aop.annotation.AOPPointcut;
import net.legacy.library.aop.pointcut.Pointcut;
import net.legacy.library.aop.pointcut.PointcutExpressionParser;

import java.lang.reflect.Method;

/**
 * Abstract base class for method interceptors that use pointcut expressions.
 *
 * <p>This class provides pointcut expression support for interceptors, allowing
 * them to define where they should be applied using flexible expressions rather
 * than just annotations.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:35
 */
public abstract class PointcutMethodInterceptor implements MethodInterceptor {
    private final Pointcut pointcut;
    
    /**
     * Creates a new pointcut-based interceptor with the given expression.
     *
     * @param pointcutExpression the pointcut expression
     */
    protected PointcutMethodInterceptor(String pointcutExpression) {
        PointcutExpressionParser parser = new PointcutExpressionParser();
        this.pointcut = parser.parse(pointcutExpression);
    }
    
    /**
     * Creates a new pointcut-based interceptor using the @AOPPointcut annotation.
     *
     * @param interceptorClass the interceptor class to check for @AOPPointcut
     */
    protected PointcutMethodInterceptor(Class<?> interceptorClass) {
        AOPPointcut pointcutAnnotation = interceptorClass.getAnnotation(AOPPointcut.class);
        if (pointcutAnnotation == null) {
            throw new IllegalArgumentException("Interceptor class must have @AOPPointcut annotation");
        }
        
        PointcutExpressionParser parser = new PointcutExpressionParser();
        this.pointcut = parser.parse(pointcutAnnotation.value());
    }
    
    /**
     * Creates a new pointcut-based interceptor with the given pointcut.
     *
     * @param pointcut the pointcut to use
     */
    protected PointcutMethodInterceptor(Pointcut pointcut) {
        this.pointcut = pointcut;
    }
    
    /**
     * {@inheritDoc}
     *
     * <p>This implementation delegates to the pointcut's matches method to
     * determine if this interceptor should be applied.
     */
    @Override
    public boolean supports(Method method) {
        return pointcut.matches(method, method.getDeclaringClass());
    }
    
    /**
     * Gets the pointcut used by this interceptor.
     *
     * @return the pointcut
     */
    protected Pointcut getPointcut() {
        return pointcut;
    }
}