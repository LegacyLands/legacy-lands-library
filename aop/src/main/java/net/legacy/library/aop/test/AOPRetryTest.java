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

}
