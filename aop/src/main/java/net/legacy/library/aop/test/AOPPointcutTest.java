package net.legacy.library.aop.test;

import net.legacy.library.aop.interceptor.PointcutMethodInterceptor;
import net.legacy.library.aop.pointcut.Pointcut;
import net.legacy.library.aop.pointcut.PointcutExpressionParser;
import net.legacy.library.foundation.util.TestLogger;

import java.lang.reflect.Method;

/**
 * Test class for pointcut expression functionality.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:55
 */
public class AOPPointcutTest {

    private static final String MODULE_NAME = "aop";
    private static int failureCount = 0;

    /**
     * Tests pointcut expression parsing and matching.
     */
    public static void runTests() {
        TestLogger.logInfo(MODULE_NAME, "=== AOP Pointcut Expression Tests ===");
        failureCount = 0;

        testExecutionPointcut();
        testWithinPointcut();
        testAnnotationPointcut();
        testCompositePointcut();
        testPointcutInterceptors();
        testArrayTypeMatching();

        TestLogger.logInfo(MODULE_NAME, "=== AOP Pointcut Tests Completed ===");

        if (failureCount > 0) {
            throw new AssertionError(String.format("Pointcut tests failed: %d failures", failureCount));
        }
    }

    private static void testExecutionPointcut() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Execution Pointcut ---");

        PointcutExpressionParser parser = new PointcutExpressionParser();

        // Test service method matching
        Pointcut servicePointcut = parser.parse("execution(* net.legacy.library..*Service.*(..))");

        try {
            // Create test classes
            Class<?> testServiceClass = TestUserService.class;
            Class<?> testDaoClass = TestUserDao.class;

            Method serviceMethod = testServiceClass.getMethod("saveUser", String.class);
            Method daoMethod = testDaoClass.getMethod("save", String.class);

            boolean serviceMatches = servicePointcut.matches(serviceMethod, testServiceClass);
            boolean daoMatches = servicePointcut.matches(daoMethod, testDaoClass);

            TestLogger.logInfo(MODULE_NAME, "Service method matches: %s (expected: true)", serviceMatches);
            TestLogger.logInfo(MODULE_NAME, "DAO method matches: %s (expected: false)", daoMatches);

            if (serviceMatches && !daoMatches) {
                TestLogger.logSuccess(MODULE_NAME, "Execution pointcut test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Execution pointcut test failed - Service: %s, DAO: %s", serviceMatches, daoMatches);
                failureCount++;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error in execution pointcut test", exception);
            failureCount++;
        }
    }

    private static void testWithinPointcut() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Within Pointcut ---");

        PointcutExpressionParser parser = new PointcutExpressionParser();

        // Test within pattern - match inner classes
        Pointcut cachePointcut = parser.parse("within(net.legacy.library.aop.test.AOPPointcutTest$*)");

        try {
            Class<?> cacheClass = TestCacheManager.class;
            Class<?> serviceClass = TestUserService.class;

            Method cacheMethod = cacheClass.getMethod("get", String.class);
            Method serviceMethod = serviceClass.getMethod("saveUser", String.class);

            boolean cacheMatches = cachePointcut.matches(cacheMethod, cacheClass);
            boolean serviceMatches = cachePointcut.matches(serviceMethod, serviceClass);

            TestLogger.logInfo(MODULE_NAME, "Cache method matches: %s (expected: true)", cacheMatches);
            TestLogger.logInfo(MODULE_NAME, "Service method matches: %s (expected: true)", serviceMatches);  // Both are inner classes

            // Both should match since they are both inner classes of AOPPointcutTest
            if (cacheMatches && serviceMatches) {
                TestLogger.logSuccess(MODULE_NAME, "Within pointcut test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Within pointcut test failed - Cache: %s, Service: %s", cacheMatches, serviceMatches);
                failureCount++;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error in within pointcut test", exception);
            failureCount++;
        }
    }

    private static void testAnnotationPointcut() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Annotation Pointcut ---");

        PointcutExpressionParser parser = new PointcutExpressionParser();

        // Test annotation-based matching
        Pointcut loggedPointcut = parser.parse("@annotation(net.legacy.library.aop.annotation.Logged)");

        try {
            Class<?> testClass = TestAnnotatedService.class;

            Method loggedMethod = testClass.getMethod("loggedMethod");
            Method normalMethod = testClass.getMethod("normalMethod");

            boolean loggedMatches = loggedPointcut.matches(loggedMethod, testClass);
            boolean normalMatches = loggedPointcut.matches(normalMethod, testClass);

            TestLogger.logInfo(MODULE_NAME, "Logged method matches: %s (expected: true)", loggedMatches);
            TestLogger.logInfo(MODULE_NAME, "Normal method matches: %s (expected: false)", normalMatches);

            if (loggedMatches && !normalMatches) {
                TestLogger.logSuccess(MODULE_NAME, "Annotation pointcut test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Annotation pointcut test failed");
                failureCount++;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error in annotation pointcut test", exception);
            failureCount++;
        }
    }

    private static void testCompositePointcut() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Composite Pointcut ---");

        PointcutExpressionParser parser = new PointcutExpressionParser();

        // Test AND operation
        Pointcut compositeAnd = parser.parse("execution(* *Service.*(..)) && @annotation(net.legacy.library.aop.annotation.Monitored)");

