package net.legacy.library.cache;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.cache.test.CacheTestRunner;
import net.legacy.library.foundation.test.TestExecutionUtil;

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
        TestExecutionUtil.executeModuleTestRunner("cache", CacheTestRunner.create());
    }

}