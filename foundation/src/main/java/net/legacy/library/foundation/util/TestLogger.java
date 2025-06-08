package net.legacy.library.foundation.util;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

/**
 * Standardized logging utility for module testing.
 *
 * <p>This utility class provides consistent logging methods for test execution,
 * ensuring uniform log formats across all modules. It integrates with the
 * Fairy framework's logging system while providing test-specific formatting.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
@UtilityClass
public class TestLogger {
    private static final String TEST_PREFIX = "[TEST]";
    private static final String SUCCESS_ICON = "✅";
    private static final String FAILURE_ICON = "❌";
    private static final String INFO_ICON = "ℹ️";
    private static final String WARNING_ICON = "⚠️";

    /**
     * Logs a test start message.
     *
     * @param moduleName the name of the module being tested
     * @param testName   the name of the test being started
     */
    public static void logTestStart(String moduleName, String testName) {
        Log.info("%s [%s] Starting test: %s",
                TEST_PREFIX,
                moduleName,
                testName);
    }

    /**
     * Logs a test completion message.
     *
     * @param moduleName the name of the module being tested
     * @param testName   the name of the test that completed
     * @param durationMs the test duration in milliseconds
     */
    public static void logTestComplete(String moduleName, String testName, long durationMs) {
        Log.info("%s [%s] %s Test completed: %s (took %dms)",
                TEST_PREFIX,
                moduleName,
                "🏁",
                testName,
                durationMs);
    }

    /**
     * Logs a test success message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the success message
     */
    public static void logSuccess(String moduleName, String message) {
        Log.info("%s [%s] %s %s",
                TEST_PREFIX,
                moduleName,
                SUCCESS_ICON,
                message);
    }

    /**
     * Logs a test failure message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the failure message
     */
    public static void logFailure(String moduleName, String message) {
        Log.warn("%s [%s] %s %s",
                TEST_PREFIX,
                moduleName,
                FAILURE_ICON,
                message);
    }

    /**
     * Logs a test failure message with exception details.
     *
     * @param moduleName the name of the module being tested
     * @param message    the failure message
     * @param exception  the exception that caused the failure
     */
    public static void logFailure(String moduleName, String message, Exception exception) {
        Log.error("%s [%s] %s %s",
                TEST_PREFIX,
                moduleName,
                FAILURE_ICON,
                message,
                exception);
    }

    /**
     * Logs general test information.
     *
     * @param moduleName the name of the module being tested
     * @param message    the information message
     */
    public static void logInfo(String moduleName, String message) {
        Log.info("%s [%s] %s %s",
                TEST_PREFIX,
                moduleName,
                INFO_ICON,
                message);
    }

    /**
     * Logs a test warning message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the warning message
     */
    public static void logWarning(String moduleName, String message) {
        Log.warn("%s [%s] %s %s",
                TEST_PREFIX,
                moduleName,
                WARNING_ICON,
                message);
    }

    /**
     * Logs test execution statistics.
     *
     * @param moduleName   the name of the module being tested
     * @param totalTests   the total number of tests
     * @param successCount the number of successful tests
     * @param failureCount the number of failed tests
     * @param durationMs   the total execution duration in milliseconds
     */
    public static void logStatistics(String moduleName, int totalTests, int successCount,
                                     int failureCount, long durationMs) {
        Log.info("%s [%s] %s Statistics: Total=%d, Success=%d, Failed=%d, Duration=%dms",
                TEST_PREFIX,
                moduleName,
                "📊",
                totalTests,
                successCount,
                failureCount,
                durationMs);
    }

    /**
     * Logs detailed test metrics.
     *
     * @param moduleName            the name of the module being tested
     * @param processedCount        the number of processed items
     * @param averageProcessingTime the average processing time per item
     * @param throughput            the processing throughput (items per second)
     */
    public static void logMetrics(String moduleName, int processedCount,
                                  long averageProcessingTime, double throughput) {
        Log.info("%s [%s] Metrics: Processed=%d, AvgTime=%dms, Throughput=%.2f/sec",
                TEST_PREFIX,
                moduleName,
                processedCount,
                averageProcessingTime,
                throughput);
    }

    /**
     * Logs a debug message (only if debug mode is enabled).
     *
     * @param moduleName the name of the module being tested
     * @param message    the debug message
     * @param debugMode  whether debug mode is enabled
     */
    public static void logDebug(String moduleName, String message, boolean debugMode) {
        if (debugMode) {
            Log.info("%s [%s] [DEBUG] %s",
                    TEST_PREFIX,
                    moduleName,
                    message);
        }
    }

    /**
     * Logs a test validation result.
     *
     * @param moduleName  the name of the module being tested
     * @param testName    the name of the test
     * @param condition   the validation condition result
     * @param description description of what was validated
     */
    public static void logValidation(String moduleName, String testName,
                                     boolean condition, String description) {
        String icon = condition ? SUCCESS_ICON : FAILURE_ICON;
        String result = condition ? "PASSED" : "FAILED";

        if (condition) {
            Log.info("%s [%s] %s %s: %s - %s",
                    TEST_PREFIX,
                    moduleName,
                    icon,
                    testName,
                    result,
                    description);
        } else {
            Log.warn("%s [%s] %s %s: %s - %s",
                    TEST_PREFIX,
                    moduleName,
                    icon,
                    testName,
                    result,
                    description);
        }
    }

    /**
     * Logs a formatted test result summary.
     *
     * @param moduleName the name of the module being tested
     * @param summary    the formatted summary text
     */
    public static void logSummary(String moduleName, String summary) {
        String[] lines = summary.split("\n");
        for (String line : lines) {
            if (!line.trim().isEmpty()) {
                Log.info("%s [%s] %s",
                        TEST_PREFIX,
                        moduleName,
                        line.trim());
            }
        }
    }
}