package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.Logged;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.util.Arrays;

/**
 * Aspect for automatic method logging.
 *
 * <p>This aspect intercepts methods annotated with {@link Logged} and
 * logs their entry, exit, arguments, and return values based on configuration.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 300)
public class LoggingAspect implements MethodInterceptor {
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
        Logged logged = method.getAnnotation(Logged.class);
        String methodSignature = buildMethodSignature(context);

        // Log method entry
        logEntry(logged, methodSignature, context);

        Object result = null;
        Throwable thrown = null;
        long startTime = System.currentTimeMillis();

        try {
            result = invocation.proceed();
            return result;
        } catch (Throwable throwable) {
            thrown = throwable;
            throw throwable;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            logExit(logged, methodSignature, context, result, thrown, duration);
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
        return method.isAnnotationPresent(Logged.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 200; // Lower priority than monitoring
    }

    private void logEntry(Logged logged, String methodSignature, AspectContext context) {
        if (!logged.format().isEmpty()) {
            // Use custom format with placeholders
            String format = logged.format()
                    .replace("{method}", "%s")
                    .replace("{args}", "%s")
                    .replace("{result}", "%s")
                    .replace("{duration}", "%d");
            logMessage(logged.level(), format, methodSignature, formatArguments(context.getArguments()), "N/A", 0L);
        } else if (logged.includeArgs() && context.getArguments().length > 0) {
            logMessage(logged.level(), "Entering %s with args: %s", methodSignature, formatArguments(context.getArguments()));
        } else {
            logMessage(logged.level(), "Entering %s", methodSignature);
        }
    }

    private void logExit(Logged logged, String methodSignature, AspectContext context,
                         Object result, Throwable thrown, long duration) {
        Logged.LogLevel logLevel = (thrown != null && logged.level() != Logged.LogLevel.ERROR)
                ? Logged.LogLevel.ERROR : logged.level();

        if (!logged.format().isEmpty()) {
            // Use custom format with placeholders
            String format = logged.format()
                    .replace("{method}", "%s")
                    .replace("{args}", "%s")
                    .replace("{result}", "%s")
                    .replace("{duration}", "%d");
            logMessage(logLevel, format, methodSignature, formatArguments(context.getArguments()),
                    formatResult(result), duration);
        } else if (thrown != null) {
            logMessage(logLevel, "Exiting %s with exception after %dms: %s",
                    methodSignature, duration, thrown.getClass().getSimpleName());
        } else if (logged.includeResult() && result != null) {
            logMessage(logLevel, "Exiting %s after %dms with result: %s",
                    methodSignature, duration, formatResult(result));
        } else {
            logMessage(logLevel, "Exiting %s after %dms", methodSignature, duration);
        }
    }

    private String buildMethodSignature(AspectContext context) {
        Method method = context.getMethod();
        String className = context.getTarget().getClass().getSimpleName();
        return className + "." + method.getName();
    }

    private String formatArguments(Object[] args) {
        return (args == null || args.length == 0) ? "[]" : Arrays.toString(args);
    }

    private String formatResult(Object result) {
        if (result == null) {
            return "null";
        }
        String string = result.toString();
        return string.length() > 100 ? string.substring(0, 97) + "..." : string;
    }


    private void logMessage(Logged.LogLevel level, String format, Object... args) {
        switch (level) {
            case TRACE -> Log.debug("[TRACE] " + format, args);
            case DEBUG -> Log.debug(format, args);
            case INFO -> Log.info(format, args);
            case WARN -> Log.warn(format, args);
            case ERROR -> Log.error(format, args);
        }
    }
}