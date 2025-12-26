package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.Retry;
import net.legacy.library.aop.aspect.RetryAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for retry functionality in enterprise environments.
 *
 * <p>This test class focuses on the {@code @Retry} annotation and related
 * retry mechanisms. Tests verify retry strategies, exponential backoff,
 * maximum retry limits, and retry condition evaluation.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-retry-test",
        description = "Tests retry functionality for transient failure handling",
        tags = {"aop", "retry", "resilience", "transient-failures", "enterprise"},
        priority = 3,
        timeout = 20000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPRetryTest {

    /**
     * Tests retry mechanism for eventually successful operations.
     */
    public static boolean testEventualSuccessRetry() {
        try {
            TestLogger.logInfo("aop", "Starting eventual success retry test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create retry aspect instance
            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    retryAspect,
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the retry test class
            aopService.registerTestInterceptors(TestRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RetryService service = aopFactory.create(TestRetryService.class);

            String result = service.flakyOperation("retry-test");

            boolean resultValid = result != null && result.contains("Flaky operation succeeded") && result.contains("Attempt #3");

            TestLogger.logInfo("aop", "Eventual success retry test: resultValid=%s, result=%s", resultValid, result);

            // Additional debugging
            if (!resultValid) {
                TestLogger.logInfo("aop", "Expected result to contain 'Flaky operation succeeded' and 'Attempt #3'");
                if (result != null) {
                    TestLogger.logInfo("aop", "Actual result: %s", result);
                } else {
                    TestLogger.logInfo("aop", "Result was null");
                }
            }

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Eventual success retry test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests maximum retry limit enforcement.
     */
    public static boolean testMaxRetryLimit() {
        try {
            TestLogger.logInfo("aop", "Starting max retry limit test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create retry aspect instance
            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    retryAspect,
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the retry test class
            aopService.registerTestInterceptors(TestRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RetryService service = aopFactory.create(TestRetryService.class);

            try {
                service.alwaysFailingOperation("limit-test");
                TestLogger.logFailure("aop", "Max retry limit test: expected exception after max retries");
                return false;
            } catch (RuntimeException expected) {
                boolean resultValid = expected.getMessage().contains("always fails");
                TestLogger.logInfo("aop", "Max retry limit test: caught expected exception after max retries: %s", expected.getMessage());
                return resultValid;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Max retry limit test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that fallback methods are executed when retries are exhausted.
     */
    public static boolean testFallbackInvocation() {
        try {
            TestLogger.logInfo("aop", "Starting retry fallback test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RetryService service = aopFactory.create(TestRetryService.class);

            String result = service.failingWithFallback("fallback");

            boolean valid = result != null && result.contains("Fallback invoked") && result.contains("fallback");
            TestLogger.logInfo("aop", "Retry fallback test: result=%s", result);
            return valid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry fallback test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests asynchronous retry handling with fallback support.
     */
    public static boolean testAsyncFallbackInvocation() {
        try {
            TestLogger.logInfo("aop", "Starting async retry fallback test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RetryService service = aopFactory.create(TestRetryService.class);

            CompletableFuture<String> future = service.asyncOperation("async");
            String result = future.join();

            boolean valid = result != null && result.contains("Async fallback for async");
            TestLogger.logInfo("aop", "Async retry fallback test: result=%s", result);
            return valid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Async retry fallback test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry backoff mechanism.
     */
    public static boolean testRetryBackoff() {
        try {
            TestLogger.logInfo("aop", "Starting retry backoff test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Get retry aspect from registry
            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    retryAspect,
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the retry test class
            aopService.registerTestInterceptors(TestRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RetryService service = aopFactory.create(TestRetryService.class);

            long startTime = System.currentTimeMillis();
            try {
                // This should eventually succeed after 4 failures with backoff delays
                String result = service.eventuallySuccessfulOperation("backoff-test");
                long duration = System.currentTimeMillis() - startTime;

                // Should take at least 500ms + 750ms + 1125ms + 1687.5ms = ~4062ms for 4 failures
                // The operation should succeed on the 5th attempt
                boolean resultValid = result != null && result.contains("Eventually succeeded") && duration >= 3000;

                TestLogger.logInfo("aop", "Retry backoff test: duration=%dms, result=%s, resultValid=%s",
                        duration, result, resultValid);
                return resultValid;
            } catch (RuntimeException exception) {
                long duration = System.currentTimeMillis() - startTime;
                TestLogger.logFailure("aop", "Retry backoff test: unexpected failure after %dms: %s", duration, exception.getMessage());
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry backoff test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with fixed backoff strategy.
     */
    public static boolean testRetryFixedBackoff() {
        try {
            TestLogger.logInfo("aop", "Starting retry fixed backoff test");

            // Reset counters before test
            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            long startTime = System.currentTimeMillis();
            String result = service.fixedBackoffOperation("fixed-test");
            long duration = System.currentTimeMillis() - startTime;

            // Fixed backoff: 3 failures with 200ms delay each = ~600ms minimum
            boolean resultValid = result != null && result.contains("Fixed backoff succeeded") && duration >= 500;

            TestLogger.logInfo("aop", "Retry fixed backoff test: duration=%dms, result=%s, resultValid=%s",
                    duration, result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry fixed backoff test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with linear backoff strategy.
     */
    public static boolean testRetryLinearBackoff() {
        try {
            TestLogger.logInfo("aop", "Starting retry linear backoff test");

            // Reset counters before test
            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            long startTime = System.currentTimeMillis();
            String result = service.linearBackoffOperation("linear-test");
            long duration = System.currentTimeMillis() - startTime;

            // Linear backoff: 100ms + 200ms + 300ms = 600ms minimum for 3 failures
            boolean resultValid = result != null && result.contains("Linear backoff succeeded") && duration >= 500;

            TestLogger.logInfo("aop", "Retry linear backoff test: duration=%dms, result=%s, resultValid=%s",
                    duration, result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry linear backoff test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry only on specific exception types.
     */
    public static boolean testRetryOnSpecificExceptions() {
        try {
            TestLogger.logInfo("aop", "Starting retry on specific exceptions test");

            // Reset counters before test
            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            // Test with IOException - should retry (retryOn = {IOException.class})
            boolean ioExceptionRetried = false;
            try {
                service.retryOnIOExceptionOperation("io-test");
            } catch (Exception exception) {
                // Should have retried maxAttempts times (4 attempts)
                int ioAttempts = TestAdvancedRetryService.getIoExceptionCount();
                ioExceptionRetried = ioAttempts == 4;
                TestLogger.logInfo("aop", "IOException test: attempts=%d, expected=4, message=%s",
                        ioAttempts, exception.getMessage());
            }

            // Test with IllegalArgumentException - should NOT retry (not in retryOn list)
            boolean illegalArgNotRetried = false;
            try {
                service.retryOnIOExceptionWithIllegalArg("illegal-test");
            } catch (IllegalArgumentException exception) {
                // Should not have retried, immediate propagation (only 1 attempt)
                int illegalAttempts = TestAdvancedRetryService.getIllegalArgCount();
                illegalArgNotRetried = illegalAttempts == 1;
                TestLogger.logInfo("aop", "IllegalArgumentException test: attempts=%d, expected=1, message=%s",
                        illegalAttempts, exception.getMessage());
            }

            boolean resultValid = ioExceptionRetried && illegalArgNotRetried;
            TestLogger.logInfo("aop", "Retry on specific exceptions test: ioExceptionRetried=%s, illegalArgNotRetried=%s, resultValid=%s",
                    ioExceptionRetried, illegalArgNotRetried, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry on specific exceptions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with ignored exceptions.
     */
    public static boolean testRetryIgnoreExceptions() {
        try {
            TestLogger.logInfo("aop", "Starting retry ignore exceptions test");

            // Reset counters before test
            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            // Call operation that throws ignored exception (IllegalStateException)
            boolean ignoredExceptionNotRetried = false;
            try {
                service.ignoreExceptionsOperation("ignore-test");
            } catch (IllegalStateException exception) {
                // Should NOT have retried because IllegalStateException is in ignore list
                // Verify only 1 attempt was made
                int ignoreAttempts = TestAdvancedRetryService.getIgnoreCount();
                ignoredExceptionNotRetried = ignoreAttempts == 1;
                TestLogger.logInfo("aop", "IgnoreExceptions test: attempts=%d, expected=1, message=%s",
                        ignoreAttempts, exception.getMessage());
            }

            TestLogger.logInfo("aop", "Retry ignore exceptions test: ignoredExceptionNotRetried=%s",
                    ignoredExceptionNotRetried);
            return ignoredExceptionNotRetried;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry ignore exceptions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with exponential jitter backoff strategy.
     */
    public static boolean testRetryExponentialJitterBackoff() {
        try {
            TestLogger.logInfo("aop", "Starting retry exponential jitter backoff test");

            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            long startTime = System.currentTimeMillis();
            String result = service.exponentialJitterBackoffOperation("jitter-test");
            long duration = System.currentTimeMillis() - startTime;

            // Exponential jitter: base delays with random jitter applied
            // Due to jitter, duration should vary but still take time
            boolean resultValid = result != null && result.contains("Exponential jitter succeeded") && duration >= 200;

            TestLogger.logInfo("aop", "Retry exponential jitter backoff test: duration=%dms, result=%s, resultValid=%s",
                    duration, result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry exponential jitter backoff test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with random backoff strategy.
     */
    public static boolean testRetryRandomBackoff() {
        try {
            TestLogger.logInfo("aop", "Starting retry random backoff test");

            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            long startTime = System.currentTimeMillis();
            String result = service.randomBackoffOperation("random-test");
            long duration = System.currentTimeMillis() - startTime;

            // Random backoff: delays between initialDelay and maxDelay
            // Should take some time but be unpredictable
            boolean resultValid = result != null && result.contains("Random backoff succeeded") && duration >= 100;

            TestLogger.logInfo("aop", "Retry random backoff test: duration=%dms, result=%s, resultValid=%s",
                    duration, result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry random backoff test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests retry with maximum delay enforcement.
     */
    public static boolean testRetryMaxDelay() {
        try {
            TestLogger.logInfo("aop", "Starting retry max delay test");

            // Reset counters before test
            TestAdvancedRetryService.resetCounters();

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RetryAspect retryAspect = new RetryAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    retryAspect,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRetryService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRetryService service = aopFactory.create(TestAdvancedRetryService.class);

            long startTime = System.currentTimeMillis();
            String result = service.maxDelayOperation("max-delay-test");
            long duration = System.currentTimeMillis() - startTime;

            // With exponential backoff and maxDelay=300ms:
            // Delays would be: 100, 200, 300 (capped), 300 (capped) = 900ms
            // But since max delay caps at 300ms, total should be reasonable
            boolean resultValid = result != null && result.contains("Max delay succeeded") && duration < 3000;

            TestLogger.logInfo("aop", "Retry max delay test: duration=%dms, result=%s, resultValid=%s",
                    duration, result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Retry max delay test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing retry functionality.
     */
    public interface RetryService {

        @Retry
        String flakyOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 5,
                initialDelay = 500,
                backoffMultiplier = 1.5
        )
        String eventuallySuccessfulOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 2,
                initialDelay = 100
        )
        void alwaysFailingOperation(String input) throws Exception;

        @Retry(
                initialDelay = 200,
                fallbackMethod = "fallbackOperation",
                includeExceptionInFallback = true
        )
        String failingWithFallback(String input);

        @Retry(
                maxAttempts = 2,
                initialDelay = 100,
                fallbackMethod = "asyncFallback",
                includeExceptionInFallback = true
        )
        CompletableFuture<String> asyncOperation(String input);

    }

    /**
     * Advanced retry service interface for additional backoff strategy testing.
     */
    public interface AdvancedRetryService {

        @Retry(
                maxAttempts = 5,
                initialDelay = 200,
                backoffStrategy = Retry.BackoffStrategy.FIXED
        )
        String fixedBackoffOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                backoffStrategy = Retry.BackoffStrategy.LINEAR
        )
        String linearBackoffOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                retryOn = {java.io.IOException.class}
        )
        String retryOnIOExceptionOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                retryOn = {java.io.IOException.class}
        )
        String retryOnIOExceptionWithIllegalArg(String input) throws Exception;

        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                ignoreExceptions = {IllegalStateException.class}
        )
        String ignoreExceptionsOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                maxDelay = 300,
                backoffStrategy = Retry.BackoffStrategy.EXPONENTIAL
        )
        String maxDelayOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                jitterFactor = 0.5,
                backoffStrategy = Retry.BackoffStrategy.EXPONENTIAL_JITTER
        )
        String exponentialJitterBackoffOperation(String input) throws Exception;

        @Retry(
                maxAttempts = 5,
                initialDelay = 50,
                maxDelay = 200,
                backoffStrategy = Retry.BackoffStrategy.RANDOM
        )
        String randomBackoffOperation(String input) throws Exception;

    }

    /**
     * Test implementation for retry operations.
     */
    public static class TestRetryService implements RetryService {

        private final AtomicInteger flakyCounter = new AtomicInteger(0);
        private final AtomicInteger eventuallyCounter = new AtomicInteger(0);

        @Override
        @Retry
        public String flakyOperation(String input) throws Exception {
            int attempt = flakyCounter.incrementAndGet();

            if (attempt <= 2) {
                throw new RuntimeException("Flaky operation failed on attempt #" + attempt);
            }

            return "Flaky operation succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 500,
                backoffMultiplier = 1.5
        )
        public String eventuallySuccessfulOperation(String input) throws Exception {
            int attempt = eventuallyCounter.incrementAndGet();

            if (attempt <= 4) {
                throw new RuntimeException("Eventually successful failed on attempt #" + attempt);
            }

            return "Eventually succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 2,
                initialDelay = 100
        )
        public void alwaysFailingOperation(String input) throws Exception {
            throw new RuntimeException("This operation always fails: " + input);
        }

        @Override
        @Retry(
                initialDelay = 200,
                fallbackMethod = "fallbackOperation",
                includeExceptionInFallback = true
        )
        public String failingWithFallback(String input) {
            throw new IllegalStateException("Primary operation failed for " + input);
        }

        public String fallbackOperation(String input, Throwable throwable) {
            return "Fallback invoked for " + input + " due to " + throwable.getClass().getSimpleName();
        }

        @Override
        @Retry(
                maxAttempts = 2,
                initialDelay = 100,
                fallbackMethod = "asyncFallback",
                includeExceptionInFallback = true
        )
        public CompletableFuture<String> asyncOperation(String input) {
            CompletableFuture<String> failed = new CompletableFuture<>();
            failed.completeExceptionally(new IllegalStateException("Async failure for " + input));
            return failed;
        }

        public CompletableFuture<String> asyncFallback(String input, Throwable throwable) {
            return CompletableFuture.completedFuture("Async fallback for " + input + " after " + throwable.getClass().getSimpleName());
        }

    }

    /**
     * Test implementation for advanced retry operations.
     */
    public static class TestAdvancedRetryService implements AdvancedRetryService {

        private static final AtomicInteger fixedCounter = new AtomicInteger(0);
        private static final AtomicInteger linearCounter = new AtomicInteger(0);
        private static final AtomicInteger ioExceptionCounter = new AtomicInteger(0);
        private static final AtomicInteger illegalArgCounter = new AtomicInteger(0);
        private static final AtomicInteger ignoreCounter = new AtomicInteger(0);
        private static final AtomicInteger maxDelayCounter = new AtomicInteger(0);
        private static final AtomicInteger exponentialJitterCounter = new AtomicInteger(0);
        private static final AtomicInteger randomCounter = new AtomicInteger(0);

        /**
         * Resets all counters for fresh test execution.
         */
        public static void resetCounters() {
            fixedCounter.set(0);
            linearCounter.set(0);
            ioExceptionCounter.set(0);
            illegalArgCounter.set(0);
            ignoreCounter.set(0);
            maxDelayCounter.set(0);
            exponentialJitterCounter.set(0);
            randomCounter.set(0);
        }

        /**
         * Gets the IO exception counter value.
         */
        public static int getIoExceptionCount() {
            return ioExceptionCounter.get();
        }

        /**
         * Gets the illegal argument counter value.
         */
        public static int getIllegalArgCount() {
            return illegalArgCounter.get();
        }

        /**
         * Gets the ignore counter value.
         */
        public static int getIgnoreCount() {
            return ignoreCounter.get();
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 200,
                backoffStrategy = Retry.BackoffStrategy.FIXED
        )
        public String fixedBackoffOperation(String input) throws Exception {
            int attempt = fixedCounter.incrementAndGet();
            if (attempt <= 3) {
                throw new RuntimeException("Fixed backoff failed on attempt #" + attempt);
            }
            return "Fixed backoff succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                backoffStrategy = Retry.BackoffStrategy.LINEAR
        )
        public String linearBackoffOperation(String input) throws Exception {
            int attempt = linearCounter.incrementAndGet();
            if (attempt <= 3) {
                throw new RuntimeException("Linear backoff failed on attempt #" + attempt);
            }
            return "Linear backoff succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                retryOn = {java.io.IOException.class}
        )
        public String retryOnIOExceptionOperation(String input) throws Exception {
            int attempt = ioExceptionCounter.incrementAndGet();
            throw new java.io.IOException("IO exception on attempt #" + attempt);
        }

        @Override
        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                retryOn = {java.io.IOException.class}
        )
        public String retryOnIOExceptionWithIllegalArg(String input) throws Exception {
            int attempt = illegalArgCounter.incrementAndGet();
            // Throw IllegalArgumentException which is NOT in retryOn list
            throw new IllegalArgumentException("Illegal argument on attempt #" + attempt);
        }

        @Override
        @Retry(
                maxAttempts = 4,
                initialDelay = 50,
                ignoreExceptions = {IllegalStateException.class}
        )
        public String ignoreExceptionsOperation(String input) throws Exception {
            int attempt = ignoreCounter.incrementAndGet();
            throw new IllegalStateException("Ignored exception on attempt #" + attempt);
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                maxDelay = 300,
                backoffStrategy = Retry.BackoffStrategy.EXPONENTIAL
        )
        public String maxDelayOperation(String input) throws Exception {
            int attempt = maxDelayCounter.incrementAndGet();
            if (attempt <= 4) {
                throw new RuntimeException("Max delay failed on attempt #" + attempt);
            }
            return "Max delay succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 100,
                jitterFactor = 0.5,
                backoffStrategy = Retry.BackoffStrategy.EXPONENTIAL_JITTER
        )
        public String exponentialJitterBackoffOperation(String input) throws Exception {
            int attempt = exponentialJitterCounter.incrementAndGet();
            if (attempt <= 3) {
                throw new RuntimeException("Exponential jitter failed on attempt #" + attempt);
            }
            return "Exponential jitter succeeded: " + input + " (Attempt #" + attempt + ")";
        }

        @Override
        @Retry(
                maxAttempts = 5,
                initialDelay = 50,
                maxDelay = 200,
                backoffStrategy = Retry.BackoffStrategy.RANDOM
        )
        public String randomBackoffOperation(String input) throws Exception {
            int attempt = randomCounter.incrementAndGet();
            if (attempt <= 3) {
                throw new RuntimeException("Random backoff failed on attempt #" + attempt);
            }
            return "Random backoff succeeded: " + input + " (Attempt #" + attempt + ")";
        }

    }

}
