package net.legacy.library.script;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.foundation.test.TestExecutionUtil;
import net.legacy.library.script.test.ScriptTestRunner;

/**
 * The script module launcher for the Legacy Lands Library.
 *
 * <p>This class serves as the entry point for the script engine module,
 * handling plugin initialization and optional debug mode testing. When DEBUG
 * mode is enabled, it automatically runs comprehensive tests to validate the
 * script engine framework functionality.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2025-03-12 16:47
 */
@FairyLaunch
@InjectableComponent
public class ScriptLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables comprehensive testing
     * of the script engine framework during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs comprehensive debug tests for the script engine framework.
     */
    private void runDebugTests() {
        TestExecutionUtil.executeModuleTestRunner("script", ScriptTestRunner.create());
    }
}
