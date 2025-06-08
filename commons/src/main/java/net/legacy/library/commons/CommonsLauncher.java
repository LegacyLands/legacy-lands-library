package net.legacy.library.commons;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.commons.test.CommonsTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;

/**
 * The commons module launcher for the Legacy Lands Library.
 *
 * <p>This class serves as the entry point for the commons module,
 * handling plugin initialization and optional debug mode testing. When DEBUG
 * mode is enabled, it automatically runs comprehensive tests to validate the
 * commons module's core business logic components.
 *
 * @author qwq-dev
 * @since 2024-12-23 18:32
 */
@FairyLaunch
@InjectableComponent
public class CommonsLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables comprehensive testing
     * of the commons module's core logic during plugin startup.
     */
    private static final boolean DEBUG = true;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs comprehensive debug tests for the commons module's core logic.
     */
    private void runDebugTests() {
        try {
            TestLogger.logInfo("commons", "Initializing commons module test runner...");

            CommonsTestRunner testRunner = CommonsTestRunner.create();
            TestResultSummary result = testRunner.runTests();

            // Extract test metrics from result
            Object successCountObj = result.getMetadata().get("successCount");
            Object failureCountObj = result.getMetadata().get("failureCount");
            Object totalCountObj = result.getMetadata().get("totalCount");

            int successCount = successCountObj instanceof Integer ? (Integer) successCountObj : 0;
            int failureCount = failureCountObj instanceof Integer ? (Integer) failureCountObj : 0;
            int totalCount = totalCountObj instanceof Integer ? (Integer) totalCountObj : 0;

            if (result.isSuccess()) {
                TestLogger.logSuccess("commons",
                        String.format("All commons module tests completed successfully in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));
            } else {
                TestLogger.logFailure("commons",
                        String.format("Commons module tests completed with failures in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));

                // Log detailed failure information
                TestLogger.logInfo("commons", "Test Results Summary:");
                TestLogger.logInfo("commons", "    Passed: " + successCount + " tests");
                TestLogger.logInfo("commons", "    Failed: " + failureCount + " tests");
                TestLogger.logInfo("commons", "    Total:  " + totalCount + " tests");
                TestLogger.logInfo("commons", "    Duration: " + result.getDurationMs() + "ms");
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Critical error while running commons module tests", exception);
        }
    }
}
