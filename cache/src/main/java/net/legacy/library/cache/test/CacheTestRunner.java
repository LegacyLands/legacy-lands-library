package net.legacy.library.cache.test;

import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;

/**
 * Test runner for validating cache module functionality.
 *
 * <p>This class orchestrates the execution of cache module tests, focusing on
 * critical business logic areas like expiration handling, lock management,
 * and multi-level cache coordination. It provides lightweight but comprehensive
 * testing coverage for the cache module's custom logic.
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
        testPackages = {"net.legacy.library.cache.test"},
        globalTimeout = 30000,
        enableCaching = true,
        failFast = true
)
public class CacheTestRunner extends AbstractModuleTestRunner {
    private static final String MODULE_NAME = "cache";

    private final TestTimer timer = new TestTimer();
    private int totalTests = 0;
    private int passedTests = 0;
    private int failedTests = 0;

    /**
     * Creates a cache test runner instance.
     */
    public CacheTestRunner() {
        super(MODULE_NAME);
    }

    /**
     * Creates a test runner instance using static factory method.
     *
     * @return the configured test runner
     */
    public static CacheTestRunner create() {
        return new CacheTestRunner();
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "cache-module-tests");

        timer.startTimer("total-execution");
        timer.startTimer("setup");

        // Reset counters
        totalTests = 0;
        passedTests = 0;
        failedTests = 0;
    }

    @Override
    protected void executeTests() throws Exception {
        timer.stopTimer("setup");
        timer.startTimer("test-execution");

        TestLogger.logInfo(MODULE_NAME, "Running cache module tests...");

        // Execute expiration tests
        executeTestClass(CacheExpirationTest.class, "Cache Expiration Logic");

        // Execute lock management tests
        executeTestClass(LockManagementTest.class, "Lock Management Logic");

        // Execute multi-level cache tests  
        executeTestClass(MultiLevelCacheTest.class, "Multi-Level Cache Coordination");

        // Execute abstract cache service tests
        executeTestClass(AbstractCacheServiceTest.class, "Abstract Cache Service Logic");

        // Execute cache implementations tests
        executeTestClass(CacheImplementationsTest.class, "Cache Implementations Logic");

        timer.stopTimer("test-execution");
        timer.startTimer("validation");

        // Validate overall results
        validateTestResults();

        timer.stopTimer("validation");
    }

    @Override
    protected void afterTests() throws Exception {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "cache-module-tests",
                timer.getTimerResult("total-execution").getDuration());

        // Log final statistics
        TestLogger.logStatistics(MODULE_NAME, totalTests, passedTests, failedTests,
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Executes all test methods in a test class.
     */
    private void executeTestClass(Class<?> testClass, String testDescription) {
        TestLogger.logInfo(MODULE_NAME, "Executing " + testDescription + " tests...");

        List<String> testMethods = getTestMethods(testClass);

        for (String methodName : testMethods) {
            executeTestMethod(testClass, methodName);
        }
    }

    /**
     * Gets all static test methods from a test class.
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
     * Executes a single test method.
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
                    "Test failed with exception: " + exception.getMessage());

            context.incrementProcessed();
            context.incrementFailure();

        } finally {
            timer.stopTimer(methodName);
        }
    }

    /**
     * Validates the overall test results.
     */
    private void validateTestResults() {
        TestLogger.logInfo(MODULE_NAME, "Validating cache module test results...");

        // Validate that we have a reasonable number of tests
        validateResult(totalTests >= 15, "Should have at least 15 test methods");

        // Validate all tests pass (100% success rate required)
        double successRate = totalTests > 0 ? (double) passedTests / totalTests : 0.0;
        validateResult(successRate == 1.0, "Success rate must be 100% - all tests must pass");

        // Validate that critical test categories are covered
        boolean hasExpirationTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Expir") || name.contains("TTL"));
        boolean hasLockTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Lock") || name.contains("Timeout"));
        boolean hasMultiLevelTests = timer.getAllResults().keySet().stream()
                .anyMatch(name -> name.contains("Level") || name.contains("Multi"));

        validateResult(hasExpirationTests, "Should include expiration logic tests");
        validateResult(hasLockTests, "Should include lock management tests");
        validateResult(hasMultiLevelTests, "Should include multi-level coordination tests");

        // Log detailed validation results
        TestLogger.logValidation(MODULE_NAME, "TotalTestCount", totalTests >= 15,
                "Total tests: " + totalTests);
        TestLogger.logValidation(MODULE_NAME, "SuccessRate", successRate == 1.0,
                "Success rate: " + (successRate * 100) + "% (" + passedTests + "/" + totalTests + ") - 100% required");
        TestLogger.logValidation(MODULE_NAME, "ExpirationCoverage", hasExpirationTests,
                "Expiration logic test coverage");
        TestLogger.logValidation(MODULE_NAME, "LockCoverage", hasLockTests,
                "Lock management test coverage");
        TestLogger.logValidation(MODULE_NAME, "MultiLevelCoverage", hasMultiLevelTests,
                "Multi-level coordination test coverage");

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
        String message = "All cache module tests passed successfully";
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
        String message = "Cache module tests failed: " + exception.getMessage();
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