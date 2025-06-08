package net.legacy.library.player.service;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.filters.Filters;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.Cleanup;
import lombok.Getter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.multi.FlexibleMultiLevelCacheService;
import net.legacy.library.cache.service.multi.TieredCacheLevel;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.VirtualThreadScheduledFuture;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.task.PlayerDataPersistenceTask;
import net.legacy.library.player.task.PlayerDataPersistenceTimerTask;
import net.legacy.library.player.task.redis.RStreamAccepterInvokeTask;
import net.legacy.library.player.task.redis.RStreamPubTask;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.util.RKeyUtil;
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Service for managing {@link LegacyPlayerData} with a multi-level caching system.
 *
 * <p>This service handles the retrieval, synchronization, and persistence of player data
 * across different cache levels (L1: Caffeine, L2: Redis) and the underlying database.
 *
 * <p>It provides methods to retrieve player data efficiently by first checking the L1 cache,
 * then the L2 cache, and finally falling back to the database if necessary. Additionally,
 * it manages background tasks for data persistence and Redis stream processing.
 *
 * <p>Multiple instances of {@code LegacyPlayerDataService} can be managed concurrently,
 * each identified by a unique name.
 *
 * @author qwq-dev
 * @since 2025-01-03 15:12
 */
@Getter
public class LegacyPlayerDataService {
    /**
     * Cache service for managing {@link LegacyPlayerDataService} instances.
     * Keyed by service name.
     */
    public static final CacheServiceInterface<Cache<String, LegacyPlayerDataService>, LegacyPlayerDataService>
            LEGACY_PLAYER_DATA_SERVICES = CacheServiceFactory.createCaffeineCache();

    /**
     * Default TTL for player data in Redis (1 DAY).
     */
    public static final Duration DEFAULT_TTL_DURATION = Duration.ofDays(1);

    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;
    private final FlexibleMultiLevelCacheService flexibleMultiLevelCacheService;
    private final VirtualThreadScheduledFuture playerDataPersistenceTimerTask;
    private final VirtualThreadScheduledFuture redisStreamAcceptTask;

    /**
     * Constructs a new {@link LegacyPlayerDataService}.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration for initializing the Redis cache
     * @param autoSaveInterval          the interval for auto-saving player data to the database
     * @param basePackages              the base packages to scan for {@link net.legacy.library.player.annotation.RStreamAccepterRegister} annotations
     * @param classLoaders              the class loaders to scan for {@link net.legacy.library.player.annotation.RStreamAccepterRegister} annotations
     * @param ttl                       the custom TTL to apply to player data in Redis
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     */
    public LegacyPlayerDataService(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                   Config config, Duration autoSaveInterval, List<String> basePackages,
                                   List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl

    ) {
        // Record all LegacyPlayerDataService first
        Cache<String, LegacyPlayerDataService> cache = LEGACY_PLAYER_DATA_SERVICES.getResource();

        if (cache.getIfPresent(name) != null) {
            throw new IllegalStateException("LegacyPlayerDataService with name " + name + " already exists");
        }

        cache.put(name, this);

        this.name = name;
        this.mongoDBConnectionConfig = mongoDBConnectionConfig;

        // Create L1 cache using Caffeine
        CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> cacheStringCacheServiceInterface =
                CacheServiceFactory.createCaffeineCache();

        // Create L2 cache using Redis
        RedisCacheServiceInterface redisCacheServiceInterface =
                CacheServiceFactory.createRedisCache(config);

        // Initialize multi-level cache
        this.flexibleMultiLevelCacheService = CacheServiceFactory.createFlexibleMultiLevelCacheService(Set.of(
                TieredCacheLevel.of(1, cacheStringCacheServiceInterface),
                TieredCacheLevel.of(2, redisCacheServiceInterface)
        ));

        // Auto save task
        this.playerDataPersistenceTimerTask =
                PlayerDataPersistenceTimerTask.of(autoSaveInterval, autoSaveInterval, LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this, ttl).start();

        // Redis stream accept task
        this.redisStreamAcceptTask = RStreamAccepterInvokeTask.of(this, basePackages, classLoaders, redisStreamAcceptInterval).start();
    }

