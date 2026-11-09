package net.legacy.library.player.service;

import lombok.Builder;
import lombok.Getter;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.PlayerLauncher;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;

/**
 * Settings of the player and entity data services a plugin starts with {@link LegacyDataServices}.
 *
 * <p>{@link #getName()}, {@link #getMongoDBConnectionConfig()} and {@link #getRedisConfig()} are required; every other
 * setting has the default of the corresponding {@code of} method of {@link LegacyPlayerDataService} and
 * {@link LegacyEntityDataService}, so build only what differs:
 * {@code LegacyDataServiceSettings.builder().name("shop").mongoDBConnectionConfig(mongo).redisConfig(redis).build()}.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@Getter
@Builder(toBuilder = true)
public class LegacyDataServiceSettings {

    /**
     * Default interval between two scheduled persistence runs (2 hours), as {@link LegacyPlayerDataService#of}.
     */
    public static final Duration DEFAULT_AUTO_SAVE_INTERVAL = Duration.ofHours(2);

    /**
     * Default interval between two reads of the Redis stream (2 seconds), as {@link LegacyPlayerDataService#of}.
     */
    public static final Duration DEFAULT_REDIS_STREAM_ACCEPT_INTERVAL = Duration.ofSeconds(2);

    /**
     * The name the services are named after: {@code <name>-player-data-service} and
     * {@code <name>-entity-data-service}. Service names are part of the keys the services use in Redis, so keep a
     * name once data exists under it.
     */
    private final String name;

    /**
     * The MongoDB connection the services persist through.
     */
    private final MongoDBConnectionConfig mongoDBConnectionConfig;

    /**
     * The Redis configuration of the services' L2 cache. Thread counts left at Redisson's defaults are capped by the
     * cache module.
     */
    private final Config redisConfig;

    /**
     * Whether {@link LegacyDataServices#shutdown()} closes {@link #mongoDBConnectionConfig} after the services, for a
     * connection the plugin opened only for them.
     */
    @Builder.Default
    private final boolean closeMongoDBConnection = false;

    /**
     * Whether to ping MongoDB before starting anything, so an unreachable database fails the start rather than the
     * first save.
     */
    @Builder.Default
    private final boolean verifyConnection = true;

    /**
     * Whether to start a {@link LegacyPlayerDataService}.
     */
    @Builder.Default
    private final boolean playerDataService = true;

    /**
     * Whether to start a {@link LegacyEntityDataService}.
     */
    @Builder.Default
    private final boolean entityDataService = true;

    /**
     * The interval between two scheduled persistence runs of each service.
     */
    @Builder.Default
    private final Duration autoSaveInterval = DEFAULT_AUTO_SAVE_INTERVAL;

    /**
     * The interval between two reads of the Redis stream of each service.
     */
    @Builder.Default
    private final Duration redisStreamAcceptInterval = DEFAULT_REDIS_STREAM_ACCEPT_INTERVAL;

    /**
     * How long player data stays in Redis.
     */
    @Builder.Default
    private final Duration playerTtl = LegacyPlayerDataService.DEFAULT_TTL_DURATION;

    /**
     * How long messages stay in the player service's Redis stream at most.
     */
    @Builder.Default
    private final Duration playerStreamRetention = LegacyPlayerDataService.DEFAULT_STREAM_RETENTION_DURATION;

    /**
     * How long entity data stays in Redis.
     */
    @Builder.Default
    private final Duration entityTtl = LegacyEntityDataService.DEFAULT_TTL_DURATION;

    /**
     * The settings of the entity service's cross-server synchronization.
     */
    @Builder.Default
    private final EntitySyncSettings entitySyncSettings = EntitySyncSettings.defaults();

    /**
     * The packages to scan for Redis stream accepters; include {@code net.legacy.library.player} for the built-in
     * ones, and your own package for yours.
     */
    @Builder.Default
    private final List<String> basePackages = List.of("net.legacy.library.player");

    /**
     * The class loaders to scan {@link #basePackages} with; include your plugin's when you add your own package.
     */
    @Builder.Default
    private final List<ClassLoader> classLoaders = List.of(PlayerLauncher.class.getClassLoader());

}
