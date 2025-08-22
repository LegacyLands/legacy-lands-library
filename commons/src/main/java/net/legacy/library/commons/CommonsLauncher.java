package net.legacy.library.commons;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.commons.task.VirtualThreadExecutors;
import net.legacy.library.commons.task.VirtualThreadSchedulerManager;
import net.legacy.library.commons.test.CommonsTestRunner;
import net.legacy.library.foundation.test.TestExecutionUtil;

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
    private static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        VirtualThreadExecutors.initialize();
        VirtualThreadSchedulerManager.initialize();

        if (DEBUG) {
            runDebugTests();
        }
    }

    @Override
    public void onPluginDisable() {
        VirtualThreadExecutors.destroy();
        VirtualThreadSchedulerManager.destroy();
    }

    /**
     * Runs comprehensive debug tests for the commons module's core logic.
     */
    private void runDebugTests() {
        TestExecutionUtil.executeModuleTestRunner("commons", CommonsTestRunner.create());
    }

}
