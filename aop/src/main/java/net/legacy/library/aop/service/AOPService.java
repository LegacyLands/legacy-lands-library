package net.legacy.library.aop.service;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.aspect.AsyncSafeAspect;
import net.legacy.library.aop.aspect.CircuitBreakerAspect;
import net.legacy.library.aop.aspect.DistributedTransactionAspect;
import net.legacy.library.aop.aspect.DynamicConfigAspect;
import net.legacy.library.aop.aspect.LoggingAspect;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.aspect.RateLimiterAspect;
import net.legacy.library.aop.aspect.RetryAspect;
import net.legacy.library.aop.aspect.SecurityAspect;
import net.legacy.library.aop.aspect.TracingAspect;
import net.legacy.library.aop.aspect.ValidationAspect;
import net.legacy.library.aop.config.AOPModuleConfiguration;
import net.legacy.library.aop.fault.RateLimiter.RateLimiterMetrics;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.model.MethodMetrics;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.registry.InterceptorRegistry;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Coordinates proxy creation, interceptor registration, and ClassLoader-scoped lifecycle for the AOP module.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
public class AOPService {

    private final AspectProxyFactory proxyFactory;
    private final ClassLoaderIsolationService isolationService;
    private final DistributedTransactionAspect distributedTransactionAspect;
    private final SecurityAspect securityAspect;
    private final CircuitBreakerAspect circuitBreakerAspect;
    private final RetryAspect retryAspect;
    private final ValidationAspect validationAspect;
    private final TracingAspect tracingAspect;
    private final MonitoringAspect monitoringAspect;
    private final AsyncSafeAspect asyncSafeAspect;
    private final LoggingAspect loggingAspect;
    private final RateLimiterAspect rateLimiterAspect;
    private final DynamicConfigAspect dynamicConfigAspect;

    private final Map<Class<?>, List<MethodInterceptor>> globalInterceptors = new ConcurrentHashMap<>();

    @Getter
    private volatile AOPModuleConfiguration moduleConfiguration = AOPModuleConfiguration.enableAll();

    public AOPService(AspectProxyFactory proxyFactory,
                      ClassLoaderIsolationService isolationService,
                      DistributedTransactionAspect distributedTransactionAspect,
                      SecurityAspect securityAspect,
                      CircuitBreakerAspect circuitBreakerAspect,
                      RetryAspect retryAspect,
                      ValidationAspect validationAspect,
                      TracingAspect tracingAspect) {
        this(proxyFactory,
                isolationService,
                distributedTransactionAspect,
                securityAspect,
                circuitBreakerAspect,
                retryAspect,
                validationAspect,
                tracingAspect,
                null,
                null,
                null,
                null,
                null);
    }

    /**
     * Bootstraps the AOP module based on configuration flags and available aspects.
     */
    public synchronized void initialize() {
        moduleConfiguration = moduleConfiguration != null ? moduleConfiguration : AOPModuleConfiguration.enableAll();

        globalInterceptors.clear();

        Log.info("Initializing AOP service (tracing=%s, retry=%s, security=%s, tx=%s, monitoring=%s, async=%s, logging=%s, faultTolerance=%s, rateLimiter=%s, dynamicConfig=%s)",
                moduleConfiguration.isTracingEnabled(),
                moduleConfiguration.isRetryEnabled(),
                moduleConfiguration.isSecurityEnabled(),
                moduleConfiguration.isDistributedTransactionEnabled(),
                moduleConfiguration.isMonitoringEnabled(),
                moduleConfiguration.isAsyncSafeEnabled(),
                moduleConfiguration.isLoggingEnabled(),
                moduleConfiguration.isFaultToleranceEnabled(),
                moduleConfiguration.isRateLimiterEnabled(),
                moduleConfiguration.isDynamicConfigEnabled());

        isolationService.setModuleConfiguration(moduleConfiguration);

        // Register managed interceptors in priority order (lowest order = highest priority)
        registerManagedInterceptor(dynamicConfigAspect);   // order 10 - config injection first
        registerManagedInterceptor(validationAspect);
        registerManagedInterceptor(loggingAspect);
        registerManagedInterceptor(monitoringAspect);
        registerManagedInterceptor(asyncSafeAspect);
        registerManagedInterceptor(rateLimiterAspect);     // order 65
        registerManagedInterceptor(circuitBreakerAspect);
        registerManagedInterceptor(retryAspect);
        registerManagedInterceptor(tracingAspect);
        registerManagedInterceptor(distributedTransactionAspect);
        registerManagedInterceptor(securityAspect);
    }

