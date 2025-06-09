package net.legacy.library.cache;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.cache.test.CacheTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;

/**
 * The cache module launcher for the Legacy Lands Library.
 *
 * <p>This class serves as the entry point for the cache module,
 * handling plugin initialization and optional debug mode testing. When DEBUG
 * mode is enabled, it automatically runs lightweight tests to validate the
 * cache module's critical business logic.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 23:55
 */
@FairyLaunch
@InjectableComponent
public class CacheLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables focused testing
     * of the cache module's critical logic during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs focused debug tests for the cache module's critical logic.
     */
    private void runDebugTests() {
        try {
            TestLogger.logInfo("cache", "Initializing cache module test runner...");

            CacheTestRunner testRunner = CacheTestRunner.create();
            TestResultSummary result = testRunner.runTests();

            // Extract test metrics from result
            Object successCountObj = result.getMetadata().get("successCount");
            Object failureCountObj = result.getMetadata().get("failureCount");
            Object totalCountObj = result.getMetadata().get("totalCount");

            int successCount = successCountObj instanceof Integer ? (Integer) successCountObj : 0;
            int failureCount = failureCountObj instanceof Integer ? (Integer) failureCountObj : 0;
            int totalCount = totalCountObj instanceof Integer ? (Integer) totalCountObj : 0;

            if (result.isSuccess()) {
                TestLogger.logSuccess("cache",
                        String.format("All cache module tests completed successfully in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));
            } else {
                TestLogger.logFailure("cache",
                        String.format("Cache module tests completed with failures in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));

                // Log detailed failure information
                TestLogger.logInfo("cache", "Test Results Summary:");
                TestLogger.logInfo("cache", "    Passed: " + successCount + " tests");
                TestLogger.logInfo("cache", "    Failed: " + failureCount + " tests");
                TestLogger.logInfo("cache", "    Total:  " + totalCount + " tests");
                TestLogger.logInfo("cache", "    Duration: " + result.getDurationMs() + "ms");
            }
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Critical error while running cache module tests", exception);
        }
    }
}