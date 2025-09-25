package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.container.PreDestroy;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.Retry;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;

/**
 * Implements {@link Retry} support with configurable backoff strategies and optional fallbacks.
 * 
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 60)
public class RetryAspect implements MethodInterceptor {

    private final ConcurrentHashMap<Method, Method> fallbackCache = new ConcurrentHashMap<>();
    private final ScheduledExecutorService scheduler = Executors.newScheduledThreadPool(
            Math.max(2, Runtime.getRuntime().availableProcessors() / 2)
    );

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        Retry retry = method.getAnnotation(Retry.class);

        if (retry == null) {
            return invocation.proceed();
        }

        Object[] originalArguments = context.getArguments() != null
                ? context.getArguments().clone()
                : new Object[0];

        if (CompletableFuture.class.isAssignableFrom(method.getReturnType())
                || CompletionStage.class.isAssignableFrom(method.getReturnType())) {
            return executeAsync(context.getTarget(), method, retry, invocation, originalArguments);
        }

        return executeSync(context.getTarget(), method, retry, invocation, originalArguments);
    }

    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(Retry.class);
    }

    @Override
    public int getOrder() {
        return 60;
    }

    private Object executeSync(Object target, Method method, Retry retry, MethodInvocation invocation,
                               Object[] originalArguments) throws Throwable {
        int maxAttempts = normalizeAttempts(retry.maxAttempts());
        Throwable lastError = null;

        for (int attempt = 1; attempt <= maxAttempts; attempt++) {
            try {
                return invocation.proceed();
            } catch (Throwable throwable) {
                Throwable unwrapped = unwrap(throwable);
                if (shouldIgnore(unwrapped, retry)) {
                    throw unwrapped;
                }
                if (shouldRetry(unwrapped, retry)) {
                    lastError = unwrapped;
                    if (attempt == maxAttempts) {
                        break;
                    }

                    long delay = calculateDelayMillis(retry, attempt);
                    if (delay > 0) {
                        Thread.sleep(delay);
                    }
                } else {
                    throw unwrapped;
                }
            }
        }

        Method fallback = resolveFallback(method, retry, target.getClass());
        if (fallback != null) {
            return invokeFallback(target, fallback, retry, lastError, originalArguments);
        }
        throw Objects.requireNonNull(lastError, "Retry exhausted without capturing the failure cause");
    }

    private CompletableFuture<Object> executeAsync(Object target, Method method, Retry retry,
                                                   MethodInvocation invocation, Object[] originalArguments) {
        CompletableFuture<Object> result = new CompletableFuture<>();
        attemptAsync(target, method, retry, invocation, originalArguments, result, 1);
        return result;
    }

    private void attemptAsync(Object target, Method method, Retry retry, MethodInvocation invocation,
                              Object[] originalArguments, CompletableFuture<Object> resultFuture,
                              int attempt) {
        CompletableFuture<Object> execution;
        try {
            Object value = invocation.proceed();
            if (value instanceof CompletionStage<?> stage) {
                execution = stage.toCompletableFuture().thenApply(Object.class::cast);
            } else {
                execution = CompletableFuture.completedFuture(value);
            }
        } catch (Throwable throwable) {
            execution = new CompletableFuture<>();
            execution.completeExceptionally(throwable);
        }

        execution.whenComplete((value, throwable) -> {
            if (throwable == null) {
                resultFuture.complete(value);
                return;
            }

            Throwable failure = unwrap(throwable);
            if (shouldIgnore(failure, retry)) {
                resultFuture.completeExceptionally(failure);
                return;
            }

            int maxAttempts = normalizeAttempts(retry.maxAttempts());
            boolean retryable = shouldRetry(failure, retry);

            if (retryable && attempt < maxAttempts) {
                long delay = calculateDelayMillis(retry, attempt);
                scheduler.schedule(() -> attemptAsync(target, method, retry, invocation, originalArguments,
                        resultFuture, attempt + 1), delay, TimeUnit.MILLISECONDS);
                return;
            }

            Method fallback = resolveFallback(method, retry, target.getClass());
            if (fallback != null) {
                try {
                    Object fallbackResult = invokeFallback(target, fallback, retry, failure, originalArguments);
                    if (fallbackResult instanceof CompletionStage<?> stage) {
                        stage.whenComplete((fv, fe) -> {
                            if (fe == null) {
                                resultFuture.complete(fv);
                            } else {
                                resultFuture.completeExceptionally(fe);
                            }
                        });
                    } else {
                        resultFuture.complete(fallbackResult);
                    }
                } catch (Throwable fallbackError) {
                    resultFuture.completeExceptionally(fallbackError);
                }
            } else {
                resultFuture.completeExceptionally(failure);
            }
        });
    }

    private Method resolveFallback(Method method, Retry retry, Class<?> targetClass) {
        if (retry.fallbackMethod().isEmpty()) {
            return null;
        }

        return fallbackCache.computeIfAbsent(method, key -> {
            try {
                Class<?>[] parameterTypes = method.getParameterTypes();
                if (retry.includeExceptionInFallback()) {
                    Class<?>[] extended = Arrays.copyOf(parameterTypes, parameterTypes.length + 1);
                    extended[extended.length - 1] = Throwable.class;
                    parameterTypes = extended;
                }
                Method fallbackMethod = targetClass.getMethod(retry.fallbackMethod(), parameterTypes);
                fallbackMethod.setAccessible(true);
                return fallbackMethod;
            } catch (NoSuchMethodException noSuchMethodException) {
                throw new IllegalStateException("Fallback method '" + retry.fallbackMethod() + "' not found on "
                        + targetClass.getName(), noSuchMethodException);
            }
        });
    }

    private Object invokeFallback(Object target, Method fallback, Retry retry, Throwable cause,
                                  Object[] originalArguments) throws Throwable {
        Object[] arguments = originalArguments;
        if (retry.includeExceptionInFallback()) {
            Object[] extended = Arrays.copyOf(originalArguments, originalArguments.length + 1);
            extended[extended.length - 1] = cause;
            arguments = extended;
        }
        try {
            return fallback.invoke(target, arguments);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            Throwable targetException = reflectiveOperationException.getCause();
            throw targetException != null ? targetException : reflectiveOperationException;
        }
    }

    private boolean shouldRetry(Throwable throwable, Retry retry) {
        Class<? extends Throwable>[] retryOn = retry.retryOn();
        if (retryOn.length == 0) {
            return true;
        }
        return Stream.of(retryOn).anyMatch(type -> type.isInstance(throwable));
    }

    private boolean shouldIgnore(Throwable throwable, Retry retry) {
        return Stream.of(retry.ignoreExceptions()).anyMatch(type -> type.isInstance(throwable));
    }

    private Throwable unwrap(Throwable throwable) {
        if (throwable instanceof CompletionException completionException && completionException.getCause() != null) {
            return unwrap(completionException.getCause());
        }
        return throwable;
    }

    private long calculateDelayMillis(Retry retry, int attemptIndex) {
        long initialDelay = Math.max(0, retry.initialDelay());
        if (attemptIndex <= 1) {
            return initialDelay;
        }

        long delay;
        switch (retry.backoffStrategy()) {
            case LINEAR -> delay = initialDelay + (initialDelay * (attemptIndex - 1));
            case RANDOM -> {
                long maxDelay = Math.max(initialDelay, retry.maxDelay());
                long upperBound = maxDelay == Long.MAX_VALUE ? Long.MAX_VALUE : Math.max(initialDelay + 1, maxDelay + 1);
                delay = initialDelay == upperBound
                        ? initialDelay
                        : ThreadLocalRandom.current().nextLong(initialDelay, upperBound);
            }
            case EXPONENTIAL -> {
                double multiplier = Math.max(1.0, retry.backoffMultiplier());
                delay = (long) (initialDelay * Math.pow(multiplier, attemptIndex - 1));
            }
            default -> delay = initialDelay;
        }

        double jitterFactor = Math.max(0.0, Math.min(1.0, retry.jitterFactor()));
        if (jitterFactor > 0.0) {
            double jitterRange = delay * jitterFactor;
            double jitter = (ThreadLocalRandom.current().nextDouble() * 2 - 1) * jitterRange;
            delay = Math.max(0L, (long) (delay + jitter));
        }

        long maxDelay = retry.maxDelay() > 0 ? retry.maxDelay() : Long.MAX_VALUE;
        return Math.min(delay, maxDelay);
    }

    private int normalizeAttempts(int maxAttempts) {
        if (maxAttempts == 0) {
            return 1;
        }
        return maxAttempts < 0 ? Integer.MAX_VALUE : maxAttempts;
    }

    @PreDestroy
    public void shutdown() {
        scheduler.shutdown();
        try {
            if (!scheduler.awaitTermination(250, TimeUnit.MILLISECONDS)) {
                scheduler.shutdownNow();
            }
        } catch (InterruptedException interruptedException) {
            scheduler.shutdownNow();
            Thread.currentThread().interrupt();
        }
    }

}
