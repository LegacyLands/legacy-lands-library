package net.legacy.library.grpcclient;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.foundation.test.TestExecutionUtil;
import net.legacy.library.grpcclient.test.GRPCClientTestRunner;

/**
 * gRPC client launcher.
 *
 * <p>When DEBUG mode is enabled, it runs the cluster scheduling integration tests,
 * which require two running task scheduler nodes configured as peers of each other.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@FairyLaunch
@InjectableComponent
public class GRPCClientLauncher extends Plugin {

    /**
     * Debug mode flag. When set to true, runs the cluster scheduling tests during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs the cluster scheduling integration tests.
     */
    private void runDebugTests() {
        TestExecutionUtil.executeModuleTestRunner("bukkit-grpc-client", GRPCClientTestRunner.create());
    }

}
