package net.legacy.library.foundation.test;

import lombok.experimental.UtilityClass;
import net.legacy.library.foundation.util.TestLogger;

import java.util.Map;

/**
 * Utility class for standardized test execution across modules.
 *
 * <p>This utility provides a consistent way to execute module tests with
 * standardized logging, error handling, and result formatting. It eliminates
 * code duplication across module launchers while ensuring uniform behavior.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-13 14:30
 */
@UtilityClass
public class TestExecutionUtil {

    /**
     * Executes module tests with standardized logging and error handling.
     *
     * <p>This method provides a convenient way to execute test runners that
     * extend {@link AbstractModuleTestRunner} with consistent behavior.
     *
     * @param moduleName the name of the module being tested
     * @param testRunner the test runner instance
     * @param <T>        the type of test runner extending AbstractModuleTestRunner
     */
    public static <T extends AbstractModuleTestRunner> void executeModuleTestRunner(
            String moduleName, T testRunner) {
        executeModuleTests(moduleName, testRunner::runTests);
    }

    /**
     * Executes module tests with standardized logging and error handling.
     *
     * <p>This method provides maximum flexibility by accepting any test execution
     * logic through the functional interface pattern.
     *
     * @param moduleName    the name of the module being tested
     * @param testExecution functional interface for test execution
     */
    public static void executeModuleTests(String moduleName, TestExecution testExecution) {
        try {
            TestLogger.logInfo(moduleName, "Initializing %s module test runner...", moduleName);
            logTestResults(moduleName, testExecution.execute());
        } catch (Exception exception) {
            TestLogger.logFailure(moduleName,
                    "Critical error while running %s module tests", exception, moduleName);
        }
    }

    /**
     * Logs test results with standardized formatting.
     *
     * @param moduleName the name of the module being tested
     * @param result     the test result summary
     */
    private static void logTestResults(String moduleName, TestResultSummary result) {
        TestMetrics metrics = extractTestMetrics(result);

        if (result.isSuccess()) {
            logSuccessfulTestResults(moduleName, result, metrics);
        } else {
            logFailedTestResults(moduleName, result, metrics);
        }
    }

    /**
     * Extracts test metrics from result metadata with type safety.
     *
     * @param result the test result summary
     * @return extracted test metrics
     */
    private static TestMetrics extractTestMetrics(TestResultSummary result) {
        Map<String, Object> metadata = result.getMetadata();

        int successCount = extractIntegerMetadata(metadata, "successCount");
        int failureCount = extractIntegerMetadata(metadata, "failureCount");
        int totalCount = extractIntegerMetadata(metadata, "totalCount");

        // Handle case where totalCount might not be explicitly set
        if (totalCount == 0 && (successCount > 0 || failureCount > 0)) {
            totalCount = successCount + failureCount;
        }

        return TestMetrics.builder()
                .durationMs(result.getDurationMs())
                .operationsCount(totalCount)
                .successCount(successCount)
                .failureCount(failureCount)
                .build()
                .calculate();
    }

    /**
     * Safely extracts integer metadata with default value.
     *
     * @param metadata the metadata map
     * @param key      the metadata key
     * @return the integer value or 0 if not found or not an integer
     */
    private static int extractIntegerMetadata(Map<String, Object> metadata, String key) {
        Object value = metadata.get(key);
        if (value instanceof Integer) {
            return (Integer) value;
        }
        return 0;
    }

    /**
     * Logs successful test results with detailed metrics.
     *
     * @param moduleName the name of the module being tested
     * @param result     the test result summary
     * @param metrics    the extracted test metrics
     */
    private static void logSuccessfulTestResults(String moduleName, TestResultSummary result, TestMetrics metrics) {
        TestLogger.logSuccess(moduleName,
                "All %s module tests completed successfully in %dms (Total: %d, Passed: %d, Failed: %d)",
                moduleName, result.getDurationMs(), metrics.operationsCount(),
                metrics.successCount(), metrics.failureCount());
    }

    /**
     * Logs failed test results with detailed failure information.
     *
     * @param moduleName the name of the module being tested
     * @param result     the test result summary
     * @param metrics    the extracted test metrics
     */
    private static void logFailedTestResults(String moduleName, TestResultSummary result, TestMetrics metrics) {
        TestLogger.logFailure(moduleName,
                "%s module tests completed with failures in %dms (Total: %d, Passed: %d, Failed: %d)",
                moduleName, result.getDurationMs(), metrics.operationsCount(),
                metrics.successCount(), metrics.failureCount());

        // Log detailed failure information
        logDetailedTestSummary(moduleName, metrics);
    }

    /**
     * Logs detailed test summary information.
     *
     * @param moduleName the name of the module being tested
     * @param metrics    the test metrics
     */
    private static void logDetailedTestSummary(String moduleName, TestMetrics metrics) {
        TestLogger.logInfo(moduleName, "Test Results Summary:");
        TestLogger.logInfo(moduleName, "    Passed: %d tests", metrics.successCount());
        TestLogger.logInfo(moduleName, "    Failed: %d tests", metrics.failureCount());
        TestLogger.logInfo(moduleName, "    Total:  %d tests", metrics.operationsCount());
        TestLogger.logInfo(moduleName, "    Duration: %dms", metrics.durationMs());

        // Log additional metrics if available
        if (metrics.operationsPerSecond() > 0) {
            TestLogger.logInfo(moduleName, "    Throughput: %.2f tests/sec", metrics.operationsPerSecond());
        }

        if (metrics.averageOperationDurationMs() > 0) {
            TestLogger.logInfo(moduleName, "    Avg Duration: %.2fms per test", metrics.averageOperationDurationMs());
        }

        if (metrics.getSuccessRate() > 0) {
            TestLogger.logInfo(moduleName, "    Success Rate: %.1f%%", metrics.getSuccessRate());
        }
    }

}