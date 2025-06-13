package net.legacy.library.annotation;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.annotation.test.AnnotationTestRunner;
import net.legacy.library.foundation.test.TestExecutionUtil;

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
    private static final boolean DEBUG = false;

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
        TestExecutionUtil.executeModuleTestRunner("annotation", AnnotationTestRunner.create());
    }
}
