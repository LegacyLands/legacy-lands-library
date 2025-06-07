package net.legacy.library.annotation;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.annotation.test.AnnotationTestRunner;

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
            Log.info("DEBUG mode enabled - Running annotation processing tests...");
            runDebugTests();
        }
    }

    /**
     * Runs comprehensive debug tests for the annotation processing framework.
     *
     * <p>This method creates and executes a test runner that validates:
     * <ul>
     *   <li>Annotation scanning functionality</li>
     *   <li>Custom processor execution</li>
     *   <li>Error handling mechanisms</li>
     *   <li>Lifecycle method invocation order</li>
     *   <li>Result validation and reporting</li>
     * </ul>
     *
     * <p>Test results are logged with detailed information about the execution,
     * including success/failure status, processing counts, and any errors encountered.
     */
    private void runDebugTests() {
        try {
            Log.info("[DEBUG] Initializing annotation processing test runner...");
            
            AnnotationTestRunner testRunner = AnnotationTestRunner.create();
            AnnotationTestRunner.TestResultSummary result = testRunner.runTests();
            
            if (result.isSuccess()) {
                Log.info("[DEBUG] ✅ All annotation processing tests completed successfully in %dms", 
                        result.getDurationMs());
                Log.info("[DEBUG] Test summary: %s", result.getMessage());
            } else {
                Log.warn("[DEBUG] ❌ Annotation processing tests failed after %dms", 
                        result.getDurationMs());
                Log.warn("[DEBUG] Failure details: %s", result.getMessage());
            }
            
        } catch (Exception exception) {
            Log.error("[DEBUG] Critical error while running annotation processing tests", exception);
        }
    }
}
