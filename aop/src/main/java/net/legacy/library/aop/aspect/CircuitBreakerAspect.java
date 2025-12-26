package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.CircuitBreaker;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Implements circuit breaker pattern for fault tolerance in distributed systems.
 *
 * <p>This aspect integrates with the {@link net.legacy.library.aop.fault.CircuitBreaker}
 * utility class to provide automatic circuit breaking for methods annotated with
 * {@link CircuitBreaker}. The circuit breaker monitors method invocations and automatically
 * opens when failure thresholds are exceeded, preventing cascading failures.
 *
 * <p>The circuit breaker operates in three states:
 * <ul>
 *   <li><b>CLOSED</b>: Normal operation, all calls are allowed</li>
 *   <li><b>OPEN</b>: Circuit is tripped, calls are blocked and fallback is invoked</li>
 *   <li><b>HALF_OPEN</b>: Testing state, limited calls allowed to probe recovery</li>
 * </ul>
 *
 * <p>Key features:
 * <ul>
 *   <li>Configurable failure rate and count thresholds</li>
 *   <li>Sliding window for failure tracking</li>
 *   <li>Timeout enforcement with automatic failure recording</li>
 *   <li>Fallback method support when circuit is open</li>
 *   <li>Dynamic open duration via supplier method</li>
 *   <li>Metrics exposure for monitoring integration</li>
 * </ul>
 *
 * @author qwq-dev
 * @version 2.0
 * @see CircuitBreaker
 * @see net.legacy.library.aop.fault.CircuitBreaker
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 70)
public class CircuitBreakerAspect implements MethodInterceptor {

    private final ConcurrentHashMap<String, net.legacy.library.aop.fault.CircuitBreaker> circuitBreakers = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Method, Method> fallbackCache = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<Method, Method> openDurationSupplierCache = new ConcurrentHashMap<>();
    private final AtomicLong operationCounter = new AtomicLong(0);

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        CircuitBreaker annotation = method.getAnnotation(CircuitBreaker.class);

        if (annotation == null) {
            return invocation.proceed();
        }

        Object target = context.getTarget();
        Class<?> targetClass = target != null ? target.getClass() : method.getDeclaringClass();
        String circuitKey = buildCircuitKey(annotation, targetClass, method);
        net.legacy.library.aop.fault.CircuitBreaker circuitBreaker = getOrCreateCircuitBreaker(circuitKey, annotation, target);

        long operationId = operationCounter.incrementAndGet();

        if (!circuitBreaker.isCallPermitted()) {
            Log.warn("Circuit breaker %s is OPEN, blocking operation #%d", circuitKey, operationId);
            return handleCircuitOpen(annotation, method, context, circuitKey);
        }

