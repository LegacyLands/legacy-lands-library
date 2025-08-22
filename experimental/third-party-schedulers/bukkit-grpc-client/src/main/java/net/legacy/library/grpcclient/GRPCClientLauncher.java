package net.legacy.library.grpcclient;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.grpcclient.task.GRPCTaskSchedulerClient;
import net.legacy.library.grpcclient.task.TaskSchedulerException;
import net.legacy.library.grpcclient.test.TaskSchedulerExample;

import java.util.concurrent.Executors;

/**
 * gRPC client launcher.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@FairyLaunch
@InjectableComponent
public class GRPCClientLauncher extends Plugin {

    // DEBUG
    public static final boolean DEBUG = false;

    @Override
    public void onPluginEnable() {
        if (DEBUG) {
            try {
                new TaskSchedulerExample().runDemonstrationLogic(new GRPCTaskSchedulerClient("localhost", 50051, 10000, 5, Executors.newCachedThreadPool()));
            } catch (TaskSchedulerException exception) {
                throw new RuntimeException(exception);
            }
        }
    }

}