        // Test OR operation
        Pointcut compositeOr = parser.parse("within(net.legacy.library.cache..*) || within(net.legacy.library.service..*)");

        TestLogger.logSuccess(MODULE_NAME, "Composite pointcut parsing successful");
    }

    private static void testPointcutInterceptors() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Pointcut Interceptors ---");

        try {
            // Test custom pointcut interceptor
            TestPointcutInterceptor testInterceptor = new TestPointcutInterceptor(
                    "execution(* net.legacy.library..*Service.*(..))"
            );

            Class<?> testServiceClass = TestUserService.class;
            Method serviceMethod = testServiceClass.getMethod("saveUser", String.class);
            Method daoMethod = TestUserDao.class.getMethod("save", String.class);

            boolean serviceSupports = testInterceptor.supports(serviceMethod);
            boolean daoSupports = testInterceptor.supports(daoMethod);

            TestLogger.logInfo(MODULE_NAME, "TestPointcutInterceptor supports service method: %s (expected: true)", serviceSupports);
            TestLogger.logInfo(MODULE_NAME, "TestPointcutInterceptor supports dao method: %s (expected: false)", daoSupports);

            // Debug: log class names
            TestLogger.logInfo(MODULE_NAME, "Service class: %s", testServiceClass.getName());
            TestLogger.logInfo(MODULE_NAME, "DAO class: %s", TestUserDao.class.getName());

            if (serviceSupports && !daoSupports) {
                TestLogger.logSuccess(MODULE_NAME, "Pointcut interceptor test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Pointcut interceptor test failed - Service: %s, DAO: %s", serviceSupports, daoSupports);
                failureCount++;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error in pointcut interceptor test", exception);
            failureCount++;
        }
    }

    private static void testArrayTypeMatching() {
        TestLogger.logInfo(MODULE_NAME, "\n--- Testing Array Type Matching ---");

        PointcutExpressionParser parser = new PointcutExpressionParser();

        // Test array return type matching
        Pointcut arrayReturnPointcut = parser.parse("execution(String[] *(..))");

        try {
            Class<?> testClass = TestArrayService.class;

            Method arrayMethod = testClass.getMethod("getNames");
            Method singleMethod = testClass.getMethod("getName");
            Method intArrayMethod = testClass.getMethod("getNumbers");

            boolean arrayMatches = arrayReturnPointcut.matches(arrayMethod, testClass);
            boolean singleMatches = arrayReturnPointcut.matches(singleMethod, testClass);
            boolean intArrayMatches = arrayReturnPointcut.matches(intArrayMethod, testClass);

            TestLogger.logInfo(MODULE_NAME, "String[] return method matches: %s (expected: true)", arrayMatches);
            TestLogger.logInfo(MODULE_NAME, "String return method matches: %s (expected: false)", singleMatches);
            TestLogger.logInfo(MODULE_NAME, "int[] return method matches: %s (expected: false)", intArrayMatches);

            if (arrayMatches && !singleMatches && !intArrayMatches) {
                TestLogger.logSuccess(MODULE_NAME, "Array type matching test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Array type matching test failed");
                failureCount++;
            }

            // Test array parameter matching
            Pointcut arrayParamPointcut = parser.parse("execution(* *(String[]))");

            Method processArrayMethod = testClass.getMethod("processNames", String[].class);
            Method processSingleMethod = testClass.getMethod("processName", String.class);

            boolean paramArrayMatches = arrayParamPointcut.matches(processArrayMethod, testClass);
            boolean paramSingleMatches = arrayParamPointcut.matches(processSingleMethod, testClass);

            TestLogger.logInfo(MODULE_NAME, "String[] parameter method matches: %s (expected: true)", paramArrayMatches);
            TestLogger.logInfo(MODULE_NAME, "String parameter method matches: %s (expected: false)", paramSingleMatches);

            if (paramArrayMatches && !paramSingleMatches) {
                TestLogger.logSuccess(MODULE_NAME, "Array parameter matching test passed");
            } else {
                TestLogger.logFailure(MODULE_NAME, "Array parameter matching test failed");
                failureCount++;
            }

        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error in array type matching test", exception);
            failureCount++;
        }
    }

    // Test classes for pointcut matching  
    public static class TestUserService {

        public void saveUser(String username) {
        }

    }

    public static class TestUserDao {

        public void save(String data) {
        }

    }

    // Test class for within pattern matching
    public static class TestCacheManager {

        public Object get(String key) {
            return null;
        }

    }

    public static class TestAnnotatedService {

        @net.legacy.library.aop.annotation.Logged
        public void loggedMethod() {
        }

        public void normalMethod() {
        }

    }

    // Test class for array type matching
    public static class TestArrayService {

        public String[] getNames() {
            return new String[0];
        }

        public String getName() {
            return "";
        }

        public int[] getNumbers() {
            return new int[0];
        }

        public void processNames(String[] names) {
        }

        public void processName(String name) {
        }

    }

    // Test interceptor implementation
    public static class TestPointcutInterceptor extends PointcutMethodInterceptor {

        public TestPointcutInterceptor(String expression) {
            super(expression);
        }

        @Override
        public Object intercept(net.legacy.library.aop.model.AspectContext context,
                                net.legacy.library.aop.interceptor.MethodInvocation invocation) throws Throwable {
            Object result = invocation.proceed();
            return result;
        }

    }

}
