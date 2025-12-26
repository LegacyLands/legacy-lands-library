package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.Secured;
import net.legacy.library.aop.annotation.SecurityContext;
import net.legacy.library.aop.annotation.SecurityPolicy;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.security.SecurityProvider;
import net.legacy.library.aop.security.SecurityProviderRegistry;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aspect for securing method execution with authentication and authorization.
 *
 * <p>This aspect intercepts methods annotated with {@link Secured} and enforces
 * security policies including authentication, role-based authorization, permission
 * checking, and audit logging. It integrates with various security providers
 * and supports custom security policies.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
@AOPInterceptor(global = true, order = 100)
public class SecurityAspect implements MethodInterceptor {

    private final SecurityProviderRegistry securityProviderRegistry;

    // Rate limiting state
    private final Map<String, RateLimitState> rateLimitStates = new ConcurrentHashMap<>();
    private final Map<String, AtomicLong> auditCounters = new ConcurrentHashMap<>();

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        Secured secured = method.getAnnotation(Secured.class);

        // Get security provider
        SecurityProvider provider = securityProviderRegistry.getProvider(secured.provider());
        if (provider == null) {
            provider = securityProviderRegistry.getDefaultProvider();
        }
        if (provider == null) {
            throw new SecurityException("Security provider not found: " + secured.provider());
        }

        // Create security context
        SecurityContext securityContext = createSecurityContext(context, invocation, provider);

        // Apply security checks
        enforceSecurity(secured, securityContext);

