package net.legacy.library.player.service;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.filters.Filters;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.Cleanup;
import lombok.Getter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.multi.FlexibleMultiLevelCacheService;
import net.legacy.library.cache.service.multi.TieredCacheLevel;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.task.PlayerDataPersistenceTask;
import net.legacy.library.player.util.KeyUtil;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing {@link LegacyPlayerData} with a multi-level caching system.
 *
 * @author qwq-dev
 * @since 2025-01-03 15:12
 */
@Getter
public class LegacyPlayerDataService {
    /**
     * Cache service for managing {@link LegacyPlayerDataService} instances.
     */
    public static final CacheServiceInterface<Cache<String, LegacyPlayerDataService>, LegacyPlayerDataService>
            LEGACY_PLAYER_DATA_SERVICES = CacheServiceFactory.createCaffeineCache();

    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;
    private final FlexibleMultiLevelCacheService flexibleMultiLevelCacheService;
    private final ScheduledTask<?> playerDataPersistenceTask;

    /**
     * Constructs a new {@link LegacyPlayerDataService}.
     *
     * @param name                    the name
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @param autoSaveInterval        the interval for auto-saving player data to the database
     */
    public LegacyPlayerDataService(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config, Duration autoSaveInterval) {
        this.name = name;
        this.mongoDBConnectionConfig = mongoDBConnectionConfig;

        // Create L1 cache using Caffeine
        CacheServiceInterface<Cache<String, LegacyPlayerData>, LegacyPlayerData> cacheStringCacheServiceInterface =
                CacheServiceFactory.createCaffeineCache();

        // Create L2 cache using Redis
        RedisCacheServiceInterface redisCacheServiceInterface =
                CacheServiceFactory.createRedisCache(config);

        // Initialize multi-level cache
        this.flexibleMultiLevelCacheService = CacheServiceFactory.createFlexibleMultiLevelCacheService(Set.of(
                TieredCacheLevel.of(1, cacheStringCacheServiceInterface.getCache()),
                TieredCacheLevel.of(2, redisCacheServiceInterface.getCache())
        ));

        // Record all LegacyPlayerDataService
        Cache<String, LegacyPlayerDataService> cache = LEGACY_PLAYER_DATA_SERVICES.getCache();

        if (cache.getIfPresent(name) != null) {
            throw new IllegalStateException("LegacyPlayerDataService with name " + name + " already exists");
        }

        cache.put(name, this);

        // Auto save task
        this.playerDataPersistenceTask =
                PlayerDataPersistenceTask.of(autoSaveInterval, autoSaveInterval, LockSettings.of(0, 0, TimeUnit.MILLISECONDS), this).start();
    }

