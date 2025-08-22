package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.AsyncSafe;
import net.legacy.library.aop.aspect.AsyncSafeAspect;
import net.legacy.library.aop.custom.CustomExecutor;
import net.legacy.library.aop.custom.CustomExecutorRegistry;
import net.legacy.library.aop.custom.CustomLockStrategy;
import net.legacy.library.aop.custom.CustomTimeoutHandler;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.util.TestLogger;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for custom AsyncSafe functionality.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
public class AOPCustomTest {

    private static final String MODULE_NAME = "aop";

    public static void runCustomTests() {
        TestLogger.logTestStart(MODULE_NAME, "Custom AsyncSafe Features Test");
        long startTime = System.currentTimeMillis();

        int totalTests = 0;
        int successCount = 0;
        int failureCount = 0;

        try {
            // Register custom components
            registerCustomComponents();

            // Initialize AOP components
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register AsyncSafeAspect
            AsyncSafeAspect asyncSafeAspect = new AsyncSafeAspect();
            aopService.registerGlobalInterceptor(asyncSafeAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            // Create proxy
            TestService service = aopFactory.enhance(new TestServiceImpl());

            // Test 1: Custom Executor
            totalTests++;
            if (testCustomExecutor(service)) {
                successCount++;
            } else {
                failureCount++;
            }

            // Test 2: Custom Lock Strategy
            totalTests++;
            if (testCustomLock(service)) {
                successCount++;
            } else {
                failureCount++;
            }

            // Test 3: Custom Timeout Handler
            totalTests++;
            if (testCustomTimeout(service)) {
                successCount++;
            } else {
                failureCount++;
            }

            // Test 4: Combined Custom Features
            totalTests++;
            if (testCombinedCustom(service)) {
                successCount++;
            } else {
                failureCount++;
            }

        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Custom tests setup failed: %s", exception.getMessage());
            failureCount++;
            totalTests++;
        }

        long endTime = System.currentTimeMillis();
        TestLogger.logStatistics(MODULE_NAME, totalTests, successCount, failureCount, endTime - startTime);

        if (failureCount == 0) {
            TestLogger.logSuccess(MODULE_NAME, "All custom AsyncSafe tests passed!");
        } else {
            TestLogger.logFailure(MODULE_NAME, "%d custom tests failed out of %d total tests", failureCount, totalTests);
        }
    }

    private static void registerCustomComponents() {
        CustomExecutorRegistry registry = CustomExecutorRegistry.getInstance();

        // Register custom executor
        registry.registerExecutor(new TestCustomExecutor());
        registry.registerExecutor(new SimpleVirtualThreadExecutor());

        // Register custom lock strategy
        registry.registerLockStrategy(new TestLockStrategy());

        // Register custom timeout handler
        registry.registerTimeoutHandler(new SimpleRetryTimeoutHandler());

        TestLogger.logInfo(MODULE_NAME, "Custom components registered successfully");
    }

    private static boolean testCustomExecutor(TestService service) {
        try {
            TestLogger.logInfo(MODULE_NAME, "Testing custom executor...");
            String result = service.customExecutorTest();

            if (result != null && result.contains("CustomThread")) {
                TestLogger.logSuccess(MODULE_NAME, "Custom executor test passed: %s", result);
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "Custom executor test failed: %s", result);
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Custom executor test threw exception: %s", exception.getMessage());
            return false;
        }
    }

    private static boolean testCustomLock(TestService service) {
        try {
            TestLogger.logInfo(MODULE_NAME, "Testing custom lock strategy...");
            String result = service.customLockTest();

            if (result != null && result.contains("Lock test passed")) {
                TestLogger.logSuccess(MODULE_NAME, "Custom lock test passed: %s", result);
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "Custom lock test failed: %s", result);
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Custom lock test threw exception: %s", exception.getMessage());
            return false;
        }
    }

    private static boolean testCustomTimeout(TestService service) {
        try {
            TestLogger.logInfo(MODULE_NAME, "Testing custom timeout handler...");
            String result = service.customTimeoutTest();

            // Should get fallback value due to timeout
            if (result != null && result.equals("timeout-fallback")) {
                TestLogger.logSuccess(MODULE_NAME, "Custom timeout test passed: %s", result);
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "Custom timeout test failed, expected 'timeout-fallback' but got: %s", result);
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Custom timeout test threw exception: %s", exception.getMessage());
            return false;
        }
    }

    private static boolean testCombinedCustom(TestService service) {
        try {
            TestLogger.logInfo(MODULE_NAME, "Testing combined custom features...");
            String result = service.combinedCustomTest();

            if (result != null && result.contains("Combined test")) {
                TestLogger.logSuccess(MODULE_NAME, "Combined custom test passed: %s", result);
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "Combined custom test failed: %s", result);
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Combined custom test threw exception: %s", exception.getMessage());
            return false;
        }
    }

    public interface TestService {

        String customExecutorTest();

        String customLockTest();

        String customTimeoutTest();

        String combinedCustomTest();

    }

    public static class TestServiceImpl implements TestService {

        private final AtomicInteger counter = new AtomicInteger(0);

        @AsyncSafe(target = AsyncSafe.ThreadType.CUSTOM,
                customExecutor = "test-executor",
                customProperties = {"threadName=CustomThread"})
        @Override
        public String customExecutorTest() {
            return "Executed on: " + Thread.currentThread().getName() +
                    ", Counter: " + counter.incrementAndGet();
        }

        @AsyncSafe(target = AsyncSafe.ThreadType.CUSTOM,
                customLockStrategy = "test-lock",
                customProperties = {"lockTimeout=5000"})
        @Override
        public String customLockTest() {
            try {
                Thread.sleep(100); // Simulate work
                return "Lock test passed: " + counter.incrementAndGet();
            } catch (InterruptedException exception) {
                return "Lock test interrupted";
            }
        }

        @AsyncSafe(target = AsyncSafe.ThreadType.CUSTOM,
                customExecutor = "test-executor",
                customTimeoutHandler = "retry-handler",
                timeout = 50L,
                customProperties = {"retryCount=2", "retryDelay=100", "fallbackValue=timeout-fallback"})
        @Override
        public String customTimeoutTest() {
            try {
                Thread.sleep(200); // Will time out
                return "Should not reach here";
            } catch (InterruptedException exception) {
                return "Interrupted";
            }
        }

        @AsyncSafe(target = AsyncSafe.ThreadType.CUSTOM,
                customExecutor = "virtual-thread-executor",
                customLockStrategy = "test-lock",
                customTimeoutHandler = "retry-handler",
                timeout = 1000L,
                customProperties = {"threadName=CombinedThread", "lockTimeout=3000", "retryCount=1"})
        @Override
        public String combinedCustomTest() {
            return "Combined test: " + Thread.currentThread().getName() +
                    ", Counter: " + counter.incrementAndGet();
        }

    }

    // Custom test executor
    public static class TestCustomExecutor implements CustomExecutor {

        @Override
        public String getName() {
            return "test-executor";
        }

        @Override
        public Object execute(AspectContext context, MethodInvocation invocation, Properties properties) throws Throwable {
            String threadName = properties.getProperty("threadName", "DefaultTestThread");

            // Return the future directly to let AsyncSafeAspect handle timeout
            return CompletableFuture.supplyAsync(() -> {
                // Rename thread for testing
                Thread.currentThread().setName(threadName);
                try {
                    return invocation.proceed();
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            });
        }

    }

    // Simple virtual thread executor for testing
    public static class SimpleVirtualThreadExecutor implements CustomExecutor {

        @Override
        public String getName() {
            return "virtual-thread-executor";
        }

        @Override
        public Object execute(AspectContext context, MethodInvocation invocation, Properties properties) throws Throwable {
            // Simple async execution for testing
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return invocation.proceed();
                } catch (Throwable throwable) {
                    throw new RuntimeException(throwable);
                }
            });
        }

    }

    // Simple timeout handler for testing
    public static class SimpleRetryTimeoutHandler implements CustomTimeoutHandler {

        @Override
        public String getName() {
            return "retry-handler";
        }

        @Override
        public Object handleTimeout(AspectContext context, CompletableFuture<?> future,
                                    long timeout, Properties properties) throws Throwable {
            if (future != null) {
                future.cancel(true);
            }

            TestLogger.logWarning("aop", "Method %s timed out after %dms",
                    context.getMethod().getName(), timeout);

            // Check for fallback value
            String fallbackValue = properties.getProperty("fallbackValue");
            if (fallbackValue != null) {
                TestLogger.logInfo("aop", "Using fallback value: %s", fallbackValue);
                return fallbackValue;
            }

            throw new java.util.concurrent.TimeoutException(
                    "Method execution timed out after " + timeout + "ms: " + context.getMethod().getName()
            );
        }

    }

    // Custom test lock strategy
    public static class TestLockStrategy implements CustomLockStrategy {

        private final java.util.concurrent.locks.ReentrantLock lock =
                new java.util.concurrent.locks.ReentrantLock();

        @Override
        public String getName() {
            return "test-lock";
        }

        @Override
        public <T> T executeWithLock(AspectContext context, Callable<T> operation, Properties properties) throws Exception {
            long lockTimeout = Long.parseLong(properties.getProperty("lockTimeout", "1000"));

            if (lock.tryLock(lockTimeout, java.util.concurrent.TimeUnit.MILLISECONDS)) {
                try {
                    TestLogger.logInfo(MODULE_NAME, "Acquired test lock for method: %s", context.getMethod().getName());
                    return operation.call();
                } finally {
                    lock.unlock();
                    TestLogger.logInfo(MODULE_NAME, "Released test lock for method: %s", context.getMethod().getName());
                }
            } else {
                throw new RuntimeException("Failed to acquire lock within " + lockTimeout + "ms");
            }
        }

        @Override
        public boolean isReentrant(AspectContext context) {
            return lock.isHeldByCurrentThread();
        }

    }

}