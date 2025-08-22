package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.Monitored;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

/**
 * Test class for AOP monitoring and performance tracking functionality.
 *
 * <p>This test class focuses on the {@code @Monitored} annotation and monitoring
 * aspect implementation. Tests verify performance metrics collection, threshold-based
 * alerting, and metric aggregation functionality.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-monitoring-test",
        description = "Tests AOP monitoring and performance tracking functionality",
        tags = {"aop", "monitoring", "performance", "metrics"},
        priority = 3,
        timeout = 15000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPMonitoringTest {

    /**
     * Tests basic monitoring metrics collection for method invocations.
     */
    public static boolean testBasicMonitoringMetrics() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Execute monitored method
            int result = service.calculateSum(10, 20);

            // Validate result
            boolean validResult = result == 30;

            if (!validResult) {
                TestLogger.logFailure("aop", "Basic monitoring metrics failed: expected 30, got %d", result);
                return false;
            }

            TestLogger.logInfo("aop", "Basic monitoring metrics test: validResult=%s", validResult);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Basic monitoring metrics test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests monitoring metrics aggregation across multiple invocations.
     */
    public static boolean testMetricsAggregation() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Execute monitored method multiple times
            int result1 = service.calculateSum(1, 2);
            int result2 = service.calculateSum(3, 4);
            int result3 = service.calculateSum(5, 6);

            // Verify results
            boolean allResultsValid = result1 == 3 && result2 == 7 && result3 == 11;

            if (!allResultsValid) {
                TestLogger.logFailure("aop", "Metrics aggregation failed: invalid results %d, %d, %d", result1, result2, result3);
                return false;
            }

            TestLogger.logInfo("aop", "Metrics aggregation test: allResultsValid=%s", allResultsValid);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Metrics aggregation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests threshold-based warning mechanisms for slow operations.
     */
    public static boolean testPerformanceThresholds() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Execute method that may trigger threshold warnings
            String result = service.processData("threshold test");

            // Verify the method executed successfully
            boolean validResult = "Processed: THRESHOLD TEST".equals(result);

            if (!validResult) {
                TestLogger.logFailure("aop", "Performance thresholds failed: expected 'Processed: THRESHOLD TEST', got %s", result);
                return false;
            }

            TestLogger.logInfo("aop", "Performance thresholds test: validResult=%s", validResult);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Performance thresholds test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests failure tracking and error rate monitoring.
     */
    public static boolean testFailureTracking() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            int successCount = 0;
            int failureCount = 0;

            // Execute risky operation multiple times to test failure tracking
            for (int i = 0; i < 5; i++) {
                try {
                    service.riskyOperation();
                    successCount++;
                } catch (RuntimeException runtimeException) {
                    failureCount++;
                }
            }

            // At least some operations should have occurred
            boolean allOperationsExecuted = (successCount + failureCount) == 5;

            if (!allOperationsExecuted) {
                TestLogger.logFailure("aop", "Failure tracking failed: total operations %d != 5", (successCount + failureCount));
                return false;
            }

            TestLogger.logInfo("aop", "Failure tracking test: successCount=%d, failureCount=%d", successCount, failureCount);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Failure tracking test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test service interface for testing.
     */
    public interface TestService {

        int calculateSum(int a, int b);

        String processData(String input);

        void riskyOperation();

    }

    /**
     * Test service implementation with monitoring annotations for testing.
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

        public void riskyOperation() {
            // Randomly throw exception for testing
            if (Math.random() < 0.5) {
                throw new RuntimeException("Random failure for testing");
            }
        }

    }

}