        // Execute method with audit logging
        long startTime = System.currentTimeMillis();
        try {
            Object result = invocation.proceed();

            // Log successful execution
            if (secured.audit()) {
                logAuditEvent(context, securityContext, true, System.currentTimeMillis() - startTime, null);
            }

            return result;

        } catch (Throwable throwable) {
            // Log failed execution
            if (secured.audit()) {
                logAuditEvent(context, securityContext, false, System.currentTimeMillis() - startTime, throwable);
            }

            // Check if this exception should be wrapped
            if (secured.onAuthorizationFailure() != SecurityException.class &&
                    throwable instanceof SecurityException) {
                throw createAuthorizationFailure(secured, throwable);
            }

            throw throwable;
        }
    }

    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(Secured.class);
    }

    @Override
    public int getOrder() {
        return 100; // High priority for security
    }

    /**
     * Creates a security context for the method invocation.
     *
     * @param context    the aspect context
     * @param invocation the method invocation
     * @param provider   the security provider
     * @return security context
     */
    private SecurityContext createSecurityContext(AspectContext context, MethodInvocation invocation,
                                                  SecurityProvider provider) {
        Object principal = provider.getPrincipal();
        boolean authenticated = provider.isAuthenticated();
        String[] roles = provider.getRoles();
        String[] permissions = provider.getPermissions();

        Map<String, Object> attributes = new HashMap<>();
        attributes.put("remoteAddress", getRemoteAddress());
        attributes.put("userAgent", getUserAgent());
        attributes.put("timestamp", System.currentTimeMillis());

        return new SecurityContext(
                principal,
                authenticated,
                roles,
                permissions,
                provider.getName(),
                context.getMethod().getName(),
                invocation.getArguments(),
                attributes
        );
    }

    /**
     * Enforces security policies for the method invocation.
     *
     * @param secured the security annotation
     * @param context the security context
     * @throws SecurityException if security checks fail
     */
    private void enforceSecurity(Secured secured, SecurityContext context)
            throws SecurityException {

        // Check authentication
        if (secured.authenticated() && !context.isAuthenticated()) {
            throw new SecurityException("Authentication required for method: " + context.getMethodName());
        }

        Arrays.stream(secured.roles())
                .filter(role -> !context.hasRole(role))
                .findFirst()
                .ifPresent(role -> {
                    throw new SecurityException("Required role not found: " + role);
                });

        Arrays.stream(secured.permissions())
                .filter(permission -> !context.hasPermission(permission))
                .findFirst()
                .ifPresent(permission -> {
                    throw new SecurityException("Required permission not found: " + permission);
                });

        // Check rate limiting
        if (secured.rateLimited()) {
            checkRateLimit(secured, context);
        }

        // Apply custom security policy
        if (secured.policy() != SecurityPolicy.class) {
            try {
                SecurityPolicy policy = secured.policy().getDeclaredConstructor().newInstance();
                if (!policy.isAuthorized(context)) {
                    throw new SecurityException(policy.getErrorMessage());
                }
            } catch (Exception exception) {
                throw new SecurityException("Failed to apply security policy", exception);
            }
        }
    }

    /**
     * Checks rate limiting for the method invocation.
     *
     * @param secured the security annotation
     * @param context the security context
     * @throws SecurityException if rate limit is exceeded
     */
    private void checkRateLimit(Secured secured, SecurityContext context) {
        String key = getRateLimitKey(context);
        RateLimitState state = rateLimitStates.computeIfAbsent(key, k -> new RateLimitState());

        long currentTime = System.currentTimeMillis();
        long windowStart = currentTime - (secured.timeWindow() * 1000L);

        state.lock.lock();
        try {
            // Clean old requests
            state.requestTimes.removeIf(time -> time < windowStart);

            // Check if limit exceeded
            if (state.requestTimes.size() >= secured.maxRequests()) {
                throw new SecurityException("Rate limit exceeded for method: " + context.getMethodName());
            }

            // Add current request
            state.requestTimes.add(currentTime);
        } finally {
            state.lock.unlock();
        }
    }

    /**
     * Gets the rate limit key for the context.
     *
     * @param context the security context
     * @return rate limit key
     */
    private String getRateLimitKey(SecurityContext context) {
        Object principal = context.getPrincipal();
        String principalKey = principal != null ? principal.toString() : "anonymous";
        return context.getMethodName() + ":" + principalKey;
    }

    /**
     * Logs an audit event for the method invocation.
     *
     * @param context         the aspect context
     * @param securityContext the security context
     * @param success         whether the invocation was successful
     * @param duration        the duration of the invocation
     * @param throwable       the exception if failed, or null if successful
     */
    private void logAuditEvent(AspectContext context, SecurityContext securityContext,
                               boolean success, long duration, Throwable throwable) {
        String key = context.getMethod().getName() + ":" +
                (securityContext.getPrincipal() != null ? securityContext.getPrincipal().toString() : "anonymous");

        AtomicLong counter = auditCounters.computeIfAbsent(key, k -> new AtomicLong(0));
        long eventNumber = counter.incrementAndGet();

        Log.info("SECURITY_AUDIT - Event #%d - Method: %s, Principal: %s, Success: %s, Duration: %dms%s",
                eventNumber,
                context.getMethod().getName(),
                securityContext.getPrincipal() != null ? securityContext.getPrincipal().toString() : "anonymous",
                success,
                duration,
                throwable != null ? ", Error: " + throwable.getMessage() : "");
    }

    /**
     * Creates an authorization failure exception.
     *
     * @param secured the security annotation
     * @param cause   the original exception
     * @return the authorization failure exception
     */
    private Throwable createAuthorizationFailure(Secured secured, Throwable cause) {
        try {
            return secured.onAuthorizationFailure()
                    .getConstructor(String.class)
                    .newInstance("Authorization failed: " + cause.getMessage());
        } catch (Exception exception) {
            return new SecurityException("Authorization failed", cause);
        }
    }

    /**
     * Gets the remote address from the current context.
     *
     * @return remote address
     */
    private String getRemoteAddress() {
        // This would typically be obtained from the current request context
        // For now, return a placeholder
        return "unknown";
    }

    /**
     * Gets the user agent from the current context.
     *
     * @return user agent
     */
    private String getUserAgent() {
        // This would typically be obtained from the current request context
        // For now, return a placeholder
        return "unknown";
    }

    /**
     * Rate limit state for tracking request timestamps.
     *
     * <p>Uses {@link ReentrantLock} instead of {@code synchronized} to avoid
     * virtual thread pinning in Java 21.
     */
    private static class RateLimitState {

        private final ReentrantLock lock = new ReentrantLock();
        private final java.util.List<Long> requestTimes = new java.util.ArrayList<>();

    }

}
