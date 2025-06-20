package net.legacy.library.foundation.util;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.util.Arrays;

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
        logInfo(moduleName, "Starting test: %s", testName);
    }

    /**
     * Logs a test completion message.
     *
     * @param moduleName the name of the module being tested
     * @param testName   the name of the test that completed
     * @param durationMs the test duration in milliseconds
     * @param replace    format arguments for testName string formatting
     */
    public static void logTestComplete(String moduleName, String testName, long durationMs, Object... replace) {
        String formattedTestName = String.format(testName, replace);
        Log.info("%s [%s] %s Test completed: %s (took %dms)", TEST_PREFIX, moduleName, "🏁", formattedTestName, durationMs);
    }

    /**
     * Logs a test success message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the success message
     * @param replace    format arguments for message string formatting
     */
    public static void logSuccess(String moduleName, String message, Object... replace) {
        String formattedMessage = String.format(message, replace);
        Log.info("%s [%s] %s %s", TEST_PREFIX, moduleName, SUCCESS_ICON, formattedMessage);
    }

    /**
     * Logs a test failure message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the failure message
     * @param replace    format arguments for message string formatting
     */
    public static void logFailure(String moduleName, String message, Object... replace) {
        String formattedMessage = String.format(message, replace);
        Log.warn("%s [%s] %s %s", TEST_PREFIX, moduleName, FAILURE_ICON, formattedMessage);
    }

    /**
     * Logs a test failure message with exception details.
     *
     * @param moduleName the name of the module being tested
     * @param message    the failure message
     * @param exception  the exception that caused the failure
     * @param replace    format arguments for message string formatting
     */
    public static void logFailure(String moduleName, String message, Exception exception, Object... replace) {
        String formattedMessage = String.format(message, replace);
        Log.error("%s [%s] %s %s", TEST_PREFIX, moduleName, FAILURE_ICON, formattedMessage, exception);
    }

    /**
     * Logs general test information.
     *
     * @param moduleName the name of the module being tested
     * @param message    the information message
     * @param replace    format arguments for message string formatting
     */
    public static void logInfo(String moduleName, String message, Object... replace) {
        String formattedMessage = String.format(message, replace);
        Log.info("%s [%s] %s %s", TEST_PREFIX, moduleName, INFO_ICON, formattedMessage);
    }

    /**
     * Logs a test warning message.
     *
     * @param moduleName the name of the module being tested
     * @param message    the warning message
     * @param replace    format arguments for message string formatting
     */
    public static void logWarning(String moduleName, String message, Object... replace) {
        String formattedMessage = String.format(message, replace);
        Log.warn("%s [%s] %s %s", TEST_PREFIX, moduleName, WARNING_ICON, formattedMessage);
    }

    /**
     * Logs test execution statistics.
     *
     * @param moduleName   the name of the module being tested
     * @param totalTests   the total number of tests
     * @param successCount the number of successful tests
     * @param failureCount the number of failed tests
     * @param durationMs   the total execution duration in milliseconds
     * @param replace      additional formatting arguments for statistics
     */
    public static void logStatistics(String moduleName, int totalTests, int successCount,
                                     int failureCount, long durationMs, Object... replace) {
        String additionalInfo = replace.length > 0 ? String.format(", %s", String.format(Arrays.toString(replace), replace)) : "";
        Log.info("%s [%s] %s Statistics: Total=%d, Success=%d, Failed=%d, Duration=%dms%s", TEST_PREFIX, moduleName,
                "📊", totalTests, successCount, failureCount, durationMs, additionalInfo);
    }

    /**
     * Logs detailed test metrics.
     *
     * @param moduleName            the name of the module being tested
     * @param processedCount        the number of processed items
     * @param averageProcessingTime the average processing time per item
     * @param throughput            the processing throughput (items per second)
     * @param replace               additional formatting arguments for metrics
     */
    public static void logMetrics(String moduleName, int processedCount,
                                  long averageProcessingTime, double throughput, Object... replace) {
        String additionalInfo = replace.length > 0 ? String.format(", %s", String.format(Arrays.toString(replace), replace)) : "";
        Log.info("%s [%s] Metrics: Processed=%d, AvgTime=%dms, Throughput=%.2f/sec%s", TEST_PREFIX, moduleName,
                processedCount, averageProcessingTime, throughput, additionalInfo);
    }

    /**
     * Logs a debug message (only if debug mode is enabled).
     *
     * @param moduleName the name of the module being tested
     * @param message    the debug message
     * @param debugMode  whether debug mode is enabled
     * @param replace    format arguments for message string formatting
     */
    public static void logDebug(String moduleName, String message, boolean debugMode, Object... replace) {
        if (debugMode) {
            String formattedMessage = String.format(message, replace);
            Log.info("%s [%s] [DEBUG] %s", TEST_PREFIX, moduleName, formattedMessage);
        }
    }

    /**
     * Logs a test validation result.
     *
     * @param moduleName  the name of the module being tested
     * @param testName    the name of the test
     * @param condition   the validation condition result
     * @param description description of what was validated
     * @param replace     format arguments for description string formatting
     */
    public static void logValidation(String moduleName, String testName,
                                     boolean condition, String description, Object... replace) {
        String icon = condition ? SUCCESS_ICON : FAILURE_ICON;
        String result = condition ? "PASSED" : "FAILED";

        String formattedDescription = String.format(description, replace);
        if (condition) {
            Log.info("%s [%s] %s %s: %s - %s", TEST_PREFIX, moduleName, icon, testName, result, formattedDescription);
        } else {
            Log.warn("%s [%s] %s %s: %s - %s", TEST_PREFIX, moduleName, icon, testName, result, formattedDescription);
        }
    }

    /**
     * Logs a formatted test result summary.
     *
     * @param moduleName the name of the module being tested
     * @param summary    the formatted summary text
     * @param replace    format arguments for summary string formatting
     */
    public static void logSummary(String moduleName, String summary, Object... replace) {
        String formattedSummary = String.format(summary, replace);
        Arrays.stream(formattedSummary.split("\n"))
                .filter(line -> !line.trim().isEmpty())
                .forEach(line -> Log.info("%s [%s] %s", TEST_PREFIX, moduleName, line.trim()));
    }
}