    /**
     * Updates the module configuration used during initialization and proxy creation.
     *
     * @param configuration module configuration to apply; falling back to {@link AOPModuleConfiguration#enableAll()} when {@code null}
     */
    public void setModuleConfiguration(AOPModuleConfiguration configuration) {
        this.moduleConfiguration = configuration != null ? configuration : AOPModuleConfiguration.enableAll();
    }

    /**
     * Registers enterprise-level aspects as class-specific interceptors for the supplied test classes.
     */
    public void registerTestInterceptors(Class<?>... testClasses) {
        ensureConfigurationLoaded();

        for (Class<?> testClass : testClasses) {
            registerClassInterceptorIfEnabled(testClass, dynamicConfigAspect);
            registerClassInterceptorIfEnabled(testClass, validationAspect);
            registerClassInterceptorIfEnabled(testClass, loggingAspect);
            registerClassInterceptorIfEnabled(testClass, monitoringAspect);
            registerClassInterceptorIfEnabled(testClass, asyncSafeAspect);
            registerClassInterceptorIfEnabled(testClass, rateLimiterAspect);
            registerClassInterceptorIfEnabled(testClass, circuitBreakerAspect);
            registerClassInterceptorIfEnabled(testClass, retryAspect);
            registerClassInterceptorIfEnabled(testClass, tracingAspect);
            registerClassInterceptorIfEnabled(testClass, distributedTransactionAspect);
            registerClassInterceptorIfEnabled(testClass, securityAspect);
        }
    }

    /**
     * Creates a dynamic proxy for the specified target object.
     */
    @SuppressWarnings("DataFlowIssue") // NULL IN TEST
    public <T> T createProxy(T target) {
        if (target == null) {
            throw new IllegalArgumentException("Target object cannot be null");
        }
        ensureConfigurationLoaded();

        Class<?> targetClass = target.getClass();
        List<MethodInterceptor> interceptors = getInterceptorsForClass(targetClass);
        if (interceptors.isEmpty()) {
            return target;
        }

        if (hasApplicableInterceptor(targetClass, interceptors)) {
            try {
                return proxyFactory.createProxy(target, interceptors);
            } catch (Exception exception) {
                Log.error("Failed to create proxy for %s: %s", target.getClass().getName(), exception.getMessage());
            }
        }

        return target;
    }

    /**
     * Creates a dynamic proxy with both custom and auto-discovered interceptors.
     */
    public <T> T createProxy(T target, List<MethodInterceptor> interceptors) {
        if (target == null) {
            throw new IllegalArgumentException("Target object cannot be null");
        }
        if (interceptors == null) {
            throw new IllegalArgumentException("Interceptors cannot be null");
        }
        ensureConfigurationLoaded();

        Class<?> targetClass = target.getClass();
        List<MethodInterceptor> allInterceptors = new ArrayList<>(interceptors);
        allInterceptors.addAll(getInterceptorsForClass(targetClass));

        if (allInterceptors.isEmpty()) {
            return target;
        }

        if (hasApplicableInterceptor(targetClass, allInterceptors)) {
            return proxyFactory.createProxy(target, allInterceptors);
        }

        return target;
    }

    private boolean hasApplicableInterceptor(Class<?> targetClass, List<MethodInterceptor> interceptors) {
        if (interceptors.isEmpty()) {
            return false;
        }

        Method[] candidateMethods = gatherCandidateMethods(targetClass);
        for (Method method : candidateMethods) {
            for (MethodInterceptor interceptor : interceptors) {
                if (interceptor.supports(method)) {
                    return true;
                }
            }
        }
        return false;
    }

    private Method[] gatherCandidateMethods(Class<?> targetClass) {
        List<Method> methods = new ArrayList<>();
        methods.addAll(Arrays.asList(targetClass.getMethods()));
        methods.addAll(Arrays.asList(targetClass.getDeclaredMethods()));
        return methods.toArray(Method[]::new);
    }

    /**
     * Registers a global interceptor.
     */
    public void registerGlobalInterceptor(MethodInterceptor interceptor) {
        if (interceptor == null) {
            return;
        }

        List<MethodInterceptor> interceptors = globalInterceptors.computeIfAbsent(Object.class, key -> new ArrayList<>());
        if (!interceptors.contains(interceptor)) {
            interceptors.add(interceptor);
        }
    }

    public void registerInterceptor(Class<?> targetClass, MethodInterceptor interceptor) {
        if (interceptor == null) {
            return;
        }
        proxyFactory.registerInterceptor(targetClass, interceptor);
    }

