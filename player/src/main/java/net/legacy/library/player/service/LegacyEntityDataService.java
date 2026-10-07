package net.legacy.library.player.service;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.query.MorphiaCursor;
import dev.morphia.query.filters.Filters;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.AccessLevel;
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
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.RelationshipCriteria;
import net.legacy.library.player.model.RelationshipQueryType;
import net.legacy.library.player.task.CoalescedPersistence;
import net.legacy.library.player.task.EntityDataPersistenceTask;
import net.legacy.library.player.task.EntityDataPersistenceTimerTask;
import net.legacy.library.player.task.PersistenceBacklog;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInvokeTask;
import net.legacy.library.player.task.redis.EntityRStreamPubTask;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import net.legacy.library.player.task.redis.impl.EntityStateUpdateRStreamAccepter;
import net.legacy.library.player.util.EntityRKeyUtil;
import net.legacy.library.player.util.OutageLog;
import net.legacy.library.player.util.TTLUtil;
import org.apache.commons.lang3.Validate;
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
import java.util.concurrent.ConcurrentHashMap;
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

    /**
     * Identifies this service instance in the Redis stream messages it publishes, so that its own accepters can
     * tell the updates it sent itself apart from those of other servers. A new id is generated for every instance.
     */
    private final UUID instanceId = UUID.randomUUID();
    private final String name;
    private final MongoDBConnectionConfig mongoDBConnectionConfig;
    private final FlexibleMultiLevelCacheService flexibleMultiLevelCacheService;
    private final VirtualThreadScheduledFuture entityDataPersistenceTimerTask;
    private final VirtualThreadScheduledFuture redisStreamAcceptTask;
    private final EntitySyncSettings syncSettings;

    /**
     * When the last update of each entity arrived from the stream, by {@link System#nanoTime()}, for a load of an
     * entity this server did not hold yet: an update arriving during the load was skipped, so it reads L2 again.
     */
    @Getter(AccessLevel.NONE)
    private final Cache<UUID, Long> receivedUpdates = Caffeine.newBuilder()
            .expireAfterWrite(1, TimeUnit.MINUTES)
            .maximumSize(100_000)
            .build();

    /**
     * The persistence the save methods ask for, coalesced so a burst of saves does not start one per save.
     */
    private final CoalescedPersistence savePersistence =
            new CoalescedPersistence(synced -> EntityDataPersistenceTask.of(LockSettings.of(500, 30000, TimeUnit.MILLISECONDS), this)
                    .savedOnly()
                    .start(synced));

    /**
     * How many persistence runs a save's publication waits for at most for its state to reach L2.
     */
    private static final int PUBLISH_WAITS = 10;

    /**
     * Whether the last persistence run reached L2. While it does not, as with Redis unreachable, saves stop waiting
     * to publish: their changes are put back and published once L2 is reached again.
     */
    @Getter(AccessLevel.NONE)
    private volatile boolean l2Reachable = true;

    /**
     * The entities whose changes were put back while L2 was unreachable, to publish once it is reached again.
     */
    @Getter(AccessLevel.NONE)
    private final Set<UUID> unpublished = ConcurrentHashMap.newKeySet();

    /**
     * Publications started and not yet done, so a shutdown can let them finish before closing Redis.
     */
    @Getter(AccessLevel.NONE)
    private final AtomicInteger publishing = new AtomicInteger();

    @Getter(AccessLevel.NONE)
    private final OutageLog l2ReadLog = new OutageLog("Reading an entity from L2");

    @Getter(AccessLevel.NONE)
    private final OutageLog l2WriteLog = new OutageLog("Writing entities to L2");

    /**
     * What this server saved and wrote and has yet to write to L2 and the database.
     */
    private final PersistenceBacklog persistenceBacklog = new PersistenceBacklog();

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
        this(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders,
                redisStreamAcceptInterval, ttl, EntitySyncSettings.defaults());
    }

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
     * @param syncSettings              the settings of the cross-server synchronization
     */
    public LegacyEntityDataService(String name, MongoDBConnectionConfig mongoDBConnectionConfig,
                                   Config config, Duration autoSaveInterval, List<String> basePackages,
                                   List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl,
                                   EntitySyncSettings syncSettings) {
        // Record all LegacyEntityDataService instances first
        Cache<String, LegacyEntityDataService> cache = LEGACY_ENTITY_DATA_SERVICES.getResource();

        if (cache.getIfPresent(name) != null) {
            throw new IllegalStateException("LegacyEntityDataService with name " + name + " already exists");
        }

        cache.put(name, this);

        this.name = name;
        this.mongoDBConnectionConfig = mongoDBConnectionConfig;
        this.syncSettings = syncSettings;

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

        // Auto save task
        this.entityDataPersistenceTimerTask =
                EntityDataPersistenceTimerTask.of(autoSaveInterval, autoSaveInterval,
                        LockSettings.of(500, 30000, TimeUnit.MILLISECONDS), this, ttl).start();

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
     * Creates a new {@link LegacyEntityDataService} with custom settings of the cross-server synchronization.
     *
     * @param name                      the unique name of the service
     * @param mongoDBConnectionConfig   the MongoDB connection configuration
     * @param config                    the Redis configuration
     * @param autoSaveInterval          the interval between auto-save operations
     * @param basePackages              the base packages to scan for accepter annotations
     * @param classLoaders              the class loaders to scan for accepter annotations
     * @param redisStreamAcceptInterval the interval for accepting messages from the Redis stream
     * @param ttl                       the custom TTL to apply to entity data in Redis
     * @param syncSettings              the settings of the cross-server synchronization
     * @return a new instance of {@link LegacyEntityDataService}
     */
    public static LegacyEntityDataService of(String name, MongoDBConnectionConfig mongoDBConnectionConfig, Config config,
                                             Duration autoSaveInterval, List<String> basePackages,
                                             List<ClassLoader> classLoaders, Duration redisStreamAcceptInterval, Duration ttl,
                                             EntitySyncSettings syncSettings) {
        return new LegacyEntityDataService(name, mongoDBConnectionConfig, config, autoSaveInterval, basePackages, classLoaders,
                redisStreamAcceptInterval, ttl, syncSettings);
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
     * <p>A failed publication is logged here, since most callers, such as {@link #saveEntity(LegacyEntityData)},
     * do not wait for the returned future.
     *
     * @param entityRStreamTask the task to be published to the Redis stream
     * @return a {@link CompletableFuture} instance tracking the execution status of the task
     */
    public CompletableFuture<?> pubEntityRStreamTask(EntityRStreamTask entityRStreamTask) {
        return EntityRStreamPubTask.of(this, entityRStreamTask).start().whenComplete((ignored, throwable) -> {
            if (throwable != null) {
                Log.error("Failed to publish entity stream task %s for service %s",
                        throwable, entityRStreamTask.getActionName(), name);
            }
        });
    }

    /**
     * Creates and starts a new entity stream task.
     *
     * <p>This method creates a new {@link EntityRStreamTask} with the provided parameters
     * and starts it immediately, returning the resulting {@link ScheduledTask}.
     *
     * @param actionName     the name of the task
     * @param data           the data payload of the task
     * @param expirationTime the duration after which the task expires
     * @return a {@link CompletableFuture} instance tracking the execution status of the task
     */
    public CompletableFuture<?> createEntityStreamTask(String actionName, String data, Duration expirationTime) {
        return pubEntityRStreamTask(EntityRStreamTask.of(actionName, data, expirationTime));
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

        // One read of a value always written whole, so no read lock, which would wait behind writes of the entity
        Object stored = l2Cache.getResource().getBucket(key).get();
        if (!(stored instanceof String jsonData) || jsonData.isEmpty()) {
            return Optional.empty();
        }

        // Deserialize JSON to LegacyEntityData
        return Optional.ofNullable(SimplixSerializer.deserialize(jsonData, LegacyEntityData.class));
    }

    /**
     * Retrieves the {@link LegacyEntityData} from the database.
     *
     * @param uuid the unique identifier of the entity
     * @return the entity data retrieved from the database, or {@code null} if not found
     */
    public LegacyEntityData getFromDatabase(UUID uuid) {
        @Cleanup
        MorphiaCursor<LegacyEntityData> queryResult = mongoDBConnectionConfig.getDatastore()
                .find(LegacyEntityData.class)
                .filter(Filters.eq("_id", uuid))
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
     *   <li>Immediately merges the provided {@link LegacyEntityData} into the L1 cache (Caffeine), and the state in
     *       the L2 cache into it, one attribute and relationship at a time by their stamps (see
     *       {@link LegacyEntityData#mergeChangesFrom}). An entity not yet cached is cached as it is; a cached one stays
     *       the same instance. This makes the data instantly available for subsequent reads via
     *       {@link #getEntityData(UUID)} within the same service instance.</li>
     *   <li>Schedules an asynchronous task ({@link EntityDataPersistenceTask}) to persist the data
     *       to the L2 cache (Redis) with the configured TTL and to the underlying database (MongoDB).</li>
     *   <li>Publishes the merged state, with its stamps, to the Redis stream; every other server merges it into its
     *       own L1 cache for this entity. This ensures cross-server cache consistency.</li>
     * </ol>
     *
     * <p><b>Important:</b> This method returns immediately after scheduling the persistence task.
     * It does <em>not</em> wait for the data to be written to Redis or MongoDB. Persistence is eventual.
     * Use this method when immediate persistence guarantees are not strictly required.
     *
     * @param entityData the entity data to save and schedule for persistence
     */
    public void saveEntity(LegacyEntityData entityData) {
        Validate.notNull(entityData, "Entity data cannot be null.");

        LegacyEntityData cachedEntity = mergeIntoL1Cache(entityData, true);

        /*
         * The changes are taken now and published once a persistence run has put them in L2: a server that loads the
         * entity from L2 after reading the message then has them, though it ignored the message for not holding it
         */
        publishOnceInL2(cachedEntity);
    }

    /**
     * Saves entity data to the L1 cache and schedules asynchronous persistence to L2 cache and database
     * without publishing to Redis Stream.
     *
     * <p>This method is similar to {@link #saveEntity(LegacyEntityData)} but does not publish
     * entity updates to the Redis Stream. It is used internally to avoid infinite loops when
     * handling version conflicts during entity updates.
     *
     * @param entityData the entity data to save and schedule for persistence
     */
    public void saveEntityWithoutRepublish(LegacyEntityData entityData) {
        Validate.notNull(entityData, "Entity data cannot be null.");

        persistenceBacklog.saved(mergeIntoL1Cache(entityData, false).getUuid());

        // Schedule persistence to L2 and DB
        savePersistence.request();
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
     *   <li>Publishes entity data updates to the Redis stream for each entity to notify other servers to update
     *       their L1 cache. This ensures cross-server cache consistency.</li>
     * </ol>
     *
     * <p><b>Important:</b> This method returns immediately after scheduling the persistence task.
     * It does <em>not</em> wait for the data to be written to Redis or MongoDB. Persistence is eventual.
     * This is more efficient than calling {@link #saveEntity(LegacyEntityData)} multiple times as it batches the persistence task.
     *
     * @param entityDataList the list of entity data to save and schedule for persistence
     */
    public void saveEntities(List<LegacyEntityData> entityDataList) {
        Validate.notEmpty(entityDataList, "Entity data list cannot be empty.");

        // Each published once a persistence run has put it in L2, as in saveEntity; the runs they ask for coalesce
        entityDataList.stream()
                .filter(Objects::nonNull)
                .map(entityData -> mergeIntoL1Cache(entityData, true))
                .forEach(this::publishOnceInL2);
    }

    /**
     * Saves multiple entities to the L1 cache and schedules asynchronous persistence without republishing.
     *
     * <p>This method is similar to {@link #saveEntities(List)} but does not publish
     * entity updates to the Redis Stream. It is used internally to avoid infinite loops when
     * handling version conflicts during entity updates.
     *
     * @param entityDataList the list of entity data to save and schedule for persistence
     */
    public void saveEntitiesWithoutRepublish(List<LegacyEntityData> entityDataList) {
        Validate.notEmpty(entityDataList, "Entity data list cannot be empty.");

        entityDataList.stream()
                .filter(Objects::nonNull)
                .forEach(entityData -> persistenceBacklog.saved(mergeIntoL1Cache(entityData, false).getUuid()));

        // Schedule persistence to L2 and DB
        savePersistence.request();
    }

    /**
     * Takes the changes of a saved entity not yet published, and publishes them once a persistence run has put them in
     * L2.
     *
     * <p>Taken now, before the save is noted for the persistence, so the state written for the save holds them: a
     * server that loads the entity from L2 after reading the message, having ignored it for not holding the entity,
     * then has them. A run that could not write the entity, or that started before the save, is followed by another,
     * a few at most. A failed publication puts the changes back for the next one.
     *
     * @param cachedEntity the entity as cached in L1
     */
    private void publishOnceInL2(LegacyEntityData cachedEntity) {
        LegacyEntityData.Changes changes = cachedEntity.takeUnpublishedChanges();
        long ticket = persistenceBacklog.saved(cachedEntity.getUuid());
        EntityRStreamTask update = EntityStateUpdateRStreamAccepter.createRStreamTask(
                cachedEntity.getUuid(), changes, instanceId, syncSettings.getUpdateExpiration()
        );
        publishOnceInL2(cachedEntity, changes, ticket, update, 1);
    }

    private void publishOnceInL2(LegacyEntityData cachedEntity, LegacyEntityData.Changes changes, long ticket,
                                 EntityRStreamTask update, int attempt) {
        savePersistence.request().whenComplete((ignored, throwable) -> {
            boolean written = persistenceBacklog.isWritten(cachedEntity.getUuid(), ticket);

            // L2 unreachable: nothing waits; the changes go with the first publication once it is reached again
            if (!written && !l2Reachable) {
                cachedEntity.restoreUnpublishedChanges(changes);
                unpublished.add(cachedEntity.getUuid());
                return;
            }
            if (!written && attempt < PUBLISH_WAITS) {
                publishOnceInL2(cachedEntity, changes, ticket, update, attempt + 1);
                return;
            }

            publishing.incrementAndGet();
            pubEntityRStreamTask(update).whenComplete((published, failure) -> {
                publishing.decrementAndGet();
                if (failure != null) {
                    cachedEntity.restoreUnpublishedChanges(changes);
                    unpublished.add(cachedEntity.getUuid());
                }
            });
        });
    }

    /**
     * Records whether a persistence run reached L2, called by {@link EntityDataPersistenceTask}. Once L2 is reached
     * again after an outage, the changes put back meanwhile are published.
     *
     * @param reached whether the run wrote to L2
     */
    public void reportL2Write(boolean reached) {
        boolean wasReachable = l2Reachable;
        l2Reachable = reached;
        if (!reached) {
            l2WriteLog.failed(new IllegalStateException("A persistence run wrote nothing to L2"));
            return;
        }
        l2WriteLog.succeeded();

        if (!wasReachable || !unpublished.isEmpty()) {
            Cache<UUID, LegacyEntityData> l1Cache = getL1Cache().getResource();
            for (UUID uuid : List.copyOf(unpublished)) {
                unpublished.remove(uuid);
                LegacyEntityData cachedEntity = l1Cache.getIfPresent(uuid);
                if (cachedEntity != null) {
                    publishOnceInL2(cachedEntity);
                }
            }
        }
    }

    /**
     * Gets the time before which removal stamps are forgotten, by {@link EntitySyncSettings#getTombstoneRetention()}.
     *
     * <p>Stamps carry the clock of the server that made the change, so a server whose clock runs ahead or behind
     * shifts when its removals are forgotten elsewhere by that much; against a retention of hours, a skew of seconds
     * or minutes does not matter.
     *
     * @return the cutoff in milliseconds since epoch
     */
    public long tombstoneCutoff() {
        return System.currentTimeMillis() - syncSettings.getTombstoneRetention().toMillis();
    }

    /**
     * Merges a saved entity into the one cached in L1 and with the state in L2, and caches the result.
     *
     * <p>The cached instance stays the one in L1, so every holder of it keeps seeing the saved state. Both merges go
     * one attribute and relationship at a time by their stamps (see {@link LegacyEntityData#mergeChangesFrom}):
     * a stale copy cannot undo newer changes, and the changes another server already persisted to L2 are taken in,
     * so a save based on a state older than L2 does not overwrite them.
     *
     * <p>The saves that do not publish skip the L2 merge, as they did before stamps: the write to L2 merges with what
     * L2 holds in any case (see {@link net.legacy.library.player.task.L1ToL2EntityDataSyncTask}).
     *
     * @param entityData the entity being saved
     * @param mergeL2    whether to merge in the state persisted to L2
     * @return the cached instance holding the merged state
     */
    private LegacyEntityData mergeIntoL1Cache(LegacyEntityData entityData, boolean mergeL2) {
        Cache<UUID, LegacyEntityData> l1Cache = getL1Cache().getResource();
        LegacyEntityData cachedEntity = l1Cache.asMap().merge(entityData.getUuid(), entityData, (existing, saved) -> {
            existing.mergeChangesFrom(saved, tombstoneCutoff());
            return existing;
        });

        if (!mergeL2) {
            return cachedEntity;
        }

        try {
            /*
             * One read of a value always written whole, so no read lock: taking it would wait behind the L2 writes of
             * an entity saved often, and fail the merge when they take long
             */
            Object stored = getL2Cache().getResource().getBucket(EntityRKeyUtil.getEntityKey(cachedEntity.getUuid(), this)).get();
            if (stored instanceof String storedString && !storedString.isEmpty()) {
                cachedEntity.mergeChangesFrom(SimplixSerializer.deserialize(storedString, LegacyEntityData.class), tombstoneCutoff());
            }
            l2ReadLog.succeeded();
        } catch (RuntimeException exception) {
            // Redis is unreachable; the save goes on with the cached state, and the write to L2 merges L2 in later
            l2ReadLog.failed(exception);
        }
        return cachedEntity;
    }

    /**
     * Notes that an update of an entity arrived from the stream, before an accepter checks whether the entity is held
     * in L1: a load of it running meanwhile then reads L2 once more, since the update was published once it was in L2.
     *
     * @param uuid the entity's UUID
     */
    public void noteUpdateReceived(UUID uuid) {
        receivedUpdates.put(uuid, System.nanoTime());
    }

    /**
     * Forgets, in every entity cached in L1, the stamps of removals older than the tombstone retention (see
     * {@link EntitySyncSettings#getTombstoneRetention()} and {@link LegacyEntityData#pruneTombstones(long)}).
     *
     * <p>Called by the periodic persistence before it persists, so the persisted state drops them too.
     *
     * @return how many stamps were forgotten
     */
    public int pruneTombstones() {
        long cutoff = tombstoneCutoff();
        return getL1Cache().getResource().asMap().values().stream()
                .mapToInt(entity -> entity.pruneTombstones(cutoff))
                .sum();
    }

    /**
     * Retrieves entity data using the multi-level cache and database.
     *
     * @param uuid the unique identifier of the entity
     * @return the entity data, or {@code null} if not found
     */
    public LegacyEntityData getEntityData(UUID uuid) {
        // Check L1 cache first
        Optional<LegacyEntityData> dataFromL1Cache = getFromL1Cache(uuid);
        if (dataFromL1Cache.isPresent()) {
            return dataFromL1Cache.get();
        }

        // Check L2 cache next, then the database
        long loadStart = System.nanoTime();
        LegacyEntityData entityData = getFromL2Cache(uuid).orElseGet(() -> getFromDatabase(uuid));
        if (entityData == null) {
            return null;
        }

        /*
         * Store in L1 cache for future access. Another thread may have cached the entity meanwhile, a save for one;
         * the loaded state is merged into that instance instead of replacing it
         */
        LegacyEntityData cached = getL1Cache().getResource().asMap().merge(uuid, entityData, (current, loaded) -> {
            current.mergeChangesFrom(loaded, tombstoneCutoff());
            return current;
        });

        // An update that arrived during the load was skipped, the entity not being held yet; L2 has it by now
        Long received = receivedUpdates.getIfPresent(uuid);
        if (received != null && received - loadStart >= 0) {
            getFromL2Cache(uuid).ifPresent(stored -> cached.mergeChangesFrom(stored, tombstoneCutoff()));
        }
        return cached;
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
     * @return the entity, or {@code null} if not found
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
     * Retrieves all {@link LegacyEntityData} objects from the database and populates the L1 cache.
     *
     * <p>Unlike the {@code getEntityData(UUID uuid)} method, this method avoids
     * the typical lazy-loading pattern by eagerly fetching all entity data from the database.
     * This makes it suitable for "pre-warming" the L1 cache, ensuring that frequently
     * accessed data is readily available for subsequent lookups.
     *
     * <p><b>Performance Warning:</b> This operation can be resource-intensive, especially
     * with a large number of entity data entries, as it involves loading all data into memory.
     * Therefore, it is generally NOT recommended for use in synchronous, performance-critical
     * environments or on the main thread, as it can lead to significant delays and
     * potential bottlenecks. Consider using this method sparingly and in asynchronous
     * contexts where startup latency is acceptable for the benefit of later read performance.
     *
     * @return a {@link List} containing all {@link LegacyEntityData} objects found in the database.
     *         Returns an empty list if no entity data is found.
     */
    public List<LegacyEntityData> getAllLegacyEntityData() {
        // Get all LegacyEntityData objects
        @Cleanup
        MorphiaCursor<LegacyEntityData> queryResult = mongoDBConnectionConfig.getDatastore()
                .find(LegacyEntityData.class)
                .iterator();

        if (!queryResult.hasNext()) {
            return Collections.emptyList();
        }

        List<LegacyEntityData> resultList = queryResult.toList();

        // L1
        Cache<UUID, LegacyEntityData> l1Cache = getL1Cache().getResource();
        for (LegacyEntityData entityData : resultList) {
            l1Cache.put(entityData.getUuid(), entityData);
        }

        return resultList;
    }

    /**
     * Shuts down the service, ensuring all data is properly persisted.
     *
     * @throws InterruptedException if the shutdown process is interrupted
     */
    public void shutdown() throws InterruptedException {
        // Nothing new starts: no scheduled persistence, no more stream reads
        entityDataPersistenceTimerTask.cancel(false);
        redisStreamAcceptTask.cancel(false);

        // A persistence a save started may still run; the final one starts after it, so it cannot be skipped by it
        savePersistence.close();
        if (!savePersistence.awaitIdle(Duration.ofMinutes(2))) {
            Log.warn("An entity persistence started by a save was still running after 2 minutes; persisting once more anyway");
        }

        // Create a latch to track completion of persistence task
        CountDownLatch completionLatch = new CountDownLatch(1);

        // The final run waits longer for the lock another server may hold, and keeps it long enough to finish
        EntityDataPersistenceTask task = EntityDataPersistenceTask.of(
                LockSettings.of(10, 60, TimeUnit.SECONDS), this
        ).asFinalRun();

        // Saves made since the close publish once this run has put them in L2
        CompletableFuture<Void> synced = new CompletableFuture<>();
        synced.whenComplete((ignored, throwable) -> savePersistence.completeFinalRun());
        task.start(synced).whenComplete((ignored, throwable) -> completionLatch.countDown());

        // Wait for the task to complete with a timeout
        if (!completionLatch.await(2, TimeUnit.MINUTES)) {
            Log.warn("Timed out waiting for entity persistence task to complete!!");
        }

        // The publications that run started go out before Redis is closed
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (publishing.get() > 0 && System.nanoTime() < deadline) {
            Thread.sleep(20);
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
            RBucket<Object> bucket = redissonClient.getBucket(entityKey);

            if (!bucket.isExists()) {
                return false;
            }

            return TTLUtil.setReliableTTL(redissonClient, entityKey, ttl.getSeconds());
        } catch (Exception exception) {
            Log.error("Failed to set TTL for entity %s", uuid, exception);
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
                if (TTLUtil.processBucketTTL(redissonClient, key, DEFAULT_TTL_DURATION.getSeconds())) {
                    count++;
                }
            }
        } catch (Exception exception) {
            Log.error("Error setting default TTL for entities", exception);
        }
        return count;
    }

}