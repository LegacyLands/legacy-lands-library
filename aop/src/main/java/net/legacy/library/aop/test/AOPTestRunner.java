package net.legacy.library.aop.test;

import io.fairyproject.container.Containers;
import lombok.Getter;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Enhanced test runner for the AOP module with improved architecture and test organization.
 *
 * <p>This runner orchestrates the execution of all AOP-related tests using a modular,
 * organized approach. It automatically discovers and executes test methods from different
 * test classes, each focusing on specific aspects of AOP functionality.
 *
 * @author qwq-dev
 * @version 1.0
 * @see AOPCoreTest
 * @see AOPAsyncTest
 * @see AOPMonitoringTest
 * @see AOPAnnotationTest
 * @since 2025-06-20 18:45
 */
@TestConfiguration(
        continueOnFailure = false,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.aop.test"},
        globalTimeout = 30000,
        enableCaching = true,
        failFast = true
)
public class AOPTestRunner extends AbstractModuleTestRunner {
    private static final String MODULE_NAME = "aop";

    /**
     * Ordered list of test classes to execute.
     * Tests are ordered by dependency and importance.
     */
    private static final Class<?>[] TEST_CLASSES = {
            AOPCoreTest.class,
            AOPAsyncTest.class,
            AOPMonitoringTest.class,
            AOPAnnotationTest.class,
            AOPCustomTest.class
    };

    @Getter
    private final AOPService aopService;

    private final TestTimer timer = new TestTimer();

    public AOPTestRunner(AOPService aopService) {
        super(MODULE_NAME);
        this.aopService = aopService;
    }

    /**
     * Creates a test runner instance using dependency injection.
     *
     * @return the configured test runner
     */
    public static AOPTestRunner create() {
        try {
            AOPService aopService = Containers.get(AOPService.class);
            return new AOPTestRunner(aopService);
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Failed to create AOPTestRunner: %s", exception.getMessage());
            throw new RuntimeException("Failed to create test runner", exception);
        }
    }

    @Override
    protected void beforeTests() {
        TestLogger.logTestStart(MODULE_NAME, "aop-tests");

        // Initialize timer
        timer.startTimer("total-execution");
        timer.startTimer("setup");
    }

    @Override
    protected void executeTests() {
        timer.stopTimer("setup");
        timer.startTimer("test-execution");

        TestLogger.logInfo(MODULE_NAME, "Starting comprehensive AOP test suite...");

        for (Class<?> testClass : TEST_CLASSES) {
            TestLogger.logInfo(MODULE_NAME, "Executing test class: %s", testClass.getSimpleName());

            // Special handling for AOPCustomTest
            if (testClass == AOPCustomTest.class) {
                context.incrementProcessed();
                executeCustomTests();
            } else {
                List<String> testMethods = discoverTestMethods(testClass);
                TestLogger.logInfo(MODULE_NAME, "Found %d test methods in %s", testMethods.size(), testClass.getSimpleName());

                for (String methodName : testMethods) {
                    context.incrementProcessed();
                    executeTestMethod(testClass, methodName);
                }
            }
        }

        timer.stopTimer("test-execution");

        // Log comprehensive statistics using foundation's TestLogger
        TestLogger.logStatistics(MODULE_NAME,
                context.getProcessedCount().get(),
                context.getSuccessCount().get(),
                context.getFailureCount().get(),
                context.getElapsedTime());

        // Check if any tests failed
        if (context.getFailureCount().get() > 0) {
            throw new AssertionError(
                    String.format("AOP test suite failed: %d/%d tests failed",
                            context.getFailureCount().get(), context.getProcessedCount().get())
            );
        } else {
            TestLogger.logSuccess(MODULE_NAME, "All AOP tests passed successfully!");
        }
    }

    @Override
    protected void afterTests() {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "aop-tests",
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Execute a single static test method.
     */
    private void executeTestMethod(Class<?> testClass, String methodName) {
        timer.startTimer(methodName);

        try {
            Method testMethod = testClass.getMethod(methodName);
            Boolean result = (Boolean) testMethod.invoke(null);

            if (result != null && result) {
                TestLogger.logValidation(MODULE_NAME, methodName, true, "Test completed successfully");
                context.incrementSuccess();
            } else {
                TestLogger.logValidation(MODULE_NAME, methodName, false, "Test failed - returned false");
                context.incrementFailure();
            }

        } catch (Exception exception) {
            TestLogger.logValidation(MODULE_NAME, methodName, false, "Test failed with exception: %s", exception.getMessage());
            context.incrementFailure();

        } finally {
            timer.stopTimer(methodName);
        }
    }

    /**
     * Discovers test methods in a test class using reflection.
     *
     * @param testClass the test class to scan
     * @return list of test method names
     */
    private List<String> discoverTestMethods(Class<?> testClass) {
        List<String> testMethods = new ArrayList<>();

        for (Method method : testClass.getDeclaredMethods()) {
            if (isTestMethod(method)) {
                testMethods.add(method.getName());
            }
        }

        return testMethods;
    }

    /**
     * Checks if a method qualifies as a test method.
     *
     * @param method the method to check
     * @return true if the method is a valid test method
     */
    private boolean isTestMethod(Method method) {
        return method.getName().startsWith("test") &&
                method.getReturnType() == boolean.class &&
                method.getParameterCount() == 0 &&
                java.lang.reflect.Modifier.isStatic(method.getModifiers()) &&
                java.lang.reflect.Modifier.isPublic(method.getModifiers());
    }
    
    /**
     * Executes custom tests from AOPCustomTest.
     */
    private void executeCustomTests() {
        timer.startTimer("custom-tests");
        
        try {
            TestLogger.logInfo(MODULE_NAME, "Executing custom AsyncSafe tests...");
            AOPCustomTest.runCustomTests();
            
            TestLogger.logValidation(MODULE_NAME, "custom-tests", true, "Custom tests completed successfully");
            context.incrementSuccess();
            
        } catch (Exception exception) {
            TestLogger.logValidation(MODULE_NAME, "custom-tests", false, "Custom tests failed: %s", exception.getMessage());
            context.incrementFailure();
            
        } finally {
            timer.stopTimer("custom-tests");
        }
    }

    @Override
    protected TestResultSummary generateSuccessResult(long duration) {
        // Check if there were any test failures
        int failureCount = context.getFailureCount().get();
        int successCount = context.getSuccessCount().get();
        int totalCount = context.getProcessedCount().get();

        boolean actualSuccess = failureCount == 0;
        String message;

        if (actualSuccess) {
            message = "All AOP tests passed successfully";
        } else {
            message = String.format("AOP tests completed with %d failures out of %d tests",
                    failureCount, totalCount);
        }

        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(actualSuccess)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("successCount", successCount)
                .withMetadata("failureCount", failureCount)
                .withMetadata("totalCount", totalCount);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = String.format("AOP tests failed: %s", exception.getMessage());
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(false)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage());
    }
}