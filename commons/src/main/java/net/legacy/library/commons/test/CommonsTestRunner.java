package net.legacy.library.commons.test;

import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Commons module test runner for core business logic components.
 *
 * <p>This test runner validates the critical business logic components in the commons module,
 * focusing on dependency injection mechanisms and task chain execution patterns. The test suite
 * ensures reliability and correctness of core infrastructure components used throughout the
 * Legacy Lands Library ecosystem.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@TestConfiguration(
        continueOnFailure = false,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.commons.test"},
        globalTimeout = 30000,
        enableCaching = true,
        failFast = true
)
public class CommonsTestRunner extends AbstractModuleTestRunner {
    private static final String MODULE_NAME = "commons";

    private final TestTimer timer = new TestTimer();
    private int totalTests = 0;
    private int passedTests = 0;
    private int failedTests = 0;

    /**
     * Create Commons module test runner
     */
    public CommonsTestRunner() {
        super(MODULE_NAME);
    }

    /**
     * Create Commons module test runner
     */
    public static CommonsTestRunner create() {
        return new CommonsTestRunner();
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "commons-module-tests");

        timer.startTimer("total-execution");
        timer.startTimer("setup");

        // Reset counters
        totalTests = 0;
        passedTests = 0;
        failedTests = 0;

        // Set random seed for test repeatability
        System.setProperty("test.random.seed", "12345");
    }

    @Override
    protected void executeTests() throws Exception {
        timer.stopTimer("setup");
        timer.startTimer("test-execution");

        TestLogger.logInfo(MODULE_NAME, "Running commons module tests...");

        // Execute VarHandle injection tests
        executeTestClass(VarHandleInjectionTest.class, "VarHandle Injection Logic");

        // Execute task chain builder tests
        executeTestClass(TaskChainBuilderTest.class, "Task Chain Builder Logic");

        // Execute random generator tests
        executeTestClass(RandomGeneratorTest.class, "Random Generator Logic");

        timer.stopTimer("test-execution");
        timer.startTimer("validation");

        // Validate overall results
        validateTestResults();

        timer.stopTimer("validation");
    }

    @Override
    protected void afterTests() throws Exception {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "commons-module-tests",
                timer.getTimerResult("total-execution").getDuration());

        // Log final statistics
        TestLogger.logStatistics(MODULE_NAME, totalTests, passedTests, failedTests,
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Execute all test methods in a test class
     */
    private void executeTestClass(Class<?> testClass, String testDescription) {
        TestLogger.logInfo(MODULE_NAME, "Executing %s tests...", testDescription);

        List<String> testMethods = getTestMethods(testClass);

        for (String methodName : testMethods) {
            executeTestMethod(testClass, methodName);
        }
    }

    /**
     * Get all static test methods from a test class
     */
    private List<String> getTestMethods(Class<?> testClass) {
        List<String> testMethods = new ArrayList<>();

        for (Method method : testClass.getDeclaredMethods()) {
            if (method.getName().startsWith("test") &&
                    method.getReturnType() == boolean.class &&
                    method.getParameterCount() == 0 &&
                    java.lang.reflect.Modifier.isStatic(method.getModifiers())) {

                testMethods.add(method.getName());
            }
        }

        return testMethods;
    }

    /**
     * Execute a single static test method
     */
    private void executeTestMethod(Class<?> testClass, String methodName) {
        totalTests++;
        timer.startTimer(methodName);

        try {
            Method method = testClass.getMethod(methodName);
            boolean result = (Boolean) method.invoke(null);

            if (result) {
                passedTests++;
                TestLogger.logValidation(MODULE_NAME, methodName, true,
                        "Test completed successfully");

                // Update context
                context.incrementProcessed();
                context.incrementSuccess();
            } else {
                failedTests++;
                TestLogger.logValidation(MODULE_NAME, methodName, false,
                        "Test failed - returned false");

                context.incrementProcessed();
                context.incrementFailure();
            }

        } catch (Exception exception) {
            failedTests++;
            TestLogger.logValidation(MODULE_NAME, methodName, false,
                    "Test failed with exception: %s", exception.getMessage());

            context.incrementProcessed();
            context.incrementFailure();

        } finally {
            timer.stopTimer(methodName);
        }
    }

    /**
     * Validate overall test results
     */
    private void validateTestResults() {
        TestLogger.logInfo(MODULE_NAME, "Validating commons module test results...");

        // Validate reasonable test count
        validateResult(totalTests >= 15, "Should have at least 15 test methods");

        // Validate all tests pass (100% success rate required)
        double successRate = totalTests > 0 ? (double) passedTests / totalTests : 0.0;
        validateResult(successRate == 1.0, "Success rate must be 100% - all tests must pass");

        // Validate critical test categories are covered
        boolean hasInjectionTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Injection") || name.contains("VarHandle"));
        boolean hasTaskChainTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Task") || name.contains("Chain"));
        boolean hasRandomGeneratorTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Random") || name.contains("Generation"));

        validateResult(hasInjectionTests, "Should include VarHandle injection tests");
        validateResult(hasTaskChainTests, "Should include task chain tests");
        validateResult(hasRandomGeneratorTests, "Should include random generator tests");

        // Log detailed validation results
        TestLogger.logValidation(MODULE_NAME, "TotalTestCount", totalTests >= 15,
                "Total tests: %d", totalTests);
        TestLogger.logValidation(MODULE_NAME, "SuccessRate", successRate == 1.0,
                "Success rate: %.1f%% (%d/%d) - 100%% required", (successRate * 100), passedTests, totalTests);
        TestLogger.logValidation(MODULE_NAME, "InjectionCoverage", hasInjectionTests,
                "VarHandle injection test coverage");
        TestLogger.logValidation(MODULE_NAME, "TaskChainCoverage", hasTaskChainTests,
                "Task chain test coverage");
        TestLogger.logValidation(MODULE_NAME, "RandomGeneratorCoverage", hasRandomGeneratorTests,
                "Random generator test coverage");

        // Add metrics to context
        context.putContextData("totalTests", totalTests);
        context.putContextData("passedTests", passedTests);
        context.putContextData("failedTests", failedTests);
        context.putContextData("successRate", successRate);

        // Add timing information
        timer.getAllResults().forEach((name, result) -> context.putContextData(name + "_duration", result.getDuration()));
    }

    @Override
    protected TestResultSummary generateSuccessResult(long duration) {
        String message = "All commons module tests passed successfully";
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(true)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("totalTests", totalTests)
                .withMetadata("passedTests", passedTests)
                .withMetadata("failedTests", failedTests)
                .withMetadata("successRate", totalTests > 0 ? (double) passedTests / totalTests : 0.0);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = "Commons module tests failed: " + exception.getMessage();
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(false)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage())
                .withMetadata("totalTests", totalTests)
                .withMetadata("passedTests", passedTests)
                .withMetadata("failedTests", failedTests);
    }
}