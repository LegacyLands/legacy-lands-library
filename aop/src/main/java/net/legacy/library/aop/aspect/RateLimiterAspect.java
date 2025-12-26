package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.RateLimiter;
import net.legacy.library.aop.fault.RateLimiter.RateLimitExceededException;
import net.legacy.library.aop.fault.RateLimiter.RateLimitStrategy;
import net.legacy.library.aop.fault.RateLimiter.RateLimiterConfig;
import net.legacy.library.aop.fault.RateLimiter.RateLimiterMetrics;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Aspect for automatic rate limiting on methods.
 *
 * <p>This aspect intercepts methods annotated with {@link RateLimiter} and
 * controls the execution frequency based on configured policies. It integrates
 * with the {@link net.legacy.library.aop.fault.RateLimiter} utility class for
 * actual rate limiting logic.
 *
 * <p>Supports four rate limiting strategies:
 * <ul>
 *   <li>{@link RateLimitStrategy#FIXED_WINDOW} - Fixed window with periodic resets</li>
 *   <li>{@link RateLimitStrategy#SLIDING_WINDOW} - Sliding window with smooth transitions</li>
 *   <li>{@link RateLimitStrategy#TOKEN_BUCKET} - Token bucket with burst handling</li>
 *   <li>{@link RateLimitStrategy#LEAKY_BUCKET} - Leaky bucket with constant output rate</li>
 * </ul>
 *
 * @author qwq-dev
 * @version 1.0
 * @see RateLimiter
 * @see net.legacy.library.aop.fault.RateLimiter
 * @since 2025-12-25 15:00
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 65)
public class RateLimiterAspect implements MethodInterceptor {

    private static final Pattern KEY_PLACEHOLDER_PATTERN = Pattern.compile("\\{#([^}]+)}");

    private final Map<Method, Method> fallbackCache = new ConcurrentHashMap<>();
    private final Map<String, net.legacy.library.aop.fault.RateLimiter> rateLimiters = new ConcurrentHashMap<>();

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
        RateLimiter annotation = method.getAnnotation(RateLimiter.class);

        String rateLimiterKey = buildRateLimiterKey(annotation, method, context);
        net.legacy.library.aop.fault.RateLimiter rateLimiter = getOrCreateRateLimiter(rateLimiterKey, annotation);

        boolean acquired;
        if (annotation.waitForNextSlot()) {
            try {
                acquired = rateLimiter.acquire(1, annotation.maxWaitTime());
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                throw new RateLimitExceededException("Rate limiting wait interrupted for: " + rateLimiterKey, exception);
            }
        } else {
            acquired = rateLimiter.tryAcquire();
        }

        if (!acquired) {
            return handleRateLimitExceeded(annotation, method, context, rateLimiterKey);
        }

        return invocation.proceed();
    }

    /**
     * {@inheritDoc}
     *
     * @param method {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(RateLimiter.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 65;
    }

    /**
     * Gets the metrics for a specific rate limiter.
     *
     * @param key the rate limiter key
     * @return the rate limiter metrics, or null if not found
     */
    public RateLimiterMetrics getMetrics(String key) {
        net.legacy.library.aop.fault.RateLimiter rateLimiter = rateLimiters.get(key);
        return rateLimiter != null ? rateLimiter.getMetrics() : null;
    }

    /**
     * Resets a specific rate limiter.
     *
     * @param key the rate limiter key
     */
    public void resetRateLimiter(String key) {
        net.legacy.library.aop.fault.RateLimiter rateLimiter = rateLimiters.get(key);
        if (rateLimiter != null) {
            rateLimiter.reset();
        }
    }

    /**
     * Clears all rate limiters and caches.
     */
    public void clearAll() {
        rateLimiters.clear();
        fallbackCache.clear();
    }

    private net.legacy.library.aop.fault.RateLimiter getOrCreateRateLimiter(String key, RateLimiter annotation) {
        return rateLimiters.computeIfAbsent(key, k -> {
            RateLimitStrategy strategy = convertStrategy(annotation.strategy());
            RateLimiterConfig config = new RateLimiterConfig(
                    annotation.limit(),
                    annotation.period(),
                    strategy
            );
            return new net.legacy.library.aop.fault.RateLimiter(key, config);
        });
    }

    private RateLimitStrategy convertStrategy(RateLimiter.RateLimitStrategy annotationStrategy) {
        return switch (annotationStrategy) {
            case FIXED_WINDOW -> RateLimitStrategy.FIXED_WINDOW;
            case SLIDING_WINDOW -> RateLimitStrategy.SLIDING_WINDOW;
            case TOKEN_BUCKET -> RateLimitStrategy.TOKEN_BUCKET;
            case LEAKY_BUCKET -> RateLimitStrategy.LEAKY_BUCKET;
        };
    }

    private String buildRateLimiterKey(RateLimiter annotation, Method method, AspectContext context) {
        String baseName = annotation.name().isEmpty()
                ? context.getTarget().getClass().getName() + "." + method.getName()
                : annotation.name();

        if (annotation.keyExpression().isEmpty()) {
            return baseName;
        }

        String keyExpression = annotation.keyExpression();
        String resolvedKey = resolveKeyExpression(keyExpression, method, context.getArguments());
        return baseName + ":" + resolvedKey;
    }

    private String resolveKeyExpression(String expression, Method method, Object[] arguments) {
        Matcher matcher = KEY_PLACEHOLDER_PATTERN.matcher(expression);
        StringBuilder result = new StringBuilder();

        while (matcher.find()) {
            String placeholder = matcher.group(1);
            String replacement = resolveParameterValue(placeholder, method, arguments);
            matcher.appendReplacement(result, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(result);

        return result.toString();
    }

    private String resolveParameterValue(String placeholder, Method method, Object[] arguments) {
        Parameter[] parameters = method.getParameters();

        // Handle indexed parameter like "arg0", "arg1"
        if (placeholder.startsWith("arg") && placeholder.length() > 3) {
            try {
                int index = Integer.parseInt(placeholder.substring(3));
                if (index >= 0 && index < arguments.length) {
                    return String.valueOf(arguments[index]);
                }
            } catch (NumberFormatException exception) {
                // Fall through to named parameter handling
            }
        }

        // Handle named parameter
        for (int i = 0; i < parameters.length; i++) {
            if (parameters[i].getName().equals(placeholder)) {
                return String.valueOf(arguments[i]);
            }
        }

        // Handle nested property access like "user.id"
        String[] parts = placeholder.split("\\.");
        if (parts.length > 1) {
            for (int i = 0; i < parameters.length; i++) {
                if (parameters[i].getName().equals(parts[0])) {
                    return resolveNestedProperty(arguments[i], parts, 1);
                }
            }
        }

        Log.warn("Unable to resolve rate limiter key placeholder: %s", placeholder);
        return placeholder;
    }

    private String resolveNestedProperty(Object object, String[] parts, int startIndex) {
        Object current = object;

        for (int i = startIndex; i < parts.length && current != null; i++) {
            try {
                String propertyName = parts[i];
                String getterName = "get" + Character.toUpperCase(propertyName.charAt(0)) + propertyName.substring(1);
                Method getter = current.getClass().getMethod(getterName);
                current = getter.invoke(current);
            } catch (Exception exception) {
                Log.warn("Unable to resolve nested property: %s", String.join(".", parts));
                return String.join(".", parts);
            }
        }

        return current != null ? String.valueOf(current) : "null";
    }

    private Object handleRateLimitExceeded(RateLimiter annotation, Method method,
                                           AspectContext context, String rateLimiterKey) throws Throwable {
        if (!annotation.fallbackMethod().isEmpty()) {
            Method fallbackMethod = findFallbackMethod(annotation.fallbackMethod(), method, context.getTarget().getClass());
            if (fallbackMethod != null) {
                return fallbackMethod.invoke(context.getTarget(), context.getArguments());
            }
        }

        throw new RateLimitExceededException("Rate limit exceeded for: " + rateLimiterKey);
    }

    private Method findFallbackMethod(String fallbackMethodName, Method originalMethod, Class<?> targetClass) {
        return fallbackCache.computeIfAbsent(originalMethod, key -> {
            try {
                return targetClass.getDeclaredMethod(fallbackMethodName, originalMethod.getParameterTypes());
            } catch (NoSuchMethodException exception) {
                Log.warn("Fallback method not found: %s in class %s", fallbackMethodName, targetClass.getName());
                return null;
            }
        });
    }

}