    /**
     * Creates a new {@link LegacyPlayerDataService}.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration
     * @param autoSaveInterval          the interval between auto-save operations
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @param ttl                       the custom TTL to apply to player data in Redis
     * @return a new instance of {@link LegacyPlayerDataService}
     */
    public static LegacyPlayerDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             Duration autoSaveInterval, List<String> basePackages,
                                             List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl) {
        return new LegacyPlayerDataService(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders, redisStreamAcceptInterval, ttl);
    }

    /**
     * Creates a new {@link LegacyPlayerDataService} with default auto-save interval, Redis stream accept interval, TTL.
     *
     * @param name                    the unique name of the service
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @param basePackages            the base packages to scan for {@link net.legacy.library.player.annotation.RStreamAccepterRegister} annotations
     * @param classLoaders            the class loaders to scan for {@link net.legacy.library.player.annotation.RStreamAccepterRegister} annotations
     * @return the newly created {@link LegacyPlayerDataService}
     */
    public static LegacyPlayerDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                             Config config, List<String> basePackages, List<ClassLoader> classLoaders
    ) {
        return of(name, mongoDBConnectionConfig, config, Duration.ofHours(2), basePackages, classLoaders, Duration.ofSeconds(2), DEFAULT_TTL_DURATION);
    }

    /**
     * Creates a new {@link LegacyPlayerDataService} with default TTL.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration
     * @param autoSaveInterval          the interval between auto-save operations
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @return a new instance of {@link LegacyPlayerDataService}
     */
    public static LegacyPlayerDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             Duration autoSaveInterval, List<String> basePackages,
                                             List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval) {
        return of(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders, redisStreamAcceptInterval, DEFAULT_TTL_DURATION);
    }

    /**
     * Retrieves a {@link LegacyPlayerDataService} by its unique name.
     *
     * @param name the name of the service to retrieve
     * @return an {@link Optional} containing the {@link LegacyPlayerDataService} if found, or empty if not found
     */
    public static Optional<LegacyPlayerDataService> getLegacyPlayerDataService(String name) {
        return Optional.ofNullable(LEGACY_PLAYER_DATA_SERVICES.getResource().getIfPresent(name));
    }

    /**
     * Publishes a {@link RStreamTask} to the Redis stream for processing.
     *
     * <p>After the returned {@link ScheduledTask} is executed, it indicates that the task
     * has been successfully published to the stream. To ensure the task is executed,
     * additional logic should be implemented in the corresponding {@link net.legacy.library.player.task.redis.RStreamAccepterInterface}.
     *
     * @param rStreamTask the task to be published to the Redis stream
     * @return a {@link CompletableFuture} instance tracking the execution status of the task
     */
    public CompletableFuture<?> pubRStreamTask(RStreamTask rStreamTask) {
        return RStreamPubTask.of(this, rStreamTask).start();
    }

    /**
     * Retrieves the first-level (L1) cache service.
     *
     * @return the {@link CacheServiceInterface} used for the first-level cache (L1)
     * @throws IllegalStateException if the L1 cache is not found
     */
    public CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> getL1Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(1, () -> new IllegalStateException("L1 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the first-level (L1) cache.
     *
     * @param uuid the unique identifier of the player
     * @return an {@link Optional} containing the {@link LegacyPlayerData} if found in L1 cache, or empty otherwise
     */
    public Optional<LegacyPlayerData> getFromL1Cache(UUID uuid) {
        // Get L1 cache
        CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> l1Cache = getL1Cache();
        Cache<UUID, LegacyPlayerData> l1CacheImpl = l1Cache.getResource();

        // Retrieve data from L1 cache
        return Optional.ofNullable(l1CacheImpl.getIfPresent(uuid));
    }

    /**
     * Retrieves the second-level (L2) cache service using Redis.
     *
     * @return the {@link RedisCacheServiceInterface} used for the second-level cache (L2)
     * @throws IllegalStateException if the L2 cache is not found
     */
    public RedisCacheServiceInterface getL2Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, () -> new IllegalStateException("L2 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the second-level (L2) cache.
     *
     * @param uuid the unique identifier of the player
     * @return an {@link Optional} containing the {@link LegacyPlayerData} if found in L2 cache, or empty otherwise
     */
    public Optional<LegacyPlayerData> getFromL2Cache(UUID uuid) {
        String key = RKeyUtil.getRLPDSKey(uuid, this);

        // Get L2 cache
        RedisCacheServiceInterface l2Cache = getL2Cache();
        String string =
                l2Cache.getWithType(
                        client -> client.getReadWriteLock(RKeyUtil.getRLPDSReadWriteLockKey(key)).readLock(),
                        client -> client.getBucket(key).get(),
                        () -> null, null, false, LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
                );

        if (string == null || string.isEmpty()) {
            return Optional.empty();
        }

        // Deserialize JSON to LegacyPlayerData
        return Optional.ofNullable(SimplixSerializer.deserialize(string, LegacyPlayerData.class));
    }

    /**
     * Retrieves the {@link LegacyPlayerData} from the database for the specified UUID.
     *
     * @param uuid the unique identifier of the player
     * @return the {@link LegacyPlayerData} retrieved from the database, or a new instance if not found
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
     * <p>This method queries the L1 cache first, then the L2 cache, and finally the database
     * if the data is not found in either cache. It also ensures that the retrieved data
     * is cached in the L1 cache for faster future access.
     *
     * @param uuid the unique identifier of the player
     * @return the {@link LegacyPlayerData} for the player
     */
    public LegacyPlayerData getLegacyPlayerData(UUID uuid) {
        Optional<LegacyPlayerData> dataFromL1Cache = getFromL1Cache(uuid);

        if (dataFromL1Cache.isPresent()) {
            return dataFromL1Cache.get();
        }

        CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> l1Cache = getL1Cache();
        Cache<UUID, LegacyPlayerData> l1CacheImpl = l1Cache.getResource();
        LegacyPlayerData legacyPlayerData = getFromL2Cache(uuid).orElseGet(() -> getFromDatabase(uuid));

        l1CacheImpl.put(uuid, legacyPlayerData);
        return legacyPlayerData;
    }

    /**
     * Shuts down the {@link LegacyPlayerDataService}, ensuring that all
     * background tasks are properly terminated and caches are cleared.
     *
     * <p>This method waits for the player data persistence task to finish,
     * invalidates the service from the global cache, and shuts down the L2 cache.
     *
     * @throws InterruptedException if the shutdown process is interrupted
     */
    public void shutdown() throws InterruptedException {
        // Wait for the player data persistence task to finish
        PlayerDataPersistenceTask.of(LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this).start().wait();

        // Remove this LegacyPlayerDataService from the cache
        LEGACY_PLAYER_DATA_SERVICES.getResource().asMap().remove(name);

        // Shutdown L2 cache
        RedisCacheServiceInterface redisCacheService = getL2Cache();
        redisCacheService.shutdown();
    }

    /**
     * Saves player data to L2 cache and schedules database persistence.
     *
     * <p>This method performs the following operations:
     * <ol>
     *   <li>Save the data to L2 cache (Redis) with TTL</li>
     *   <li>Schedule persistence to MongoDB</li>
     * </ol>
     *
     * <p>Note: The data will be automatically loaded into L1 cache when accessed via getLegacyPlayerData.
     * This method focuses on ensuring data persistence across storage layers.
     *
     * @param legacyPlayerData the player data to save
     */
    public void saveLegacyPlayerData(LegacyPlayerData legacyPlayerData) {
        if (legacyPlayerData == null) {
            return;
        }

        UUID uuid = legacyPlayerData.getUuid();

        // Save to L2 cache with TTL
        String key = RKeyUtil.getRLPDSKey(uuid, this);
        String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();

        // Use getWithType with writeLock to store data in L2 cache with TTL
        getL2Cache().getWithType(
                client -> client.getReadWriteLock(RKeyUtil.getRLPDSReadWriteLockKey(key)).writeLock(),
                client -> null,
                () -> {
                    // Store operation with TTL using the new Duration-based method
                    RedissonClient client = getL2Cache().getResource();
                    client.getBucket(key).set(serialized, DEFAULT_TTL_DURATION);
                    return null;
                },
                null,
                false,
                LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
        );

        // Schedule database persistence
        PlayerDataPersistenceTask.of(LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this).start();
    }

    /**
     * Sets the TTL (Time-To-Live) for a player in the L2 cache.
     * If the player doesn't exist in L2 cache, this method will have no effect.
     *
     * @param uuid the UUID of the player
     * @param ttl  the duration after which the player data should expire
     * @return true if the TTL was set successfully, false otherwise
     */
    public boolean setPlayerTTL(UUID uuid, Duration ttl) {
        if (uuid == null) {
            return false;
        }

        try {
            String playerKey = RKeyUtil.getRLPDSKey(uuid, this);
            RedissonClient redissonClient = getL2Cache().getResource();
            RBucket<Object> bucket = redissonClient.getBucket(playerKey);

            if (!bucket.isExists()) {
                return false;
            }

            return TTLUtil.setReliableTTL(redissonClient, playerKey, ttl.getSeconds());
        } catch (Exception exception) {
            Log.error("Failed to set TTL for player %s", uuid, exception);
            return false;
        }
    }

    /**
     * Sets the default TTL for a player in the L2 cache.
     * If the player doesn't exist in L2 cache, this method will have no effect.
     *
     * @param uuid the UUID of the player
     * @return true if the TTL was set successfully, false otherwise
     */
    public boolean setPlayerDefaultTTL(UUID uuid) {
        return setPlayerTTL(uuid, DEFAULT_TTL_DURATION);
    }

    /**
     * Sets the default TTL for all players in the L2 cache that don't already have a TTL.
     * This can be used to fix legacy data that was stored without TTL.
     *
     * @return the number of players that had their TTL set
     */
    public int setDefaultTTLForAllPlayers() {
        int count = 0;
        try {
            RedissonClient redissonClient = getL2Cache().getResource();
            RKeys keys = redissonClient.getKeys();
            String pattern = RKeyUtil.getPlayerKeyPattern(this);

            KeysScanOptions keysScanOptions = KeysScanOptions.defaults().pattern(pattern);

            for (String key : keys.getKeys(keysScanOptions)) {
                if (TTLUtil.processBucketTTL(redissonClient, key, DEFAULT_TTL_DURATION.getSeconds())) {
                    count++;
                }
            }
        } catch (Exception exception) {
            Log.error("Error setting default TTL for players", exception);
        }
        return count;
    }
}