    /**
     * Creates a new {@link LegacyPlayerDataService}.
     *
     * <p>The auto-save interval is set to 2 hours by default.
     *
     * @param name                    the name
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @return the {@link LegacyPlayerDataService}
     */
    public static LegacyPlayerDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config) {
        return new LegacyPlayerDataService(name, mongoDBConnectionConfig, config, Duration.ofHours(2));
    }

    /**
     * Creates a new {@link LegacyPlayerDataService}.
     *
     * @param name                    the name
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @param autoSaveInterval        the interval for auto-saving player data to the database
     * @return the {@link LegacyPlayerDataService}
     */
    public static LegacyPlayerDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config, Duration autoSaveInterval) {
        return new LegacyPlayerDataService(name, mongoDBConnectionConfig, config, autoSaveInterval);
    }

    /**
     * Retrieves a {@link LegacyPlayerDataService} by name.
     *
     * @param name the name of the service
     * @return an {@link Optional} containing the {@link LegacyPlayerDataService} if found, or empty if not found
     */
    public static Optional<LegacyPlayerDataService> getLegacyPlayerDataService(String name) {
        return Optional.ofNullable(LEGACY_PLAYER_DATA_SERVICES.getCache().getIfPresent(name));
    }

    /**
     * Retrieves the {@link CacheServiceInterface} used for the first-level cache (L1).
     *
     * @return the {@link CacheServiceInterface} used for the first-level cache (L1)
     */
    public CacheServiceInterface<Cache<String, LegacyPlayerData>, LegacyPlayerData> getL1Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(1, () -> new IllegalStateException("L1 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the first-level cache (L1).
     *
     * @param uuid the unique identifier of the player
     * @return an {@link Optional} containing the {@link LegacyPlayerData} if found, or empty if not found
     */
    public Optional<LegacyPlayerData> getFromL1Cache(UUID uuid) {
        String uuidString = uuid.toString();

        // Get L1 cache
        CacheServiceInterface<Cache<String, LegacyPlayerData>, LegacyPlayerData> l1Cache = getL1Cache();
        Cache<String, LegacyPlayerData> l1CacheImpl = l1Cache.getCache();

        // Retrieve data from L1 cache
        return Optional.ofNullable(l1CacheImpl.getIfPresent(uuidString));
    }

    /**
     * Retrieves the {@link RedisCacheServiceInterface} used for the second-level cache (L2).
     *
     * @return the {@link RedisCacheServiceInterface} used for the second-level cache (L2)
     */
    public RedisCacheServiceInterface getL2Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, () -> new IllegalStateException("L2 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the second-level cache (L2).
     *
     * @param uuid the unique identifier of the player
     * @return an {@link Optional} containing the {@link LegacyPlayerData} if found, or empty if not found
     */
    public Optional<LegacyPlayerData> getFromL2Cache(UUID uuid) {
        String key = KeyUtil.getLegacyPlayerDataServiceKey(uuid, this);

        // Get L2 cache
        RedisCacheServiceInterface l2Cache = getL2Cache();

        // Retrieve data from L2 cache
        return Optional.ofNullable(l2Cache.getWithType(
                // Deserialize JSON to LegacyPlayerData
                cache -> SimplixSerializer.deserialize(cache.getBucket(key).get().toString(), LegacyPlayerData.class), () -> null, null, false
        ));
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the database.
     *
     * @param uuid the unique identifier of the player
     * @return the {@link LegacyPlayerData} retrieved from the database
     */
    public LegacyPlayerData getFromDatabase(UUID uuid) {
        String uuidString = uuid.toString();

        // Retrieve data from database
        @Cleanup
        MorphiaCursor<LegacyPlayerData> queryResult = mongoDBConnectionConfig.getDatastore()
                .find(LegacyPlayerData.class)
                .filter(Filters.eq("_id", uuidString))
                .iterator();

        return queryResult.hasNext() ? queryResult.tryNext() : LegacyPlayerData.of(uuid);
    }

    /**
     * Retrieves the {@link LegacyPlayerData} for a given player UUID using the multi-level cache and database.
     *
     * <p>This method queries L1 cache first, then L2 cache, and finally the database if the data is not found in the caches.
     *
     * @param uuid the unique identifier of the player
     * @return the {@link LegacyPlayerData} for the player
     * @throws IllegalStateException if required cache levels are not found
     */
    public LegacyPlayerData getLegacyPlayerData(UUID uuid) {
        String uuidString = uuid.toString();

        Optional<LegacyPlayerData> dataFromL1Cache = getFromL1Cache(uuid);

        if (dataFromL1Cache.isPresent()) {
            return dataFromL1Cache.get();
        }

        CacheServiceInterface<Cache<String, LegacyPlayerData>, LegacyPlayerData> l1Cache = getL1Cache();
        Cache<String, LegacyPlayerData> l1CacheImpl = l1Cache.getCache();
        LegacyPlayerData legacyPlayerData = getFromL2Cache(uuid).orElseGet(() -> getFromDatabase(uuid));

        l1CacheImpl.put(uuidString, legacyPlayerData);
        return legacyPlayerData;
    }

    /**
     * Shuts down the {@link RedisCacheServiceInterface} used for the second-level cache (L2).
     */
    public void shutdown() {
        // Remove this LegacyPlayerDataService from the cache
        LEGACY_PLAYER_DATA_SERVICES.getCache().invalidate(String.valueOf(hashCode()));

        // Shutdown L2 cache
        RedisCacheServiceInterface redisCacheService = getL2Cache();
        redisCacheService.shutdown();
    }
}
