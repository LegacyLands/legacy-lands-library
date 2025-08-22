package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.AsyncSafe;
import net.legacy.library.aop.annotation.Monitored;
import net.legacy.library.aop.aspect.AsyncSafeAspect;
import net.legacy.library.aop.aspect.MonitoringAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

/**
 * Test class for AOP annotation-driven functionality.
 *
 * <p>This test class validates the annotation-driven AOP capabilities,
 * including automatic interceptor discovery and registration.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-annotation-test",
        description = "Tests AOP annotation-driven functionality",
        tags = {"aop", "annotation", "interceptor"},
        priority = 4,
        timeout = 10000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPAnnotationTest {

    /**
     * Tests basic annotation-driven AOP functionality.
     */
    public static boolean testAnnotationDrivenAOP() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register MonitoringAspect since TestServiceImpl uses @Monitored
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Test monitored method
            int result = service.calculateSum(5, 10);
            boolean validResult = result == 15;

            if (!validResult) {
                TestLogger.logFailure("aop", "Annotation-driven AOP failed: expected 15, got %d", result);
                return false;
            }

            TestLogger.logInfo("aop", "Annotation-driven AOP test: validResult=%s", validResult);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Annotation-driven AOP test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests multiple annotations on the same method.
     */
    public static boolean testMultipleAnnotations() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
            AOPService aopService = new AOPService(proxyFactory, isolationService);

            // Register both aspects since TestServiceImpl uses both @Monitored and @AsyncSafe
            MonitoringAspect monitoringAspect = new MonitoringAspect();
            AsyncSafeAspect asyncSafeAspect = new AsyncSafeAspect();
            aopService.registerGlobalInterceptor(monitoringAspect);
            aopService.registerGlobalInterceptor(asyncSafeAspect);

            AOPFactory aopFactory = new AOPFactory(aopService);

            TestService service = aopFactory.create(TestServiceImpl.class);

            // Test method with both monitoring and async annotations
            String result = service.processDataWithMultipleAnnotations("test");
            boolean validResult = "Processed: TEST".equals(result);

            if (!validResult) {
                TestLogger.logFailure("aop", "Multiple annotations test failed: expected 'Processed: TEST', got %s", result);
                return false;
            }

            TestLogger.logInfo("aop", "Multiple annotations test: validResult=%s", validResult);
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Multiple annotations test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test service interface for testing.
     */
    public interface TestService {

        int calculateSum(int a, int b);

        String processDataWithMultipleAnnotations(String input);

    }

    /**
     * Test service implementation with AOP annotations for testing.
     */
    public static class TestServiceImpl implements TestService {

        @Monitored(name = "calculation")
        public int calculateSum(int a, int b) {
            return a + b;
        }

        @Monitored(name = "data_processing")
        @AsyncSafe(target = AsyncSafe.ThreadType.SYNC)
        public String processDataWithMultipleAnnotations(String input) {
            return "Processed: " + input.toUpperCase();
        }

    }

}