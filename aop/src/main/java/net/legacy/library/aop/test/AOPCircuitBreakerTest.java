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
     * Tests circuit breaker with fallback method.
     */
    public static boolean testCircuitBreakerFallback() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker fallback test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    circuitBreakerAspect,
                    null,
                    null,
                    null
            );

            aopService.initialize();

            TestFallbackService serviceImpl = new TestFallbackService();
            FallbackService service = aopService.createProxy(serviceImpl);

            // First, trigger failures to open circuit (need 3 failures with 3 minimum calls)
            for (int i = 0; i < 4; i++) {
                try {
                    service.operationWithFallback("trigger-" + i);
                } catch (Exception exception) {
                    TestLogger.logInfo("aop", "Circuit breaker fallback test: triggered failure #%d", i + 1);
                }
            }

            // Now the circuit should be open, next call should use fallback
            String fallbackResult = service.operationWithFallback("fallback-test");

            boolean usedFallback = fallbackResult != null && fallbackResult.contains("Fallback");
            TestLogger.logInfo("aop", "Circuit breaker fallback test: result=%s, usedFallback=%s",
                    fallbackResult, usedFallback);

            return usedFallback;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker fallback test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests circuit breaker with failure rate threshold.
     */
    public static boolean testCircuitBreakerFailureRate() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker failure rate test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    circuitBreakerAspect,
                    null,
                    null,
                    null
            );

            aopService.initialize();

            TestFailureRateService serviceImpl = new TestFailureRateService();
            FailureRateService service = aopService.createProxy(serviceImpl);

            int successCount = 0;
            int failureCount = 0;
            boolean circuitOpened = false;

            // Make 10 calls with 50% success rate, circuit should open after enough failures
            // (failureRateThreshold=0.3 means circuit opens when 30% failures occur)
            for (int i = 0; i < 10; i++) {
                try {
                    service.failureRateOperation(i % 3 == 0 ? "success" : "fail");
                    successCount++;
                } catch (Exception exception) {
                    if (exception.getMessage().contains("Circuit breaker is OPEN")) {
                        circuitOpened = true;
                    }
                    failureCount++;
                }
            }

            boolean resultValid = circuitOpened;
            TestLogger.logInfo("aop", "Circuit breaker failure rate test: successCount=%d, failureCount=%d, circuitOpened=%s, resultValid=%s",
                    successCount, failureCount, circuitOpened, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker failure rate test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests circuit breaker with ignore exceptions configuration.
     */
    public static boolean testCircuitBreakerIgnoreExceptions() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker ignore exceptions test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    circuitBreakerAspect,
                    null,
                    null,
                    null
            );

            aopService.initialize();

            TestIgnoreExceptionsService serviceImpl = new TestIgnoreExceptionsService();
            IgnoreExceptionsService service = aopService.createProxy(serviceImpl);

            int ignoredExceptions = 0;
            boolean circuitOpened = false;

            // Make calls that throw IllegalArgumentException (in ignore list)
            // These should NOT count toward circuit breaker failures
            for (int i = 0; i < 10; i++) {
                try {
                    service.operationWithIgnoredExceptions("ignored-" + i);
                } catch (IllegalArgumentException expected) {
                    ignoredExceptions++;
                } catch (Exception exception) {
                    if (exception.getMessage().contains("Circuit breaker is OPEN")) {
                        circuitOpened = true;
                    }
                }
            }

            // Circuit should NOT have opened because IllegalArgumentException is ignored
            boolean resultValid = ignoredExceptions == 10 && !circuitOpened;
            TestLogger.logInfo("aop", "Circuit breaker ignore exceptions test: ignoredExceptions=%d, circuitOpened=%s, resultValid=%s",
                    ignoredExceptions, circuitOpened, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker ignore exceptions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests circuit breaker HALF_OPEN to OPEN transition when test calls fail.
     */
    public static boolean testCircuitBreakerHalfOpenToOpen() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker HALF_OPEN to OPEN test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    circuitBreakerAspect,
                    null,
                    null,
                    null
            );

            aopService.initialize();

            TestHalfOpenTransitionService serviceImpl = new TestHalfOpenTransitionService();
            HalfOpenTransitionService service = aopService.createProxy(serviceImpl);

            // Phase 1: Trigger failures to open circuit
            TestLogger.logInfo("aop", "Phase 1: Opening circuit with failures");
            for (int i = 0; i < 5; i++) {
                try {
                    service.halfOpenTestOperation("open-" + i);
                } catch (Exception exception) {
                    TestLogger.logInfo("aop", "HALF_OPEN test: triggered failure #%d", i + 1);
                }
            }

            // Phase 2: Wait for circuit to transition to HALF_OPEN
            TestLogger.logInfo("aop", "Phase 2: Waiting for HALF_OPEN transition (3.5s)");
            Thread.sleep(3500); // Wait longer than waitDurationInOpenState (3000ms)

            // Phase 3: Make a call in HALF_OPEN state that fails - this should transition back to OPEN
            serviceImpl.setKeepFailing(true);
            boolean backToOpen = false;

            try {
                service.halfOpenTestOperation("half-open-fail");
            } catch (Exception exception) {
                TestLogger.logInfo("aop", "HALF_OPEN test: test call failed: %s", exception.getMessage());
            }

            // Phase 4: Next call should immediately fail with "Circuit breaker is OPEN"
            try {
                service.halfOpenTestOperation("should-be-open");
            } catch (Exception exception) {
                if (exception.getMessage().contains("Circuit breaker is OPEN")) {
                    backToOpen = true;
                }
                TestLogger.logInfo("aop", "HALF_OPEN test: after failing in half-open: %s", exception.getMessage());
            }

            boolean resultValid = backToOpen;
            TestLogger.logInfo("aop", "Circuit breaker HALF_OPEN to OPEN test: backToOpen=%s, resultValid=%s",
                    backToOpen, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker HALF_OPEN to OPEN test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests circuit breaker with timeout configuration.
     */
    public static boolean testCircuitBreakerTimeout() {
        try {
            TestLogger.logInfo("aop", "Starting circuit breaker timeout test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    circuitBreakerAspect,
                    null,
                    null,
                    null
            );

            aopService.initialize();

            TestTimeoutService serviceImpl = new TestTimeoutService();
            TimeoutService service = aopService.createProxy(serviceImpl);

            int timeoutCount = 0;
            boolean circuitOpened = false;
            String fallbackResult = null;

            // Make calls that will timeout (method sleeps longer than timeoutDuration)
            for (int i = 0; i < 5; i++) {
                try {
                    String result = service.slowOperationWithTimeout("timeout-" + i);
                    // If we get here with fallback result, timeout triggered fallback
                    if (result != null && result.contains("Timeout fallback")) {
                        timeoutCount++;
                        fallbackResult = result;
                    }
                } catch (Exception exception) {
                    if (exception.getMessage().contains("Circuit breaker is OPEN")) {
                        circuitOpened = true;
                    } else if (exception.getMessage().contains("timed out")) {
                        timeoutCount++;
                    }
                }
            }

            // Either timeouts occurred and triggered fallback, or circuit eventually opened
            boolean resultValid = timeoutCount > 0 || circuitOpened;
            TestLogger.logInfo("aop", "Circuit breaker timeout test: timeoutCount=%d, circuitOpened=%s, fallbackResult=%s, resultValid=%s",
                    timeoutCount, circuitOpened, fallbackResult, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Circuit breaker timeout test failed: %s", exception.getMessage());
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
     * Service interface for testing fallback functionality.
     */
    public interface FallbackService {

        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                fallbackMethod = "fallbackOperation"
        )
        String operationWithFallback(String input) throws Exception;

    }

    /**
     * Service interface for testing failure rate threshold.
     */
    public interface FailureRateService {

        @CircuitBreaker(
                failureRateThreshold = 0.3,
                failureCountThreshold = 10,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 5
        )
        String failureRateOperation(String input) throws Exception;

    }

    /**
     * Service interface for testing ignore exceptions.
     */
    public interface IgnoreExceptionsService {

        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                ignoreExceptions = {IllegalArgumentException.class}
        )
        String operationWithIgnoredExceptions(String input) throws Exception;

    }

    /**
     * Service interface for testing timeout functionality.
     */
    public interface TimeoutService {

        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                timeoutDuration = 100,
                fallbackMethod = "timeoutFallback"
        )
        String slowOperationWithTimeout(String input) throws Exception;

    }

    /**
     * Service interface for testing HALF_OPEN to OPEN transition.
     */
    public interface HalfOpenTransitionService {

        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 3000,
                minimumNumberOfCalls = 3,
                permittedNumberOfCallsInHalfOpenState = 1
        )
        String halfOpenTestOperation(String input) throws Exception;

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

    /**
     * Test implementation for fallback operations.
     */
    public static class TestFallbackService implements FallbackService {

        @Override
        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                fallbackMethod = "fallbackOperation"
        )
        public String operationWithFallback(String input) throws Exception {
            throw new RuntimeException("Primary operation failed: " + input);
        }

        public String fallbackOperation(String input) {
            return "Fallback result for: " + input;
        }

    }

    /**
     * Test implementation for failure rate threshold testing.
     */
    public static class TestFailureRateService implements FailureRateService {

        @Override
        @CircuitBreaker(
                failureRateThreshold = 0.3,
                failureCountThreshold = 10,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 5
        )
        public String failureRateOperation(String input) throws Exception {
            if (input.contains("fail")) {
                throw new RuntimeException("Simulated failure for: " + input);
            }
            return "Success: " + input;
        }

    }

    /**
     * Test implementation for ignore exceptions testing.
     */
    public static class TestIgnoreExceptionsService implements IgnoreExceptionsService {

        @Override
        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                ignoreExceptions = {IllegalArgumentException.class}
        )
        public String operationWithIgnoredExceptions(String input) throws Exception {
            // Always throw IllegalArgumentException, which should be ignored
            throw new IllegalArgumentException("Ignored exception for: " + input);
        }

    }

    /**
     * Test implementation for timeout testing.
     */
    public static class TestTimeoutService implements TimeoutService {

        @Override
        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 5000,
                minimumNumberOfCalls = 3,
                timeoutDuration = 100,
                fallbackMethod = "timeoutFallback"
        )
        public String slowOperationWithTimeout(String input) throws Exception {
            // Sleep longer than the timeout duration
            Thread.sleep(500);
            return "Slow result: " + input;
        }

        public String timeoutFallback(String input) {
            return "Timeout fallback for: " + input;
        }

    }

    /**
     * Test implementation for HALF_OPEN to OPEN transition testing.
     */
    public static class TestHalfOpenTransitionService implements HalfOpenTransitionService {

        private volatile boolean keepFailing = true;

        @Override
        @CircuitBreaker(
                failureCountThreshold = 3,
                waitDurationInOpenState = 3000,
                minimumNumberOfCalls = 3,
                permittedNumberOfCallsInHalfOpenState = 1
        )
        public String halfOpenTestOperation(String input) throws Exception {
            if (keepFailing) {
                throw new RuntimeException("Simulated failure for HALF_OPEN test: " + input);
            }
            return "Success: " + input;
        }

        public void setKeepFailing(boolean keepFailing) {
            this.keepFailing = keepFailing;
        }

    }

}
