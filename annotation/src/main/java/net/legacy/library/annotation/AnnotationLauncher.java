package net.legacy.library.annotation;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.annotation.test.AnnotationTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;

/**
 * The annotation module launcher for the Legacy Lands Library.
 *
 * <p>This class serves as the entry point for the annotation processing module,
 * handling plugin initialization and optional debug mode testing. When DEBUG
 * mode is enabled, it automatically runs comprehensive tests to validate the
 * annotation processing framework functionality.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2024-12-18 14:13
 */
@FairyLaunch
@InjectableComponent
public class AnnotationLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables comprehensive testing
     * of the annotation processing framework during plugin startup.
     */
    private static final boolean DEBUG = true;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs comprehensive debug tests for the annotation processing framework.
     */
    private void runDebugTests() {
        try {
            TestLogger.logInfo("annotation", "Initializing annotation processing test runner...");

            AnnotationTestRunner testRunner = AnnotationTestRunner.create();
            TestResultSummary result = testRunner.runTests();

            // Extract test metrics from result
            Object successCountObj = result.getMetadata().get("successCount");
            Object failureCountObj = result.getMetadata().get("failureCount");
            Object totalCountObj = result.getMetadata().get("totalCount");

            int successCount = successCountObj instanceof Integer ? (Integer) successCountObj : 0;
            int failureCount = failureCountObj instanceof Integer ? (Integer) failureCountObj : 0;
            int totalCount = totalCountObj instanceof Integer ? (Integer) totalCountObj : 0;

            if (result.isSuccess()) {
                TestLogger.logSuccess("annotation",
                        String.format("All annotation processing tests completed successfully in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));
            } else {
                TestLogger.logFailure("annotation",
                        String.format("Annotation processing tests completed with failures in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));

                // Log detailed failure information
                TestLogger.logInfo("annotation", "Test Results Summary:");
                TestLogger.logInfo("annotation", "    Passed: " + successCount + " tests");
                TestLogger.logInfo("annotation", "    Failed: " + failureCount + " tests");
                TestLogger.logInfo("annotation", "    Total:  " + totalCount + " tests");
                TestLogger.logInfo("annotation", "    Duration: " + result.getDurationMs() + "ms");
            }
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Critical error while running annotation processing tests", exception);
        }
    }
}
