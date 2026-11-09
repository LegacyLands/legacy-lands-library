package net.legacy.library.configuration;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.configuration.test.ConfigurationTestRunner;
import net.legacy.library.foundation.test.TestExecutionUtil;

/**
 * The type Configuration launcher.
 *
 * <p>When DEBUG mode is enabled, it runs the configuration module's tests on startup.
 *
 * @author qwq-dev
 * @since 2024-12-18 14:44
 */
@FairyLaunch
@InjectableComponent
public class ConfigurationLauncher extends Plugin {

    /**
     * Debug mode flag. When set to true, enables the configuration module's tests during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs the configuration module's tests.
     */
    private void runDebugTests() {
        TestExecutionUtil.executeModuleTestRunner("configuration", ConfigurationTestRunner.create());
    }

}
