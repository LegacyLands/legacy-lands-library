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
import net.legacy.library.mongodb.factory.MongoDBConnectionConfigFactory;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.service.LegacyPlayerDataService;
import org.bson.UuidRepresentation;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.ConcurrentMap;

/**
 * The type Player launcher.
 *
 * @author qwq-dev
 * @since 2025-1-3 14:12
 */
@FairyLaunch
@InjectableComponent
public class PlayerLauncher extends Plugin {
    // DEBUG
    public static final boolean DEBUG = false;
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

        // DEBUG
        if (DEBUG) {
            MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                    "example", "mongodb://localhost:27017/", UuidRepresentation.STANDARD
            );

            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:6379");

            List<ClassLoader> classLoader = List.of(
                    PlayerLauncher.class.getClassLoader()
            );

            LegacyPlayerDataService.of(
                    "player-data-service", mongoConfig, config,
                    Duration.ofMinutes(1), basePackages, classLoader, Duration.ofSeconds(1)
            );
        }
    }

    @Override
    public void onPluginDisable() {
        CacheServiceInterface<Cache<String, LegacyPlayerDataService>, LegacyPlayerDataService> legacyPlayerDataServices =
                LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES;
        ConcurrentMap<String, LegacyPlayerDataService> map = legacyPlayerDataServices.getCache().asMap();

        // Shut down all cache services
        map.forEach((key, value) -> {
            try {
                value.shutdown();
            } catch (InterruptedException exception) {
                Log.error("Failed to shutdown LegacyPlayerDataService: " + value.getName(), exception);
            }
        });
    }
}
