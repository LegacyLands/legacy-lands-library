package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.CircuitBreaker;
import net.legacy.library.aop.aspect.CircuitBreakerAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for circuit breaker functionality in enterprise environments.
 *
 * <p>This test class focuses on the {@code @CircuitBreaker} annotation and related
 * resilience patterns. Tests verify circuit state transitions, failure detection,
 * recovery mechanisms, and system stability under failure conditions.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-circuit-breaker-test",
        description = "Tests circuit breaker functionality for system resilience",
        tags = {"aop", "circuit-breaker", "resilience", "fault-tolerance", "enterprise"},
        priority = 3,
        timeout = 25000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPCircuitBreakerTest {

    /**
     * Tests circuit breaker state transitions.
     */
    public static boolean testCircuitBreakerStateTransitions() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker state transitions test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Instantiate circuit breaker aspect directly
            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    circuitBreakerAspect,
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            CircuitBreakerService service = aopFactory.create(TestCircuitBreakerService.class);

            int failureCount = 0;
            boolean openStateObserved = false;

            for (int i = 0; i < 10; i++) {
                try {
                    String result = service.riskyOperation("test-" + i);
                    TestLogger.logInfo("aop", "Circuit breaker state transitions test: call #%d result=%s", i + 1, result);
                } catch (Exception exception) {
                    failureCount++;
                    if (exception.getMessage() != null && exception.getMessage().contains("Circuit breaker is OPEN")) {
                        openStateObserved = true;
                    }
                    TestLogger.logInfo("aop", "Circuit breaker test: caught expected failure #%d: %s", i + 1, exception.getMessage());
                }
            }

            boolean resultValid = failureCount >= 3 && openStateObserved;

            TestLogger.logInfo("aop", "Circuit breaker state transitions test: failureCount=%d, openStateObserved=%s, resultValid=%s",
                    failureCount, openStateObserved, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker state transitions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests circuit breaker recovery mechanism.
     */
    public static boolean testCircuitBreakerRecovery() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker recovery test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    circuitBreakerAspect,
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            TestCircuitBreakerService serviceImpl = new TestCircuitBreakerService();
            CircuitBreakerService service = aopService.createProxy(serviceImpl);

            // First, trigger failures to open circuit (need at least 5 calls to meet minimumNumberOfCalls)
            // Make enough calls to trigger circuit breaker opening
            for (int i = 0; i < 8; i++) {
                try {
                    service.riskyOperation("trigger-" + i);
                } catch (Exception exception) {
                    TestLogger.logInfo("aop", "Circuit breaker recovery test: triggered failure #%d", i + 1);
                }
            }

            // Wait for recovery timeout (waitDurationInOpenState = 5000ms)
            TestLogger.logInfo("aop", "Circuit breaker recovery test: waiting for recovery timeout");
            Thread.sleep(6000); // Wait longer than the 5s timeout

            // Reset the service to allow successful calls during recovery testing
            serviceImpl.resetForRecovery();

            // Test recovery - need 3 successful calls in half-open state to close circuit
            int successfulRecoveryCalls = 0;

            for (int i = 0; i < 3; i++) {
                try {
                    String result = service.riskyOperation("recovery-test-" + i);
                    successfulRecoveryCalls++;
                    TestLogger.logInfo("aop", "Circuit breaker recovery test: recovery call #%d succeeded: %s", i + 1, result);
                } catch (Exception exception) {
                    TestLogger.logInfo("aop", "Circuit breaker recovery test: recovery call #%d failed: %s", i + 1, exception.getMessage());
                }
            }

            boolean recoverySuccess = successfulRecoveryCalls == 3;

            TestLogger.logInfo("aop", "Circuit breaker recovery test: recoverySuccess=%s, successfulRecoveryCalls=%d",
                    recoverySuccess, successfulRecoveryCalls);
            return recoverySuccess;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker recovery test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing circuit breaker functionality.
     */
    public interface CircuitBreakerService {

        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 5
        )
        String riskyOperation(String input) throws Exception;

        @CircuitBreaker(
                failureCountThreshold = 2,
                waitDurationInOpenState = 3000,
                minimumNumberOfCalls = 3
        )
        String anotherRiskyOperation(String input) throws Exception;

    }

    /**
     * Test implementation for circuit breaker operations.
     */
    public static class TestCircuitBreakerService implements CircuitBreakerService {

        private final AtomicInteger callCounter = new AtomicInteger(0);
        private volatile boolean circuitShouldFail = true;

        @Override
        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 5
        )
        public String riskyOperation(String input) throws Exception {
            int currentCall = callCounter.incrementAndGet();

            // Simulate failures for circuit breaker testing
            // First 6 calls fail to ensure circuit breaker opens (need 5 minimum calls with 3 failures)
            // After circuit breaker recovery, calls should succeed
            if (circuitShouldFail && currentCall <= 6) {
                throw new RuntimeException("Simulated failure #" + currentCall);
            }

            return "Success: " + input + " (Call #" + currentCall + ")";
        }

        /**
         * Resets the circuit breaker failure simulation for recovery testing.
         */
        public void resetForRecovery() {
            this.circuitShouldFail = false;
            this.callCounter.set(0);
        }

        @Override
        @CircuitBreaker(
                failureCountThreshold = 2,
                waitDurationInOpenState = 3000,
                minimumNumberOfCalls = 3
        )
        public String anotherRiskyOperation(String input) throws Exception {
            // Simulate different failure pattern
            if (input.contains("fail")) {
                throw new RuntimeException("Deliberate failure for: " + input);
            }
            return "Alternative success: " + input;
        }

    }

}
