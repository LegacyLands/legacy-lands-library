package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.Monitored;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.model.MethodMetrics;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect for monitoring method performance.
 *
 * <p>This aspect intercepts methods annotated with {@link Monitored} and
 * tracks their execution time, invocation count, and other metrics.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 100)
public class MonitoringAspect implements MethodInterceptor {
    private final Map<String, MethodMetrics> metricsMap = new ConcurrentHashMap<>();

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
        Monitored monitored = method.getAnnotation(Monitored.class);

        String metricName = monitored.name();
        MethodMetrics metrics = metricsMap.computeIfAbsent(metricName, k -> new MethodMetrics());

        long startTime = System.currentTimeMillis();

        try {
            return invocation.proceed();
        } catch (Throwable throwable) {
            metrics.incrementFailures();
            throw throwable;
        } finally {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordExecution(duration);

            if (duration > monitored.warnThreshold()) {
                logSlowExecution(context, monitored, duration);
            }
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
        return method.isAnnotationPresent(Monitored.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 100;
    }

    private void logSlowExecution(AspectContext context, Monitored monitored, long duration) {
        String methodName = context.getMethod().getName();
        String className = context.getTarget().getClass().getSimpleName();

        if (monitored.includeArgs() && context.getArguments().length > 0) {
            Log.warn("Slow execution detected: %s.%s(%s) took %dms (threshold: %dms)",
                    className, methodName, Arrays.toString(context.getArguments()),
                    duration, monitored.warnThreshold());
        } else {
            Log.warn("Slow execution detected: %s.%s took %dms (threshold: %dms)",
                    className, methodName, duration, monitored.warnThreshold());
        }
    }

    /**
     * Gets the metrics for a specific monitored operation.
     *
     * @param name the operation name
     * @return the metrics, or {@code null} if not found
     */
    public MethodMetrics getMetrics(String name) {
        return metricsMap.get(name);
    }

    /**
     * Clears all collected metrics.
     */
    public void clearMetrics() {
        metricsMap.clear();
    }

}