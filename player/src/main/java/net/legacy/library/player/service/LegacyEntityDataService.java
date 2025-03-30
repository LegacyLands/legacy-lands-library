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
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.task.EntityDataPersistenceTask;
import net.legacy.library.player.task.EntityDataPersistenceTimerTask;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInvokeTask;
import net.legacy.library.player.task.redis.EntityRStreamPubTask;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Predicate;
import java.util.stream.Collectors;

/**
 * Service for managing {@link LegacyEntityData} with a multi-level caching system.
 *
 * <p>This service handles the retrieval, synchronization, and persistence of entity data
 * across different cache levels (L1: Caffeine, L2: Redis) and the underlying database.
 * It provides comprehensive entity relationship management and advanced query capabilities.
 *
 * <p>The service supports various query patterns including relationship-based queries,
 * attribute-based queries, and combined queries with filtering predicates. It ensures
 * high performance through optimized cache usage and asynchronous data persistence.
 *
 * <p>Multiple instances of {@code LegacyEntityDataService} can be managed concurrently,
 * each identified by a unique name.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Getter
public class LegacyEntityDataService {
    /**
     * Cache service for managing {@link LegacyEntityDataService} instances.
     * Keyed by service name.
     */
    public static final CacheServiceInterface<Cache<String, LegacyEntityDataService>, LegacyEntityDataService>
            LEGACY_ENTITY_DATA_SERVICES = CacheServiceFactory.createCaffeineCache();

    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;
    private final FlexibleMultiLevelCacheService flexibleMultiLevelCacheService;
    private final ScheduledTask<?> entityDataPersistenceTimerTask;
    private final ScheduledTask<?> redisStreamAcceptTask;

    /**
     * Constructs a new {@link LegacyEntityDataService}.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration for initializing the Redis cache
     * @param autoSaveInterval          the interval for auto-saving entity data to the database
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     */
    public LegacyEntityDataService(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                  Config config, Duration autoSaveInterval, List<String> basePackages,
                                  List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval) {
        this.name = name;
        this.mongoDBConnectionConfig = mongoDBConnectionConfig;

        // Create L1 cache using Caffeine
        CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> cacheStringCacheServiceInterface =
                CacheServiceFactory.createCaffeineCache();

        // Create L2 cache using Redis
        RedisCacheServiceInterface redisCacheServiceInterface =
                CacheServiceFactory.createRedisCache(config);

        // Initialize multi-level cache
        this.flexibleMultiLevelCacheService = CacheServiceFactory.createFlexibleMultiLevelCacheService(Set.of(
                TieredCacheLevel.of(1, cacheStringCacheServiceInterface),
                TieredCacheLevel.of(2, redisCacheServiceInterface)
        ));

        // Record all LegacyEntityDataService instances
        Cache<String, LegacyEntityDataService> cache = LEGACY_ENTITY_DATA_SERVICES.getResource();

        if (cache.getIfPresent(name) != null) {
            throw new IllegalStateException("LegacyEntityDataService with name " + name + " already exists");
        }

        cache.put(name, this);

        // Auto save task
        this.entityDataPersistenceTimerTask =
                EntityDataPersistenceTimerTask.of(autoSaveInterval, autoSaveInterval, 
                        LockSettings.of(50, 50, TimeUnit.MILLISECONDS), this).start();

        // Redis stream accept task
        this.redisStreamAcceptTask = 
                EntityRStreamAccepterInvokeTask.of(this, basePackages, classLoaders, redisStreamAcceptInterval).start();
    }

    /**
     * Creates a new {@link LegacyEntityDataService} with default intervals.
     *
     * @param name                    the unique name of the service
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @param basePackages            the base packages to scan for accepter annotations
     * @param classLoaders            the class loaders to scan for accepter annotations
     * @return the newly created {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                            List<String> basePackages, List<ClassLoader> classLoaders) {
        return new LegacyEntityDataService(name, mongoDBConnectionConfig, config, 
                Duration.ofHours(2), basePackages, classLoaders, Duration.ofSeconds(2));
    }

    /**
     * Creates a new {@link LegacyEntityDataService} with custom intervals.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration for initializing the Redis cache
     * @param autoSaveInterval          the interval for auto-saving entity data to the database
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @return the newly created {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config, 
                                            Duration autoSaveInterval, List<String> basePackages, 
                                            List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval) {
        return new LegacyEntityDataService(name, mongoDBConnectionConfig, config, autoSaveInterval, 
                basePackages, classLoaders, redisStreamAcceptInterval);
    }

    /**
     * Retrieves a {@link LegacyEntityDataService} by its unique name.
     *
     * @param name the name of the service to retrieve
     * @return an {@link Optional} containing the service if found, or empty if not found
     */
    public static Optional<LegacyEntityDataService> getLegacyEntityDataService(String name) {
        return Optional.ofNullable(LEGACY_ENTITY_DATA_SERVICES.getResource().getIfPresent(name));
    }

    /**
     * Publishes an entity-related task to the Redis stream for processing across servers.
     *
     * <p>After the returned {@link ScheduledTask} is executed, it indicates that the task
     * has been successfully published to the stream. To ensure the task is executed,
     * additional logic should be implemented in the corresponding accepter.
     *
     * @param entityRStreamTask the task to be published to the Redis stream
     * @return a {@link ScheduledTask} instance tracking the execution status of the task
     */
    public ScheduledTask<?> pubEntityRStreamTask(EntityRStreamTask entityRStreamTask) {
        return EntityRStreamPubTask.of(this, entityRStreamTask).start();
    }
    
    /**
     * Creates and starts a new entity stream task.
     *
     * <p>This method creates a new {@link EntityRStreamTask} with the provided parameters
     * and starts it immediately, returning the resulting {@link ScheduledTask}.
     *
     * @param taskName the name of the task
     * @param data the data payload of the task
     * @param expirationTime the duration after which the task expires
     * @return a {@link ScheduledTask} instance tracking the execution status of the task
     */
    public ScheduledTask<?> createEntityStreamTask(String taskName, String data, Duration expirationTime) {
        EntityRStreamTask task = EntityRStreamTask.of(taskName, data, expirationTime);
        return pubEntityRStreamTask(task);
    }

    /**
     * Retrieves the first-level (L1) cache service.
     *
     * @return the {@link CacheServiceInterface} used for the first-level cache (L1)
     * @throws IllegalStateException if the L1 cache is not found
     */
    public CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> getL1Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(1, 
                () -> new IllegalStateException("L1 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyEntityData} from the first-level (L1) cache.
     *
     * @param uuid the unique identifier of the entity
     * @return an {@link Optional} containing the entity if found in L1 cache, or empty otherwise
     */
    public Optional<LegacyEntityData> getFromL1Cache(UUID uuid) {
        CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> l1Cache = getL1Cache();
        Cache<UUID, LegacyEntityData> l1CacheImpl = l1Cache.getResource();
        return Optional.ofNullable(l1CacheImpl.getIfPresent(uuid));
    }

    /**
     * Retrieves the second-level (L2) cache service using Redis.
     *
     * @return the {@link RedisCacheServiceInterface} used for the second-level cache (L2)
     * @throws IllegalStateException if the L2 cache is not found
     */
    public RedisCacheServiceInterface getL2Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, 
                () -> new IllegalStateException("L2 cache not found"))
                .getCacheWithType();
    }

    /**
     * Retrieves the {@link LegacyEntityData} from the second-level (L2) cache.
     *
     * @param uuid the unique identifier of the entity
     * @return an {@link Optional} containing the entity if found in L2 cache, or empty otherwise
     */
    public Optional<LegacyEntityData> getFromL2Cache(UUID uuid) {
        String key = EntityRKeyUtil.getEntityKey(uuid, this);
        RedisCacheServiceInterface l2Cache = getL2Cache();
        
        String jsonData = l2Cache.getWithType(
                client -> client.getReadWriteLock(EntityRKeyUtil.getEntityReadWriteLockKey(key)).readLock(),
                client -> client.getBucket(key).get(),
                () -> null, null, false, LockSettings.of(5, 5, TimeUnit.MILLISECONDS)
        );

        if (jsonData == null || jsonData.isEmpty()) {
            return Optional.empty();
        }

        // Deserialize JSON to LegacyEntityData
        return Optional.ofNullable(SimplixSerializer.deserialize(jsonData, LegacyEntityData.class));
    }

    /**
     * Retrieves the {@link LegacyEntityData} from the database.
     *
     * @param uuid the unique identifier of the entity
     * @return the entity data retrieved from the database, or null if not found
     */
    public LegacyEntityData getFromDatabase(UUID uuid) {
        String uuidString = uuid.toString();

        @Cleanup
        MorphiaCursor<LegacyEntityData> queryResult = mongoDBConnectionConfig.getDatastore()
                .find(LegacyEntityData.class)
                .filter(Filters.eq("_id", uuidString))
                .iterator();

        return queryResult.hasNext() ? queryResult.tryNext() : null;
    }

    /**
     * Creates a new entity with the specified type if it doesn't exist.
     *
     * @param uuid       the unique identifier of the entity
     * @param entityType the type of the entity
     * @return the newly created or existing entity
     */
    public LegacyEntityData createEntityIfNotExists(UUID uuid, String entityType) {
        LegacyEntityData entityData = getEntityData(uuid);
        
        if (entityData == null) {
            entityData = LegacyEntityData.of(uuid, entityType);
            saveEntity(entityData);
        }
        
        return entityData;
    }

    /**
     * Saves an entity to all cache levels and schedules database persistence.
     *
     * @param entityData the entity data to save
     */
    public void saveEntity(LegacyEntityData entityData) {
        // Save to L1 cache
        CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> l1Cache = getL1Cache();
        Cache<UUID, LegacyEntityData> l1CacheImpl = l1Cache.getResource();
        l1CacheImpl.put(entityData.getUuid(), entityData);
        
        // Schedule persistence to L2 and DB
        EntityDataPersistenceTask.of(LockSettings.of(5, 5, TimeUnit.MILLISECONDS), this, entityData.getUuid()).start();
    }

    /**
     * Retrieves entity data using the multi-level cache and database.
     *
     * @param uuid the unique identifier of the entity
     * @return the entity data, or null if not found
     */
    public LegacyEntityData getEntityData(UUID uuid) {
        // Check L1 cache first
        Optional<LegacyEntityData> dataFromL1Cache = getFromL1Cache(uuid);
        if (dataFromL1Cache.isPresent()) {
            return dataFromL1Cache.get();
        }

        // Check L2 cache next
        Optional<LegacyEntityData> dataFromL2Cache = getFromL2Cache(uuid);
        if (dataFromL2Cache.isPresent()) {
            LegacyEntityData entityData = dataFromL2Cache.get();
            // Store in L1 cache for future access
            getL1Cache().getResource().put(uuid, entityData);
            return entityData;
        }

        // Finally check database
        LegacyEntityData entityData = getFromDatabase(uuid);
        if (entityData != null) {
            // Store in L1 cache for future access
            getL1Cache().getResource().put(uuid, entityData);
        }
        
        return entityData;
    }

    /**
     * Finds all entities with the specified entity type.
     *
     * @param entityType the entity type to search for
     * @return a list of matching entities
     */
    public List<LegacyEntityData> findEntitiesByType(String entityType) {
        List<LegacyEntityData> results = new ArrayList<>();
        
        // Check L1 cache first
        getL1Cache().getResource().asMap().values().stream()
                .filter(entity -> entityType.equals(entity.getEntityType()))
                .forEach(results::add);
                
        // Then check database for any not in cache
        for (LegacyEntityData entity : mongoDBConnectionConfig.getDatastore()
                .find(LegacyEntityData.class)
                .filter(Filters.eq("entityType", entityType))) {
            if (getFromL1Cache(entity.getUuid()).isEmpty()) {
                results.add(entity);
                // Cache for future use
                getL1Cache().getResource().put(entity.getUuid(), entity);
            }
        }
        
        return results;
    }

    /**
     * Finds all entities with a specific attribute value.
     *
     * @param attributeKey   the attribute key to search for
     * @param attributeValue the attribute value to match
     * @return a list of matching entities
     */
    public List<LegacyEntityData> findEntitiesByAttribute(String attributeKey, String attributeValue) {
        List<LegacyEntityData> results = new ArrayList<>();
        
        // Check L1 cache first
        getL1Cache().getResource().asMap().values().stream()
                .filter(entity -> attributeValue.equals(entity.getAttribute(attributeKey)))
                .forEach(results::add);
                
        // Then check database with aggregation
        for (LegacyEntityData entity : mongoDBConnectionConfig.getDatastore()
                .find(LegacyEntityData.class)
                .filter(Filters.eq("attributes." + attributeKey, attributeValue))) {
            // Avoid duplicates from cache
            if (getFromL1Cache(entity.getUuid()).isEmpty()) {
                results.add(entity);
                // Cache for future use
                getL1Cache().getResource().put(entity.getUuid(), entity);
            }
        }
        
        return results;
    }

    /**
     * Finds all entities that have a relationship with the specified entity.
     *
     * @param relationshipType the type of relationship to search for
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @return a list of entities that have the specified relationship with the target entity
     */
    public List<LegacyEntityData> findEntitiesByRelationship(String relationshipType, UUID targetEntityUuid) {
        // Check L1 cache first
        return getL1Cache().getResource().asMap().values().stream()
                .filter(entity -> entity.hasRelationship(relationshipType, targetEntityUuid))
                .collect(Collectors.toList());
    }

    /**
     * Finds all entities of a specific type that have a relationship with the specified entity.
     *
     * @param entityType      the entity type to filter by
     * @param relationshipType the type of relationship to search for
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @return a list of entities of the specified type that have the relationship with the target entity
     */
    public List<LegacyEntityData> findEntitiesByTypeAndRelationship(
            String entityType, String relationshipType, UUID targetEntityUuid) {
        return findEntitiesByRelationship(relationshipType, targetEntityUuid).stream()
                .filter(entity -> entityType.equals(entity.getEntityType()))
                .collect(Collectors.toList());
    }

    /**
     * Finds all entities matching a custom filter predicate.
     *
     * @param filter the predicate to filter entities with
     * @return a list of entities matching the filter
     */
    public List<LegacyEntityData> findEntities(Predicate<LegacyEntityData> filter) {
        return getL1Cache().getResource().asMap().values().stream()
                .filter(filter)
                .collect(Collectors.toList());
    }

    /**
     * Retrieves all entities related to the specified entity by a relationship type.
     *
     * @param entityUuid      the UUID of the entity to find relationships for
     * @param relationshipType the type of relationship to search for
     * @return a list of related entities
     */
    public List<LegacyEntityData> getRelatedEntities(UUID entityUuid, String relationshipType) {
        LegacyEntityData entity = getEntityData(entityUuid);
        if (entity == null) {
            return Collections.emptyList();
        }
        
        return entity.getRelatedEntities(relationshipType).stream()
                .map(this::getEntityData)
                .filter(Objects::nonNull)
                .collect(Collectors.toList());
    }

    /**
     * Establishes a bidirectional relationship between two entities.
     *
     * @param entity1Uuid       the UUID of the first entity
     * @param entity2Uuid       the UUID of the second entity
     * @param relationshipType1 the relationship type from entity1 to entity2
     * @param relationshipType2 the relationship type from entity2 to entity1
     * @return true if both relationships were established, false otherwise
     */
    public boolean createBidirectionalRelationship(
            UUID entity1Uuid, UUID entity2Uuid, 
            String relationshipType1, String relationshipType2) {
        
        LegacyEntityData entity1 = getEntityData(entity1Uuid);
        LegacyEntityData entity2 = getEntityData(entity2Uuid);
        
        if (entity1 == null || entity2 == null) {
            return false;
        }
        
        entity1.addRelationship(relationshipType1, entity2Uuid);
        entity2.addRelationship(relationshipType2, entity1Uuid);
        
        saveEntity(entity1);
        saveEntity(entity2);
        
        return true;
    }

    /**
     * Counts the number of entities that have a specific relationship with a target entity.
     *
     * @param relationshipType the type of relationship to count
     * @param targetEntityUuid the UUID of the target entity
     * @return the count of entities with the specified relationship
     */
    public int countEntitiesWithRelationship(String relationshipType, UUID targetEntityUuid) {
        return (int) getL1Cache().getResource().asMap().values().stream()
                .filter(entity -> entity.hasRelationship(relationshipType, targetEntityUuid))
                .count();
    }

    /**
     * Shuts down the service, ensuring all data is properly persisted.
     *
     * @throws InterruptedException if the shutdown process is interrupted
     */
    public void shutdown() throws InterruptedException {
        // Persist all entities in L1 cache
        getL1Cache().getResource().asMap().forEach((uuid, data) -> {
            EntityDataPersistenceTask.of(LockSettings.of(5, 5, TimeUnit.MILLISECONDS), this, uuid).start();
        });
        
        // Wait for tasks to complete
        Thread.sleep(1000);
        
        // Remove this service from the registry
        LEGACY_ENTITY_DATA_SERVICES.getResource().asMap().remove(name);
        
        // Shutdown L2 cache
        getL2Cache().shutdown();
    }

    /**
     * Retrieves the name prefix used for Redis keys.
     *
     * @return the name prefix string
     */
    public String getNamePrefix() {
        return name;
    }
} 