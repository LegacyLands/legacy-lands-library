package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.ExceptionWrapper;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect for wrapping exceptions with custom exception types.
 *
 * <p>This aspect intercepts methods annotated with {@link ExceptionWrapper} and
 * wraps thrown exceptions with the specified exception type.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 400)
public class ExceptionWrapperAspect implements MethodInterceptor {
    private final Map<Class<? extends Throwable>, Constructor<? extends Throwable>> constructorCache =
            new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * @param context    {@inheritDoc}
     * @param invocation {@inheritDoc}
     * @return {@inheritDoc}
     * @throws Throwable {@inheritDoc}
     */
    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        ExceptionWrapper wrapper = method.getAnnotation(ExceptionWrapper.class);

        try {
            return invocation.proceed();
        } catch (Throwable throwable) {
            // Check if this exception should be excluded from wrapping
            if (shouldExclude(throwable, wrapper.exclude())) {
                throw throwable;
            }

            // Log original exception if configured
            if (wrapper.logOriginal()) {
                logOriginalException(context, throwable);
            }

            // Wrap and throw
            throw wrapException(context, throwable, wrapper);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(ExceptionWrapper.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 300;
    }

    private boolean shouldExclude(Throwable throwable, Class<? extends Throwable>[] excludeList) {
        if (excludeList == null || excludeList.length == 0) {
            return false;
        }

        Class<? extends Throwable> exceptionClass = throwable.getClass();
        for (Class<? extends Throwable> excludeClass : excludeList) {
            if (excludeClass.isAssignableFrom(exceptionClass)) {
                return true;
            }
        }
        return false;
    }

    private void logOriginalException(AspectContext context, Throwable throwable) {
        String methodName = context.getMethod().getName();
        String className = context.getTarget().getClass().getSimpleName();
        Log.error("Original exception in %s.%s: %s", className, methodName, throwable.getMessage(), throwable);
    }

    private Throwable wrapException(AspectContext context, Throwable original, ExceptionWrapper wrapper) throws Throwable {
        Class<? extends Throwable> wrapperClass = wrapper.wrapWith();

        // Check if the exception is already of the wrapper type
        if (wrapperClass.isInstance(original)) {
            return original;
        }

        // Get or cache the constructor
        Constructor<? extends Throwable> constructor = getWrapperConstructor(wrapperClass);

        // Format the message
        String message = formatMessage(wrapper.message(), context, original);

        try {
            // Try to create wrapper with message and cause
            Throwable wrappedException = constructor.newInstance(message, original);

            // If the method declares this exception type, return it
            Method method = context.getMethod();
            for (Class<?> exceptionType : method.getExceptionTypes()) {
                if (exceptionType.isAssignableFrom(wrapperClass)) {
                    return wrappedException;
                }
            }

            // If not declared, wrap in RuntimeException to avoid UndeclaredThrowableException
            if (!(wrappedException instanceof RuntimeException)) {
                return new RuntimeException(wrappedException.getMessage(), original);
            }

            return wrappedException;
        } catch (Exception exception) {
            // If wrapper creation fails, log and throw original
            Log.error("Failed to wrap exception with %s: %s", wrapperClass.getSimpleName(), exception.getMessage());
            throw original;
        }
    }

    private Constructor<? extends Throwable> getWrapperConstructor(Class<? extends Throwable> wrapperClass) {
        return constructorCache.computeIfAbsent(wrapperClass, clazz -> {
            try {
                // Look for constructor with (String, Throwable) parameters
                return clazz.getConstructor(String.class, Throwable.class);
            } catch (NoSuchMethodException noSuchMethodException) {
                throw new IllegalArgumentException(
                        "Wrapper exception class " + clazz.getName() +
                                " must have a constructor (String message, Throwable cause)"
                );
            }
        });
    }

    private String formatMessage(String template, AspectContext context, Throwable original) {
        Method method = context.getMethod();
        String methodName = method.getName();
        String message = original.getMessage();
        String className = context.getTarget().getClass().getSimpleName();

        return template
                .replace("{method}", className + "." + methodName)
                .replace("{args}", Arrays.toString(context.getArguments()))
                .replace("{original}", message != null ? message : original.getClass().getSimpleName());
    }
}