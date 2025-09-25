package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.CircuitBreaker;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.stream.Collectors;

/**
 * Aspect for implementing circuit breaker pattern.
 *
 * <p>This aspect intercepts method calls and implements circuit breaker functionality
 * to prevent cascading failures in distributed systems. It tracks failure counts,
 * manages circuit states (CLOSED, OPEN, HALF_OPEN), and provides recovery mechanisms.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
@AOPInterceptor(global = true, order = 70)
public class CircuitBreakerAspect implements MethodInterceptor {

    private final ConcurrentHashMap<String, CircuitBreakerState> circuitStates = new ConcurrentHashMap<>();
    private final AtomicLong operationCounter = new AtomicLong(0);

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        CircuitBreaker circuitBreaker = method.getAnnotation(CircuitBreaker.class);

        if (circuitBreaker == null) {
            return invocation.proceed();
        }

        Class<?> targetClass = context.getTarget() != null ? context.getTarget().getClass() : method.getDeclaringClass();
        String circuitKey = buildCircuitKey(targetClass, method);
        CircuitBreakerState state = circuitStates.computeIfAbsent(circuitKey, k ->
                new CircuitBreakerState(circuitBreaker));

        long operationId = operationCounter.incrementAndGet();

        return handleWithCircuitBreaker(state, invocation, circuitKey, operationId);
    }

    private String buildCircuitKey(Class<?> targetClass, Method method) {
        String parameterSignature = Arrays.stream(method.getParameterTypes())
                .map(Class::getName)
                .collect(Collectors.joining(","));
        return targetClass.getName() + "." + method.getName() + "(" + parameterSignature + ")";
    }

    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(CircuitBreaker.class);
    }

    @Override
    public int getOrder() {
        return 70; // High priority, before business logic
    }

    /**
     * Handles method execution with circuit breaker protection.
     *
     * @param state the circuit breaker state
     * @param invocation the method invocation
     * @param circuitKey the circuit key
     * @param operationId the operation ID
     * @return the method result
     * @throws Throwable if the method fails
     */
    private Object handleWithCircuitBreaker(CircuitBreakerState state, MethodInvocation invocation,
                                            String circuitKey, long operationId) throws Throwable {

        if (!state.allowCall()) {
            Log.warn("Circuit breaker %s is OPEN, blocking operation #%d", circuitKey, operationId);
            throw new RuntimeException("Circuit breaker is OPEN: " + circuitKey);
        }

        try {
            Object result = invocation.proceed();
            state.recordSuccess();
            return result;
        } catch (Throwable throwable) {
            state.recordFailure();
            Log.warn("Circuit breaker %s operation #%d failed: %s", circuitKey, operationId, throwable.getMessage());
            throw throwable;
        }
    }


    /**
     * Circuit breaker states.
     */
    private enum CircuitState {
        CLOSED,      // Normal operation, requests flow through
        OPEN,        // Circuit is open, requests are blocked
        HALF_OPEN    // Testing if circuit should be closed
    }

    /**
     * Circuit breaker state management.
     */
    private static class CircuitBreakerState {

        @Getter
        private final CircuitBreaker circuitBreaker;
        private final AtomicInteger failureCount = new AtomicInteger(0);
        private final AtomicInteger successCount = new AtomicInteger(0);
        private final AtomicInteger totalCallCount = new AtomicInteger(0);

        @Getter
        private volatile CircuitState state = CircuitState.CLOSED;
        private volatile long lastStateChange = System.currentTimeMillis();

        public CircuitBreakerState(CircuitBreaker circuitBreaker) {
            this.circuitBreaker = circuitBreaker;
        }

        /**
         * Determines whether the current call may proceed.
         *
         * @return {@code true} if the call is allowed, {@code false} otherwise
         */
        public synchronized boolean allowCall() {
            if (state == CircuitState.OPEN) {
                if (System.currentTimeMillis() - lastStateChange >= circuitBreaker.waitDurationInOpenState()) {
                    transitionToHalfOpen();
                    return true;
                }
                return false;
            }
            return true;
        }

        /**
         * Records a successful operation.
         */
        public synchronized void recordSuccess() {
            totalCallCount.incrementAndGet();
            if (state == CircuitState.HALF_OPEN) {
                successCount.incrementAndGet();
                if (successCount.get() >= circuitBreaker.permittedNumberOfCallsInHalfOpenState()) {
                    transitionToClosed();
                }
                return;
            }

            successCount.incrementAndGet();
            failureCount.set(0);
        }

        /**
         * Records a failed operation.
         */
        public synchronized void recordFailure() {
            totalCallCount.incrementAndGet();
            failureCount.incrementAndGet();

            if (state == CircuitState.HALF_OPEN) {
                transitionToOpen();
                return;
            }

            if (state == CircuitState.CLOSED
                    && totalCallCount.get() >= circuitBreaker.minimumNumberOfCalls()
                    && failureCount.get() >= circuitBreaker.failureCountThreshold()) {
                transitionToOpen();
            }
        }

        /**
         * Transitions to CLOSED state.
         */
        public void transitionToClosed() {
            state = CircuitState.CLOSED;
            failureCount.set(0);
            successCount.set(0);
            totalCallCount.set(0);
            lastStateChange = System.currentTimeMillis();
        }

        /**
         * Transitions to OPEN state.
         */
        public void transitionToOpen() {
            state = CircuitState.OPEN;
            failureCount.set(0);
            successCount.set(0);
            totalCallCount.set(0);
            lastStateChange = System.currentTimeMillis();
        }

        /**
         * Transitions to HALF_OPEN state.
         */
        public void transitionToHalfOpen() {
            state = CircuitState.HALF_OPEN;
            successCount.set(0);
            failureCount.set(0);
            totalCallCount.set(0);
            lastStateChange = System.currentTimeMillis();
        }

        /**
         * Checks if the circuit is OPEN.
         *
         * @return true if the circuit is OPEN
         */
        public boolean isOpen() {
            return state == CircuitState.OPEN;
        }

    }

}
