package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.RateLimiter;
import net.legacy.library.aop.aspect.RateLimiterAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for rate limiting functionality.
 *
 * <p>This test class focuses on the {@code @RateLimiter} annotation and related
 * rate limiting mechanisms. Tests verify different rate limiting strategies,
 * key expression resolution, and fallback handling.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-12-25 15:00
 */
@ModuleTest(
        testName = "aop-rate-limiter-test",
        description = "Tests rate limiting functionality for throttling requests",
        tags = {"aop", "rate-limiter", "resilience", "throttling", "enterprise"},
        priority = 3,
        timeout = 30000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPRateLimiterTest {

    /**
     * Tests basic rate limiting with fixed window strategy.
     */
    public static boolean testFixedWindowRateLimiting() {
        try {
            TestLogger.logInfo("aop", "Starting fixed window rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RateLimitService service = aopFactory.create(TestRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // Execute 10 calls, limit is 5 per second
            for (int i = 0; i < 10; i++) {
                try {
                    service.fixedWindowOperation("test-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    failCount.incrementAndGet();
                }
            }

            boolean resultValid = successCount.get() == 5 && failCount.get() == 5;
            TestLogger.logInfo("aop", "Fixed window rate limiting test: successCount=%d, failCount=%d, resultValid=%s",
                    successCount.get(), failCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Fixed window rate limiting test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests rate limiting with key expression for per-user limiting.
     */
    public static boolean testKeyExpressionRateLimiting() {
        try {
            TestLogger.logInfo("aop", "Starting key expression rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RateLimitService service = aopFactory.create(TestRateLimitService.class);

            AtomicInteger userASuccess = new AtomicInteger(0);
            AtomicInteger userBSuccess = new AtomicInteger(0);

            // User A makes 3 calls (limit is 2)
            for (int i = 0; i < 3; i++) {
                try {
                    service.perUserOperation("userA", "data-" + i);
                    userASuccess.incrementAndGet();
                } catch (Exception exception) {
                    // Rate limited
                }
            }

            // User B makes 3 calls (separate limit)
            for (int i = 0; i < 3; i++) {
                try {
                    service.perUserOperation("userB", "data-" + i);
                    userBSuccess.incrementAndGet();
                } catch (Exception exception) {
                    // Rate limited
                }
            }

            // Each user should have 2 successful calls
            boolean resultValid = userASuccess.get() == 2 && userBSuccess.get() == 2;
            TestLogger.logInfo("aop", "Key expression rate limiting test: userASuccess=%d, userBSuccess=%d, resultValid=%s",
                    userASuccess.get(), userBSuccess.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Key expression rate limiting test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests rate limiting with fallback method.
     */
    public static boolean testRateLimitFallback() {
        try {
            TestLogger.logInfo("aop", "Starting rate limit fallback test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RateLimitService service = aopFactory.create(TestRateLimitService.class);

            String firstResult = null;
            String secondResult = null;
            String thirdResult = null;

            // First call should succeed
            firstResult = service.operationWithFallback("test1");

            // Second call should also succeed (limit is 2)
            secondResult = service.operationWithFallback("test2");

            // Third call should use fallback
            thirdResult = service.operationWithFallback("test3");

            boolean firstValid = firstResult != null && firstResult.contains("Primary result");
            boolean secondValid = secondResult != null && secondResult.contains("Primary result");
            boolean thirdValid = thirdResult != null && thirdResult.contains("Fallback result");

            boolean resultValid = firstValid && secondValid && thirdValid;
            TestLogger.logInfo("aop", "Rate limit fallback test: first=%s, second=%s, third=%s, resultValid=%s",
                    firstResult, secondResult, thirdResult, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Rate limit fallback test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent rate limiting behavior.
     */
    public static boolean testConcurrentRateLimiting() {
        ExecutorService executor = Executors.newFixedThreadPool(10);
        try {
            TestLogger.logInfo("aop", "Starting concurrent rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RateLimitService service = aopFactory.create(TestRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);
            CountDownLatch latch = new CountDownLatch(20);

            // Submit 20 concurrent requests, limit is 5
            for (int i = 0; i < 20; i++) {
                final int index = i;
                executor.submit(() -> {
                    try {
                        service.fixedWindowOperation("concurrent-" + index);
                        successCount.incrementAndGet();
                    } catch (Exception exception) {
                        failCount.incrementAndGet();
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(10, TimeUnit.SECONDS);

            // Only 5 should succeed
            boolean resultValid = successCount.get() == 5 && failCount.get() == 15;
            TestLogger.logInfo("aop", "Concurrent rate limiting test: successCount=%d, failCount=%d, resultValid=%s",
                    successCount.get(), failCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Concurrent rate limiting test failed: %s", exception.getMessage());
            return false;
        } finally {
            executor.shutdown();
        }
    }

    /**
     * Tests sliding window rate limiting strategy.
     */
    public static boolean testSlidingWindowStrategy() {
        try {
            TestLogger.logInfo("aop", "Starting sliding window rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRateLimitService service = aopFactory.create(TestAdvancedRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // Execute calls with sliding window strategy (limit=3, period=1000ms)
            for (int i = 0; i < 5; i++) {
                try {
                    service.slidingWindowOperation("test-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    failCount.incrementAndGet();
                }
            }

            boolean resultValid = successCount.get() == 3 && failCount.get() == 2;
            TestLogger.logInfo("aop", "Sliding window rate limiting test: successCount=%d, failCount=%d, resultValid=%s",
                    successCount.get(), failCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Sliding window rate limiting test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests token bucket rate limiting strategy.
     */
    public static boolean testTokenBucketStrategy() {
        try {
            TestLogger.logInfo("aop", "Starting token bucket rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRateLimitService service = aopFactory.create(TestAdvancedRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);

            // Token bucket allows burst up to limit, then refills over time
            // limit=4 means bucket holds 4 tokens initially
            for (int i = 0; i < 6; i++) {
                try {
                    service.tokenBucketOperation("token-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    // Rate limited
                }
            }

            // First 4 should succeed (burst capacity), remaining 2 should fail
            boolean resultValid = successCount.get() == 4;
            TestLogger.logInfo("aop", "Token bucket rate limiting test: successCount=%d, resultValid=%s",
                    successCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Token bucket rate limiting test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests wait for next slot functionality.
     */
    public static boolean testWaitForNextSlot() {
        try {
            TestLogger.logInfo("aop", "Starting wait for next slot test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRateLimitService service = aopFactory.create(TestAdvancedRateLimitService.class);

            long startTime = System.currentTimeMillis();
            AtomicInteger successCount = new AtomicInteger(0);

            // With waitForNextSlot=true, limit=2, period=500ms
            // First 2 calls succeed immediately
            // Third call waits for next slot
            for (int i = 0; i < 3; i++) {
                try {
                    service.waitForSlotOperation("wait-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    TestLogger.logInfo("aop", "Wait for next slot: call %d failed: %s", i, exception.getMessage());
                }
            }

            long duration = System.currentTimeMillis() - startTime;

            // Should have waited for next slot, so duration should be >= period (500ms)
            boolean resultValid = successCount.get() == 3 && duration >= 400;
            TestLogger.logInfo("aop", "Wait for next slot test: successCount=%d, duration=%dms, resultValid=%s",
                    successCount.get(), duration, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Wait for next slot test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests leaky bucket rate limiting strategy.
     */
    public static boolean testLeakyBucketStrategy() {
        try {
            TestLogger.logInfo("aop", "Starting leaky bucket rate limiting test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRateLimitService service = aopFactory.create(TestAdvancedRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger failCount = new AtomicInteger(0);

            // Leaky bucket: constant output rate, bucket has capacity
            // limit=3 means bucket holds 3 requests, period=1000ms means 1 request leaks per 333ms
            for (int i = 0; i < 5; i++) {
                try {
                    service.leakyBucketOperation("leaky-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    failCount.incrementAndGet();
                }
            }

            // First 3 should succeed (bucket capacity), remaining 2 should fail (bucket full)
            boolean resultValid = successCount.get() == 3 && failCount.get() == 2;
            TestLogger.logInfo("aop", "Leaky bucket rate limiting test: successCount=%d, failCount=%d, resultValid=%s",
                    successCount.get(), failCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Leaky bucket rate limiting test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests maximum wait time enforcement.
     */
    public static boolean testMaxWaitTime() {
        try {
            TestLogger.logInfo("aop", "Starting max wait time test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    rateLimiterAspect,
                    null
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedRateLimitService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedRateLimitService service = aopFactory.create(TestAdvancedRateLimitService.class);

            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger timeoutCount = new AtomicInteger(0);

            // With waitForNextSlot=true, limit=1, period=2000ms, maxWaitTime=500ms
            // First call succeeds, second tries to wait but exceeds maxWaitTime
            for (int i = 0; i < 3; i++) {
                try {
                    service.maxWaitTimeOperation("max-wait-" + i);
                    successCount.incrementAndGet();
                } catch (Exception exception) {
                    if (exception.getMessage().contains("timeout") ||
                            exception.getMessage().contains("exceeded") ||
                            exception.getMessage().contains("Rate limit")) {
                        timeoutCount.incrementAndGet();
                    }
                }
            }

            // First call succeeds, others should timeout waiting
            boolean resultValid = successCount.get() == 1 && timeoutCount.get() >= 1;
            TestLogger.logInfo("aop", "Max wait time test: successCount=%d, timeoutCount=%d, resultValid=%s",
                    successCount.get(), timeoutCount.get(), resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Max wait time test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing rate limiting functionality.
     */
    public interface RateLimitService {

        @RateLimiter(limit = 5, period = 1000)
        String fixedWindowOperation(String input);

        @RateLimiter(
                limit = 2,
                period = 1000,
                keyExpression = "{#arg0}"
        )
        String perUserOperation(String userId, String data);

        @RateLimiter(
                limit = 2,
                period = 1000,
                fallbackMethod = "fallbackOperation"
        )
        String operationWithFallback(String input);

    }

    /**
     * Advanced service interface for additional rate limiting strategy testing.
     */
    public interface AdvancedRateLimitService {

        @RateLimiter(
                limit = 3,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.SLIDING_WINDOW
        )
        String slidingWindowOperation(String input);

        @RateLimiter(
                limit = 4,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.TOKEN_BUCKET
        )
        String tokenBucketOperation(String input);

        @RateLimiter(
                limit = 2,
                period = 500,
                waitForNextSlot = true,
                maxWaitTime = 2000
        )
        String waitForSlotOperation(String input);

        @RateLimiter(
                limit = 1,
                period = 2000,
                waitForNextSlot = true,
                maxWaitTime = 500
        )
        String maxWaitTimeOperation(String input);

        @RateLimiter(
                limit = 3,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.LEAKY_BUCKET
        )
        String leakyBucketOperation(String input);

    }

    /**
     * Test implementation for rate limiting operations.
     */
    public static class TestRateLimitService implements RateLimitService {

        @Override
        @RateLimiter(limit = 5, period = 1000)
        public String fixedWindowOperation(String input) {
            return "Fixed window result: " + input;
        }

        @Override
        @RateLimiter(
                limit = 2,
                period = 1000,
                keyExpression = "{#arg0}"
        )
        public String perUserOperation(String userId, String data) {
            return "Per user result for " + userId + ": " + data;
        }

        @Override
        @RateLimiter(
                limit = 2,
                period = 1000,
                fallbackMethod = "fallbackOperation"
        )
        public String operationWithFallback(String input) {
            return "Primary result: " + input;
        }

        public String fallbackOperation(String input) {
            return "Fallback result: " + input;
        }

    }

    /**
     * Test implementation for advanced rate limiting operations.
     */
    public static class TestAdvancedRateLimitService implements AdvancedRateLimitService {

        @Override
        @RateLimiter(
                limit = 3,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.SLIDING_WINDOW
        )
        public String slidingWindowOperation(String input) {
            return "Sliding window result: " + input;
        }

        @Override
        @RateLimiter(
                limit = 4,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.TOKEN_BUCKET
        )
        public String tokenBucketOperation(String input) {
            return "Token bucket result: " + input;
        }

        @Override
        @RateLimiter(
                limit = 2,
                period = 500,
                waitForNextSlot = true,
                maxWaitTime = 2000
        )
        public String waitForSlotOperation(String input) {
            return "Wait for slot result: " + input;
        }

        @Override
        @RateLimiter(
                limit = 1,
                period = 2000,
                waitForNextSlot = true,
                maxWaitTime = 500
        )
        public String maxWaitTimeOperation(String input) {
            return "Max wait time result: " + input;
        }

        @Override
        @RateLimiter(
                limit = 3,
                period = 1000,
                strategy = RateLimiter.RateLimitStrategy.LEAKY_BUCKET
        )
        public String leakyBucketOperation(String input) {
            return "Leaky bucket result: " + input;
        }

    }

}
