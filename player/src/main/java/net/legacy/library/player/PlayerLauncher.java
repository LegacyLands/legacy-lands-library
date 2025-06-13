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
import net.legacy.library.foundation.test.TestExecutionUtil;
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
    private static final boolean DEBUG = true;

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
        TestExecutionUtil.executeModuleTestRunner("player", PlayerTestRunner.create());
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