        return executeWithCircuitBreaker(circuitBreaker, annotation, invocation, context, circuitKey, operationId);
    }

    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(CircuitBreaker.class);
    }

    @Override
    public int getOrder() {
        return 70;
    }

    /**
     * Gets the metrics for a specific circuit breaker.
     *
     * @param key the circuit breaker key
     * @return the circuit breaker metrics, or null if not found
     */
    public net.legacy.library.aop.fault.CircuitBreaker.CircuitBreakerMetrics getMetrics(String key) {
        net.legacy.library.aop.fault.CircuitBreaker circuitBreaker = circuitBreakers.get(key);
        return circuitBreaker != null ? circuitBreaker.getMetrics() : null;
    }

    /**
     * Resets a specific circuit breaker.
     *
     * @param key the circuit breaker key
     */
    public void resetCircuitBreaker(String key) {
        net.legacy.library.aop.fault.CircuitBreaker circuitBreaker = circuitBreakers.get(key);
        if (circuitBreaker != null) {
            circuitBreaker.reset();
        }
    }

    /**
     * Clears all circuit breakers and caches.
     */
    public void clearAll() {
        circuitBreakers.clear();
        fallbackCache.clear();
        openDurationSupplierCache.clear();
    }

    private net.legacy.library.aop.fault.CircuitBreaker getOrCreateCircuitBreaker(
            String key, CircuitBreaker annotation, Object target) {

        return circuitBreakers.computeIfAbsent(key, k -> {
            long waitDuration = resolveOpenDuration(annotation, target);

            net.legacy.library.aop.fault.CircuitBreaker.CircuitBreakerConfig config =
                    new net.legacy.library.aop.fault.CircuitBreaker.CircuitBreakerConfig(
                            annotation.failureRateThreshold(),
                            annotation.failureCountThreshold(),
                            annotation.minimumNumberOfCalls(),
                            annotation.slidingWindowSize(),
                            waitDuration,
                            annotation.permittedNumberOfCallsInHalfOpenState(),
                            annotation.timeoutDuration(),
                            annotation.automaticTransitionFromOpenToHalfOpen(),
                            annotation.recordFailurePredicate(),
                            annotation.ignoreExceptions()
                    );

            return new net.legacy.library.aop.fault.CircuitBreaker(key, config);
        });
    }

    private long resolveOpenDuration(CircuitBreaker annotation, Object target) {
        if (annotation.openDurationSupplier().isEmpty() || target == null) {
            return annotation.waitDurationInOpenState();
        }

        try {
            Method supplierMethod = openDurationSupplierCache.computeIfAbsent(
                    target.getClass().getDeclaredMethod(annotation.openDurationSupplier()),
                    method -> {
                        method.setAccessible(true);
                        return method;
                    }
            );

            Object result = supplierMethod.invoke(target);
            if (result instanceof Long) {
                return (Long) result;
            } else if (result instanceof Number) {
                return ((Number) result).longValue();
            }
        } catch (Exception exception) {
            Log.warn("Failed to resolve openDurationSupplier: %s", exception.getMessage());
        }

        return annotation.waitDurationInOpenState();
    }

    private Object executeWithCircuitBreaker(net.legacy.library.aop.fault.CircuitBreaker circuitBreaker,
                                             CircuitBreaker annotation,
                                             MethodInvocation invocation,
                                             AspectContext context,
                                             String circuitKey,
                                             long operationId) throws Throwable {
        long timeoutDuration = annotation.timeoutDuration();

        if (timeoutDuration > 0) {
            return executeWithTimeout(circuitBreaker, invocation, context, annotation, circuitKey, operationId, timeoutDuration);
        }

        return executeDirectly(circuitBreaker, invocation, circuitKey, operationId);
    }

    private Object executeDirectly(net.legacy.library.aop.fault.CircuitBreaker circuitBreaker,
                                   MethodInvocation invocation,
                                   String circuitKey,
                                   long operationId) throws Throwable {
        try {
            Object result = invocation.proceed();
            circuitBreaker.recordSuccess();
            return result;
        } catch (Throwable throwable) {
            circuitBreaker.recordFailure(throwable);
            Log.warn("Circuit breaker %s operation #%d failed: %s", circuitKey, operationId, throwable.getMessage());
            throw throwable;
        }
    }

    private Object executeWithTimeout(net.legacy.library.aop.fault.CircuitBreaker circuitBreaker,
                                      MethodInvocation invocation,
                                      AspectContext context,
                                      CircuitBreaker annotation,
                                      String circuitKey,
                                      long operationId,
                                      long timeoutDuration) throws Throwable {
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return invocation.proceed();
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        });

        try {
            Object result = future.get(timeoutDuration, TimeUnit.MILLISECONDS);
            circuitBreaker.recordSuccess();
            return result;
        } catch (TimeoutException exception) {
            future.cancel(true);
            circuitBreaker.recordTimeout();
            Log.warn("Circuit breaker %s operation #%d timed out after %dms", circuitKey, operationId, timeoutDuration);
            return handleCircuitOpen(annotation, context.getMethod(), context, circuitKey);
        } catch (ExecutionException exception) {
            Throwable cause = exception.getCause();
            circuitBreaker.recordFailure(cause);
            Log.warn("Circuit breaker %s operation #%d failed: %s", circuitKey, operationId, cause.getMessage());
            throw cause;
        }
    }

    private String buildCircuitKey(CircuitBreaker annotation, Class<?> targetClass, Method method) {
        if (!annotation.name().isEmpty()) {
            return annotation.name();
        }

        String parameterSignature = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return targetClass.getName() + "." + method.getName() + "(" + parameterSignature + ")";
    }

    private Object handleCircuitOpen(CircuitBreaker annotation, Method method,
                                     AspectContext context, String circuitKey) throws Throwable {
        if (!annotation.fallbackMethod().isEmpty()) {
            Method fallbackMethod = findFallbackMethod(annotation.fallbackMethod(), method, context.getTarget().getClass());
            if (fallbackMethod != null) {
                return fallbackMethod.invoke(context.getTarget(), context.getArguments());
            }
        }

        throw new net.legacy.library.aop.fault.CircuitBreaker.CircuitBreakerOpenException(
                "Circuit breaker is OPEN: " + circuitKey);
    }

    private Method findFallbackMethod(String fallbackMethodName, Method originalMethod, Class<?> targetClass) {
        return fallbackCache.computeIfAbsent(originalMethod, key -> {
            try {
                Method method = targetClass.getDeclaredMethod(fallbackMethodName, originalMethod.getParameterTypes());
                method.setAccessible(true);
                return method;
            } catch (NoSuchMethodException exception) {
                Log.warn("Fallback method not found: %s in class %s", fallbackMethodName, targetClass.getName());
                return null;
            }
        });
    }

}
