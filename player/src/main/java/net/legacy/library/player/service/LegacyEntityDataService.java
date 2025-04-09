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
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.RelationshipCriteria;
import net.legacy.library.player.model.RelationshipQueryType;
import net.legacy.library.player.task.EntityDataPersistenceTask;
import net.legacy.library.player.task.EntityDataPersistenceTimerTask;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInvokeTask;
import net.legacy.library.player.task.redis.EntityRStreamPubTask;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RKeys;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
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

    /**
     * Default TTL for entity data in Redis (30 minutes).
     */
    public static final Duration DEFAULT_TTL_DURATION = Duration.ofMinutes(30);

    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;
    private final FlexibleMultiLevelCacheService flexibleMultiLevelCacheService;
    private final ScheduledFuture<?> entityDataPersistenceTimerTask;
    private final ScheduledFuture<?> redisStreamAcceptTask;

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
     * @param ttl                       the custom TTL to apply to entity data in Redis
     */
    public LegacyEntityDataService(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                   Config config, Duration autoSaveInterval, List<String> basePackages,
                                   List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl) {
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
                        LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this, ttl).start();

        // Redis stream accept task
        this.redisStreamAcceptTask =
                EntityRStreamAccepterInvokeTask.of(this, basePackages, classLoaders, redisStreamAcceptInterval).start();
    }

    /**
     * Creates a new {@link LegacyEntityDataService}.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration
     * @param autoSaveInterval          the interval between auto-save operations
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @param ttl                       the custom TTL to apply to entity data in Redis
     * @return a new instance of {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             Duration autoSaveInterval, List<String> basePackages,
                                             List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl) {
        return new LegacyEntityDataService(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders, redisStreamAcceptInterval, ttl);
    }

    /**
     * Creates a new {@link LegacyEntityDataService} with default auto-save interval, Redis stream accept interval.
     *
     * @param name                    the unique name of the service
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration for initializing the Redis cache
     * @param basePackages            the base packages to scan for accepter annotations
     * @param classLoaders            the class loaders to scan for accepter annotations
     * @param ttl                     the custom TTL to apply to entity data in Redis
     * @return the newly created {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             List<String> basePackages, List<ClassLoader> classLoaders, Duration ttl) {
        return of(name, mongoDBConnectionConfig, config, Duration.ofHours(2), basePackages, classLoaders, Duration.ofSeconds(2), ttl);
    }

    /**
     * Creates a new {@link LegacyEntityDataService} with default auto-save interval, Redis stream accept interval.
     *
     * @param name                    the unique name of the service
     * @param mongoDBConnectionConfig the MongoDB connection configuration
     * @param config                  the Redis configuration
     * @param basePackages            the base packages to scan for accepter annotations
     * @param classLoaders            the class loaders to scan for accepter annotations
     * @return a new instance of {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                             Config config, List<String> basePackages, List<ClassLoader> classLoaders) {
        return of(name, mongoDBConnectionConfig, config, Duration.ofHours(2), basePackages, classLoaders, Duration.ofSeconds(2), DEFAULT_TTL_DURATION);
    }

    /**
     * Creates a new {@link LegacyEntityDataService} with default TTL.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration
     * @param autoSaveInterval          the interval between auto-save operations
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @return a new instance of {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             Duration autoSaveInterval, List<String> basePackages,
                                             List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval) {
        return of(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders, redisStreamAcceptInterval, DEFAULT_TTL_DURATION);
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
     * @return a {@link CompletableFuture} instance tracking the execution status of the task
     */
    public CompletableFuture<?> pubEntityRStreamTask(EntityRStreamTask entityRStreamTask) {
        return EntityRStreamPubTask.of(this, entityRStreamTask).start();
    }

    /**
     * Creates and starts a new entity stream task.
     *
     * <p>This method creates a new {@link EntityRStreamTask} with the provided parameters
     * and starts it immediately, returning the resulting {@link ScheduledTask}.
     *
     * @param taskName       the name of the task
     * @param data           the data payload of the task
     * @param expirationTime the duration after which the task expires
     * @return a {@link CompletableFuture} instance tracking the execution status of the task
     */
    public CompletableFuture<?> createEntityStreamTask(String taskName, String data, Duration expirationTime) {
        return pubEntityRStreamTask(EntityRStreamTask.of(taskName, data, expirationTime));
    }

    /**
     * Retrieves the first-level (L1) cache service.
     *
     * @return the {@link CacheServiceInterface} used for the first-level cache (L1)
     * @throws IllegalStateException if the L1 cache is not found
     */
    public CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> getL1Cache() {
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(1, () -> new IllegalStateException("L1 cache not found")).getCacheWithType();
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
        return flexibleMultiLevelCacheService.getCacheLevelElseThrow(2, () -> new IllegalStateException("L2 cache not found")).getCacheWithType();
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
                () -> null, null, false, LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
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
        return entityData == null ? LegacyEntityData.of(uuid, entityType) : entityData;
    }

    /**
     * Saves entity data to the L1 cache and schedules asynchronous persistence to L2 cache and database.
     *
     * <p>This method performs the following steps:
     * <ol>
     *   <li>Immediately puts the provided {@link LegacyEntityData} into the L1 cache (Caffeine).
     *       This makes the data instantly available for subsequent reads via {@link #getEntityData(UUID)}
     *       within the same service instance.</li>
     *   <li>Schedules an asynchronous task ({@link EntityDataPersistenceTask}) to persist the data
     *       to the L2 cache (Redis) with the configured TTL and to the underlying database (MongoDB).</li>
     * </ol>
     *
     * <p><b>Important:</b> This method returns immediately after scheduling the persistence task.
     * It does <em>not</em> wait for the data to be written to Redis or MongoDB. Persistence is eventual.
     * Use this method when immediate persistence guarantees are not strictly required.
     *
     * @param entityData the entity data to save and schedule for persistence
     */
    public void saveEntity(LegacyEntityData entityData) {
        if (entityData == null) {
            return;
        }

        // Put in L1 cache immediately to make it available for the async task
        getL1Cache().getResource().put(entityData.getUuid(), entityData);

        // Schedule persistence to L2 and DB
        EntityDataPersistenceTask.of(LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this).start();
    }

    /**
     * Saves multiple entities to the L1 cache and schedules asynchronous persistence to L2 cache and database.
     *
     * <p>This method performs the following steps for each entity in the list:
     * <ol>
     *   <li>Immediately puts the {@link LegacyEntityData} into the L1 cache (Caffeine).
     *       This makes the data instantly available for subsequent reads via {@link #getEntityData(UUID)}
     *       within the same service instance.</li>
     *   <li>Schedules a single asynchronous task ({@link EntityDataPersistenceTask}) to persist all
     *       provided entities to the L2 cache (Redis) with the configured TTL and to the underlying database (MongoDB).</li>
     * </ol>
     *
     * <p><b>Important:</b> This method returns immediately after scheduling the persistence task.
     * It does <em>not</em> wait for the data to be written to Redis or MongoDB. Persistence is eventual.
     * This is more efficient than calling {@link #saveEntity(LegacyEntityData)} multiple times as it batches the persistence task.
     *
     * @param entityDataList the list of entity data to save and schedule for persistence
     */
    public void saveEntities(List<LegacyEntityData> entityDataList) {
        if (entityDataList == null || entityDataList.isEmpty()) {
            return;
        }

        // Put all entities in L1 cache immediately
        entityDataList.forEach(entityData -> {
            if (entityData != null) {
                getL1Cache().getResource().put(entityData.getUuid(), entityData);
            }
        });

        // Schedule persistence to L2 and DB
        EntityDataPersistenceTask.of(LockSettings.of(500, 500, TimeUnit.MILLISECONDS), this).start();
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
        Cache<UUID, LegacyEntityData> resource = getL1Cache().getResource();
        Optional<LegacyEntityData> dataFromL2Cache = getFromL2Cache(uuid);
        if (dataFromL2Cache.isPresent()) {
            LegacyEntityData entityData = dataFromL2Cache.get();
            // Store in L1 cache for future access
            resource.put(uuid, entityData);
            return entityData;
        }

        // Finally check database
        LegacyEntityData entityData = getFromDatabase(uuid);
        if (entityData != null) {
            // Store in L1 cache for future access
            resource.put(uuid, entityData);
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
     * @param entityType       the entity type to filter by
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
     * @param entityUuid       the UUID of the entity to find relationships for
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
     * Creates a bidirectional relationship between two entities.
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

        return true;
    }

    /**
     * Creates N-directional relationships between multiple entities.
     *
     * <p>This method creates relationships between multiple entities in a single operation.
     * The relationshipMap defines which entity should have what type of relationship to which other entities.
     *
     * @param relationshipMap a map where:
     *                        - Key: UUID of the source entity
     *                        - Value: Map of relationship types to sets of target entity UUIDs
     * @return true if all relationships were successfully established, false if any entity was not found
     */
    public boolean createNDirectionalRelationships(
            Map<UUID, Map<String, Set<UUID>>> relationshipMap) {

        if (relationshipMap == null || relationshipMap.isEmpty()) {
            return true;
        }

        // First, get all involved entities and check they exist
        Set<UUID> allEntityIds = new HashSet<>(relationshipMap.keySet());

        // Add all target entity IDs
        relationshipMap.values().forEach(typeToTargetsMap ->
                typeToTargetsMap.values().forEach(allEntityIds::addAll));

        // Get all entities in one batch
        Map<UUID, LegacyEntityData> entities = new HashMap<>();
        for (UUID uuid : allEntityIds) {
            LegacyEntityData entity = getEntityData(uuid);
            if (entity == null) {
                return false; // Entity not found
            }
            entities.put(uuid, entity);
        }

        // Now apply all the relationships
        for (Map.Entry<UUID, Map<String, Set<UUID>>> entry : relationshipMap.entrySet()) {
            UUID sourceId = entry.getKey();
            LegacyEntityData sourceEntity = entities.get(sourceId);

            for (Map.Entry<String, Set<UUID>> relationshipEntry : entry.getValue().entrySet()) {
                String relationshipType = relationshipEntry.getKey();
                Set<UUID> targetIds = relationshipEntry.getValue();

                for (UUID targetId : targetIds) {
                    sourceEntity.addRelationship(relationshipType, targetId);
                }
            }
        }

        // Save all modified entities
        saveEntities(new ArrayList<>(entities.values()));

        return true;
    }

    /**
     * Finds entities matching multiple relationship criteria.
     *
     * <p>This method enables complex relationship queries involving multiple criteria.
     *
     * @param criteria  the list of relationship criteria to apply
     * @param queryType the logical operation to apply when combining criteria (AND, OR, AND_NOT)
     * @return a list of entities matching the criteria according to the query type
     */
    public List<LegacyEntityData> findEntitiesByMultipleRelationships(
            List<RelationshipCriteria> criteria, RelationshipQueryType queryType) {

        if (criteria == null || criteria.isEmpty()) {
            return Collections.emptyList();
        }

        // Get all entities from L1 cache
        Collection<LegacyEntityData> allEntities = getL1Cache().getResource().asMap().values();

        // Apply the appropriate logical operation based on the query type
        switch (queryType) {
            case AND:
                return allEntities.stream()
                        .filter(entity -> criteria.stream().allMatch(criterion ->
                                matchesCriterion(entity, criterion)))
                        .collect(Collectors.toList());

            case OR:
                return allEntities.stream()
                        .filter(entity -> criteria.stream().anyMatch(criterion ->
                                matchesCriterion(entity, criterion)))
                        .collect(Collectors.toList());

            case AND_NOT:
                if (criteria.size() == 1) {
                    // If only one criterion, just apply it
                    RelationshipCriteria criterion = criteria.getFirst();
                    return allEntities.stream()
                            .filter(entity -> matchesCriterion(entity, criterion))
                            .collect(Collectors.toList());
                } else {
                    // First criterion must match, others must not match
                    RelationshipCriteria firstCriterion = criteria.getFirst();
                    List<RelationshipCriteria> remainingCriteria = criteria.subList(1, criteria.size());

                    return allEntities.stream()
                            .filter(entity -> matchesCriterion(entity, firstCriterion) &&
                                    remainingCriteria.stream().noneMatch(criterion ->
                                            matchesCriterion(entity, criterion)))
                            .collect(Collectors.toList());
                }

            default:
                return Collections.emptyList();
        }
    }

    /**
     * Checks if an entity matches a relationship criterion.
     *
     * @param entity    the entity to check
     * @param criterion the relationship criterion to match against
     * @return true if the entity matches the criterion, false otherwise
     */
    private boolean matchesCriterion(LegacyEntityData entity, RelationshipCriteria criterion) {
        boolean hasRelationship = entity.hasRelationship(
                criterion.getRelationshipType(),
                criterion.getTargetEntityUuid());

        // If criterion is negated, invert the result
        return criterion.isNegated() != hasRelationship;
    }

    /**
     * Executes a set of relationship operations within a transaction.
     *
     * <p>This method allows multiple relationship operations to be executed as a single
     * logical unit. All entities modified during the transaction will be saved together
     * at the end of the transaction.
     *
     * @param callback the callback that executes the relationship operations
     * @return true if the transaction completed successfully, false if it failed
     */
    public boolean executeRelationshipTransaction(
            RelationshipTransactionCallback callback) {

        Map<UUID, LegacyEntityData> modifiedEntities = new HashMap<>();

        try {
            callback.execute(new RelationshipTransactionCallback.RelationshipTransaction() {
                @Override
                public RelationshipTransactionCallback.RelationshipTransaction addRelationship(
                        UUID sourceEntityId, String relationshipType, UUID targetEntityId) {

                    LegacyEntityData entity = getOrCacheEntity(sourceEntityId, modifiedEntities);
                    if (entity != null) {
                        entity.addRelationship(relationshipType, targetEntityId);
                    }
                    return this;
                }

                @Override
                public RelationshipTransactionCallback.RelationshipTransaction removeRelationship(
                        UUID sourceEntityId, String relationshipType, UUID targetEntityId) {

                    LegacyEntityData entity = getOrCacheEntity(sourceEntityId, modifiedEntities);
                    if (entity != null) {
                        entity.removeRelationship(relationshipType, targetEntityId);
                    }
                    return this;
                }

                @Override
                public RelationshipTransactionCallback.RelationshipTransaction createBidirectionalRelationship(
                        UUID entity1Id, String relationshipType1,
                        UUID entity2Id, String relationshipType2) {

                    LegacyEntityData entity1 = getOrCacheEntity(entity1Id, modifiedEntities);
                    LegacyEntityData entity2 = getOrCacheEntity(entity2Id, modifiedEntities);

                    if (entity1 != null && entity2 != null) {
                        entity1.addRelationship(relationshipType1, entity2Id);
                        entity2.addRelationship(relationshipType2, entity1Id);
                    }
                    return this;
                }
            });

            // Save all modified entities
            if (!modifiedEntities.isEmpty()) {
                saveEntities(new ArrayList<>(modifiedEntities.values()));
            }

            return true;
        } catch (Exception exception) {
            Log.error("Failed to execute relationship transaction", exception);
            return false;
        }
    }

    /**
     * Helper method to get an entity and cache it during a transaction.
     *
     * @param entityId the entity ID to get
     * @param cache    the transaction cache of modified entities
     * @return the entity, or null if not found
     */
    private LegacyEntityData getOrCacheEntity(UUID entityId, Map<UUID, LegacyEntityData> cache) {
        // Check if we've already loaded this entity in this transaction
        LegacyEntityData cachedEntity = cache.get(entityId);
        if (cachedEntity != null) {
            return cachedEntity;
        }

        // Load the entity and add it to our transaction cache
        LegacyEntityData entity = getEntityData(entityId);
        if (entity != null) {
            cache.put(entityId, entity);
        }

        return entity;
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
        // Create a latch to track completion of all persistence tasks
        AtomicInteger successCount = new AtomicInteger(0);
        int entityCount = getL1Cache().getResource().asMap().size();
        CountDownLatch completionLatch = new CountDownLatch(entityCount);

        // Persist all entities in L1 cache using tasks that signal the latch when done
        getL1Cache().getResource().asMap().forEach((uuid, data) -> {
            EntityDataPersistenceTask task = EntityDataPersistenceTask.of(
                    LockSettings.of(500, 500, TimeUnit.MILLISECONDS),
                    this,
                    uuid
            );

            // Add completion callback
            task.setCompletionCallback(success -> {
                if (success) {
                    successCount.incrementAndGet();
                }
                completionLatch.countDown();
            });

            // Start the task
            task.start();
        });

        // Wait for all tasks to complete with a timeout
        if (!completionLatch.await(5, TimeUnit.SECONDS)) {
            Log.warn("Timed out waiting for all entity persistence tasks to complete. " +
                    "Only " + successCount.get() + " out of " + entityCount + " entities were persisted.");
        }

        // Remove this service from the registry
        LEGACY_ENTITY_DATA_SERVICES.getResource().asMap().remove(name);

        // Shutdown L2 cache
        getL2Cache().shutdown();
    }

    /**
     * Sets the TTL (Time-To-Live) for an entity in the L2 cache.
     * If the entity doesn't exist in L2 cache, this method will have no effect.
     *
     * @param uuid the UUID of the entity
     * @param ttl  the duration after which the entity should expire
     * @return true if the TTL was set successfully, false otherwise
     */
    public boolean setEntityTTL(UUID uuid, Duration ttl) {
        if (uuid == null) {
            return false;
        }

        try {
            String entityKey = EntityRKeyUtil.getEntityKey(uuid, this);
            RedissonClient redissonClient = getL2Cache().getResource();

            // Check if the key exists
            boolean keyExists = redissonClient.getBucket(entityKey).isExists();
            if (!keyExists) {
                return false;
            }

            // Set TTL
            return redissonClient.getBucket(entityKey).expire(ttl);
        } catch (Exception exception) {
            Log.error("Failed to set TTL for entity " + uuid, exception);
            return false;
        }
    }

    /**
     * Sets the default TTL for an entity in the L2 cache.
     * If the entity doesn't exist in L2 cache, this method will have no effect.
     *
     * @param uuid the UUID of the entity
     * @return true if the TTL was set successfully, false otherwise
     */
    public boolean setEntityDefaultTTL(UUID uuid) {
        return setEntityTTL(uuid, DEFAULT_TTL_DURATION);
    }

    /**
     * Sets the default TTL for all entities in the L2 cache that don't already have a TTL.
     * This can be used to fix legacy data that was stored without TTL.
     *
     * @return the number of entities that had their TTL set
     */
    public int setDefaultTTLForAllEntities() {
        int count = 0;
        try {
            RedissonClient redissonClient = getL2Cache().getResource();
            RKeys keys = redissonClient.getKeys();
            String pattern = EntityRKeyUtil.getEntityKeyPattern(this);

            KeysScanOptions keysScanOptions = KeysScanOptions.defaults().pattern(pattern);

            for (String key : keys.getKeys(keysScanOptions)) {
                // If key has no TTL (remainTimeToLive < 0)
                RBucket<Object> bucket = redissonClient.getBucket(key);
                if (bucket.remainTimeToLive() < 0 && bucket.expire(DEFAULT_TTL_DURATION)) {
                    count++;
                }
            }
        } catch (Exception exception) {
            Log.error("Error setting default TTL for entities", exception);
        }
        return count;
    }
} 