    public MethodMetrics getMonitoringMetrics(String operationName) {
        MonitoringAspect monitoring = monitoringAspect != null ? monitoringAspect : InterceptorRegistry.getMonitoringAspect();
        if (monitoring == null) {
            Log.warn("MonitoringAspect not available in current configuration");
            return null;
        }
        return monitoring.getMetrics(operationName);
    }

    public void clearMonitoringMetrics() {
        MonitoringAspect monitoring = monitoringAspect != null ? monitoringAspect : InterceptorRegistry.getMonitoringAspect();
        if (monitoring != null) {
            monitoring.clearMetrics();
        }
    }

    /**
     * Gets the rate limiter metrics for a specific key.
     *
     * @param key the rate limiter key
     * @return the rate limiter metrics, or null if not available
     */
    public RateLimiterMetrics getRateLimiterMetrics(String key) {
        if (rateLimiterAspect == null) {
            Log.warn("RateLimiterAspect not available in current configuration");
            return null;
        }
        return rateLimiterAspect.getMetrics(key);
    }

    /**
     * Gets the circuit breaker metrics for a specific key.
     *
     * @param key the circuit breaker key
     * @return the circuit breaker metrics, or null if not available
     */
    public net.legacy.library.aop.fault.CircuitBreaker.CircuitBreakerMetrics getCircuitBreakerMetrics(String key) {
        if (circuitBreakerAspect == null) {
            Log.warn("CircuitBreakerAspect not available in current configuration");
            return null;
        }
        return circuitBreakerAspect.getMetrics(key);
    }

    public void cleanupClassLoader(ClassLoader classLoader) {
        isolationService.cleanup(classLoader);
        Log.info("Cleaned up AOP resources for ClassLoader: %s", classLoader);
    }

    public void shutdown() {
        if (asyncSafeAspect != null) {
            asyncSafeAspect.shutdown();
        }
        if (retryAspect != null) {
            retryAspect.shutdown();
        }
        if (rateLimiterAspect != null) {
            rateLimiterAspect.clearAll();
        }
        if (circuitBreakerAspect != null) {
            circuitBreakerAspect.clearAll();
        }
        if (dynamicConfigAspect != null) {
            dynamicConfigAspect.clearCaches();
        }
        clearMonitoringMetrics();
        globalInterceptors.clear();
    }

    private void registerManagedInterceptor(MethodInterceptor interceptor) {
        if (!isFeatureEnabled(interceptor)) {
            return;
        }

        registerGlobalInterceptor(interceptor);
        InterceptorRegistry.register(interceptor);
    }

    private void registerClassInterceptorIfEnabled(Class<?> targetClass, MethodInterceptor interceptor) {
        if (!isFeatureEnabled(interceptor)) {
            return;
        }
        registerInterceptor(targetClass, interceptor);
    }

    private boolean isFeatureEnabled(MethodInterceptor interceptor) {
        if (interceptor == null) {
            return false;
        }
        ensureConfigurationLoaded();

        return switch (interceptor) {
            case DistributedTransactionAspect ignored -> moduleConfiguration.isDistributedTransactionEnabled();
            case SecurityAspect ignored -> moduleConfiguration.isSecurityEnabled();
            case CircuitBreakerAspect ignored -> moduleConfiguration.isFaultToleranceEnabled();
            case RetryAspect ignored -> moduleConfiguration.isRetryEnabled();
            case TracingAspect ignored -> moduleConfiguration.isTracingEnabled();
            case MonitoringAspect ignored -> moduleConfiguration.isMonitoringEnabled();
            case AsyncSafeAspect ignored -> moduleConfiguration.isAsyncSafeEnabled();
            case LoggingAspect ignored -> moduleConfiguration.isLoggingEnabled();
            case RateLimiterAspect ignored -> moduleConfiguration.isRateLimiterEnabled();
            case DynamicConfigAspect ignored -> moduleConfiguration.isDynamicConfigEnabled();
            default -> true;
        };
    }

    private void ensureConfigurationLoaded() {
        if (moduleConfiguration == null) {
            moduleConfiguration = AOPModuleConfiguration.enableAll();
        }
    }

    private List<MethodInterceptor> getInterceptorsForClass(Class<?> targetClass) {
        List<MethodInterceptor> result = new ArrayList<>(globalInterceptors.getOrDefault(Object.class, List.of()));

        List<MethodInterceptor> classSpecific = proxyFactory.getInterceptors(targetClass);
        for (MethodInterceptor interceptor : classSpecific) {
            if (!result.contains(interceptor) && isFeatureEnabled(interceptor)) {
                result.add(interceptor);
            }
        }

        return result;
    }

}
