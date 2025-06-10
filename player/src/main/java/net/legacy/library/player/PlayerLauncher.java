package net.legacy.library.player;

import com.github.benmanes.caffeine.cache.Cache;
import io.fairyproject.FairyLaunch;
import io.fairyproject.container.Autowired;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.configuration.ConfigurationLauncher;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.test.PlayerTestRunner;

import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * The player module launcher for the Legacy Lands Library.
 *
 * <p>This class serves as the entry point for the player module,
 * handling plugin initialization and optional debug mode testing. When DEBUG
 * mode is enabled, it automatically runs comprehensive tests to validate the
 * player module's core business logic components, including data models,
 * service layers, caching mechanisms, and Redis stream processing.
 *
 * @author qwq-dev
 * @version 1.1
 * @since 2025-1-3 14:12
 */
@FairyLaunch
@InjectableComponent
public class PlayerLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables comprehensive testing
     * of the player module's core logic during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Autowired
    @SuppressWarnings("unused")
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
                "net.legacy.library.player",
                "net.legacy.library.configuration.serialize.annotation"
        );

        annotationProcessingService.processAnnotations(
                basePackages, false,
                this.getClassLoader(), ConfigurationLauncher.class.getClassLoader()
        );

        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Runs comprehensive debug tests for the player module's core logic.
     */
    private void runDebugTests() {
        try {
            TestLogger.logInfo("player", "Initializing player module test runner...");

            PlayerTestRunner testRunner = PlayerTestRunner.create();
            TestResultSummary result = testRunner.runTests();

            // Extract test metrics from result
            Object successCountObj = result.getMetadata().get("successCount");
            Object failureCountObj = result.getMetadata().get("failureCount");
            Object totalCountObj = result.getMetadata().get("totalCount");

            int successCount = successCountObj instanceof Integer ? (Integer) successCountObj : 0;
            int failureCount = failureCountObj instanceof Integer ? (Integer) failureCountObj : 0;
            int totalCount = totalCountObj instanceof Integer ? (Integer) totalCountObj : 0;

            if (result.isSuccess()) {
                TestLogger.logSuccess("player",
                        String.format("All player module tests completed successfully in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));
            } else {
                TestLogger.logFailure("player",
                        String.format("Player module tests completed with failures in %dms (Total: %d, Passed: %d, Failed: %d)",
                                result.getDurationMs(), totalCount, successCount, failureCount));

                // Log detailed failure information
                TestLogger.logInfo("player", "Test Results Summary:");
                TestLogger.logInfo("player", "    Passed: " + successCount + " tests");
                TestLogger.logInfo("player", "    Failed: " + failureCount + " tests");
                TestLogger.logInfo("player", "    Total:  " + totalCount + " tests");
                TestLogger.logInfo("player", "    Duration: " + result.getDurationMs() + "ms");
            }
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Critical error while running player module tests", exception);
        }
    }

    @Override
    public void onPluginDisable() {
        try {
            // Shut down player data services
            shutdownPlayerDataServices();

            // Shut down entity data services
            shutdownEntityDataServices();
        } catch (Exception exception) {
            Log.error("Error during Player module shutdown", exception);
        }
    }

    /**
     * Safely shutdown all LegacyPlayerDataService instances.
     */
    private void shutdownPlayerDataServices() {
        CacheServiceInterface<Cache<String, LegacyPlayerDataService>, LegacyPlayerDataService> services =
                LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES;
        ConcurrentMap<String, LegacyPlayerDataService> serviceMap = services.getResource().asMap();

        serviceMap.forEach((key, service) -> {
            try {
                service.shutdown();
            } catch (InterruptedException exception) {
                Log.error("Failed to shutdown LegacyPlayerDataService: %s", service.getName(), exception);
                Thread.currentThread().interrupt(); // Restore interrupted status
            } catch (Exception exception) {
                Log.error("Unexpected error shutting down LegacyPlayerDataService: %s", service.getName(), exception);
            }
        });
    }

    /**
     * Safely shutdown all LegacyEntityDataService instances.
     */
    private void shutdownEntityDataServices() {
        CacheServiceInterface<Cache<String, LegacyEntityDataService>, LegacyEntityDataService> services =
                LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES;
        ConcurrentMap<String, LegacyEntityDataService> serviceMap = services.getResource().asMap();

        serviceMap.forEach((key, service) -> {
            try {
                service.shutdown();
            } catch (InterruptedException exception) {
                Log.error("Failed to shutdown LegacyEntityDataService: %s", service.getName(), exception);
                Thread.currentThread().interrupt(); // Restore interrupted status
            } catch (Exception exception) {
                Log.error("Unexpected error shutting down LegacyEntityDataService: %s", service.getName(), exception);
            }
        });
    }
}
