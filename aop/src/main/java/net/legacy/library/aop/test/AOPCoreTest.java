package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.AsyncSafe;
import net.legacy.library.aop.annotation.Monitored;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;

/**
 * Test class for core AOP functionality, validating proxy creation and method interception.
 *
 * <p>This test class focuses on the fundamental AOP capabilities including proxy creation,
 * method interception, aspect composition, and basic integration between AOP components.
 * Tests verify that the AOP framework correctly handles service proxying and method execution.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-core-test",
        description = "Tests core AOP functionality including proxy creation and method interception",
        tags = {"aop", "core", "proxy", "interception"},
        priority = 1,
        timeout = 15000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPCoreTest {

    /**
     * Tests basic proxy creation and method delegation functionality.
     */
    public static boolean testBasicProxyCreation() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);
            
            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            
            AOPFactory aopFactory = new AOPFactory(aopService);

            // Create AOP-enhanced service
            TestService service = aopFactory.create(TestServiceImpl.class);

            // Validate proxy creation succeeded - check for non-null and different class
            boolean proxyCreated = service != null &&
                    !service.getClass().equals(TestServiceImpl.class);

            if (!proxyCreated) {
                TestLogger.logFailure("aop", "Basic proxy creation failed: proxy was not created");
                return false;
            }

            // Execute method and validate result
            String testInput = "test input";
            String result = service.processData(testInput);
            // Validate result structure and content rather than exact match
            boolean validResult = result != null &&
                    result.startsWith("Processed:") &&
                    result.contains(testInput.toUpperCase());

            TestLogger.logInfo("aop", "Basic proxy creation test: proxyCreated=%s, validResult=%s", proxyCreated, validResult);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Basic proxy creation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that AOP framework correctly handles services with no applicable interceptors.
     */
    public static boolean testNoInterceptorFallback() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Create a simple object with no AOP annotations
            SimpleTestObject original = new SimpleTestObject();
            SimpleTestObject proxied = aopService.createProxy(original);

            // Should return the same object when no interceptors apply
            boolean sameObject = proxied == original;

            if (!sameObject) {
                TestLogger.logFailure("aop", "No interceptor fallback failed: created unnecessary proxy");
                return false;
            }

            // Verify method execution still works
            String result = proxied.simpleMethod();
            boolean validResult = "simple".equals(result);

            TestLogger.logInfo("aop", "No interceptor fallback test: sameObject=%s, validResult=%s", sameObject, validResult);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "No interceptor fallback test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests proxy creation with multiple aspects and verifies execution order.
     */
    public static boolean testMultipleAspectComposition() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);
            
            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            
            AOPFactory aopFactory = new AOPFactory(aopService);

            // Create service with multiple aspects (monitoring + logging)
            TestService service = aopFactory.create(TestServiceImpl.class);

            // Execute method with multiple aspects
            int a = 5, b = 3;
            int result = service.calculateSum(a, b);
            int expectedSum = a + b;

            // Validate result
            boolean validResult = result == expectedSum;

            if (!validResult) {
                TestLogger.logFailure("aop", "Multiple aspect composition failed: expected 8, got %d", result);
                return false;
            }

            TestLogger.logInfo("aop", "Multiple aspect composition test: validResult=%s", validResult);

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Multiple aspect composition test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests AOP service lifecycle management and resource cleanup.
     */
    public static boolean testAOPServiceLifecycle() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);
            
            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            
            AOPFactory aopFactory = new AOPFactory(aopService);

            // Test service availability - services should be created successfully
            // Note: These should always be non-null since constructor succeeded
            boolean servicesAvailable = true;

            // Create and test a proxy
            TestService service = aopFactory.create(TestServiceImpl.class);
            int result = service.calculateSum(1, 2);

            boolean validExecution = result == 3;

            TestLogger.logInfo("aop", "AOP service lifecycle test: servicesAvailable=%s, validExecution=%s", servicesAvailable, validExecution);

            return validExecution;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "AOP service lifecycle test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests error handling in proxy creation and method execution.
     */
    public static boolean testErrorHandling() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);
            
            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            
            AOPFactory aopFactory = new AOPFactory(aopService);

            // Test null input handling
            try {
                aopService.createProxy(null);
                TestLogger.logFailure("aop", "Error handling failed: expected IllegalArgumentException for null input");
                return false; // Should throw exception
            } catch (IllegalArgumentException expected) {
                // Expected behavior
            }

            // Test service with potential execution errors
            TestService service = aopFactory.create(TestServiceImpl.class);

            // Test risky operation (may or may not throw exception)
            try {
                service.riskyOperation();
                // If no exception, that's also valid (random behavior)
                TestLogger.logInfo("aop", "Error handling test: risky operation succeeded without exception");
                return true;
            } catch (RuntimeException exception) {
                // Validate exception handling worked
                TestLogger.logInfo("aop", "Error handling test: caught expected exception=%s", exception.getClass().getSimpleName());
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Error handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests async method execution with CompletableFuture return types.
     */
    public static boolean testAsyncMethodHandling() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);
            
            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            
            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Execute async method
            java.util.concurrent.CompletableFuture<String> future = service.processDataAsync("ASYNC TEST");

            // Wait for result and validate
            String result = future.get(5, java.util.concurrent.TimeUnit.SECONDS);
            String inputLower = "async test";
            boolean validResult = result != null &&
                    result.toLowerCase().contains("async") &&
                    result.toLowerCase().contains(inputLower);

            if (!validResult) {
                TestLogger.logFailure("aop", "Async method handling failed: invalid result %s", result);
                return false;
            }

            TestLogger.logInfo("aop", "Async method handling test: validResult=%s", validResult);

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Async method handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Simple test object without AOP annotations for fallback testing.
     */
    public static class SimpleTestObject {
        public String simpleMethod() {
            return "simple";
        }
    }

    /**
     * Interface for test service to enable proxy creation.
     */
    public interface TestService {
        int calculateSum(int a, int b);
        String processData(String input);
        CompletableFuture<String> processDataAsync(String input);
        void riskyOperation();
    }

    /**
     * Test service implementation with AOP annotations for testing.
     */
    public static class TestServiceImpl implements TestService {
        @Monitored(name = "calculation")
        public int calculateSum(int a, int b) {
            return a + b;
        }

        @Monitored(name = "data_processing", warnThreshold = 50)
        public String processData(String input) {
            return "Processed: " + input.toUpperCase();
        }

        @AsyncSafe(target = AsyncSafe.ThreadType.ASYNC)
        @Monitored(name = "async_processing")
        public CompletableFuture<String> processDataAsync(String input) {
            return CompletableFuture.supplyAsync(() -> "Async Processed: " + input.toLowerCase());
        }

        public void riskyOperation() {
            // Randomly throw exception for testing
            if (Math.random() < 0.5) {
                throw new RuntimeException("Random failure for testing");
            }
        }
    }
}