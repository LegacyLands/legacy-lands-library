package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.Datastore;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * Task for persisting entity data to the L2 cache and database.
 *
 * <p>This task handles the asynchronous persistence of entity data from the L1 cache
 * to both the L2 cache (Redis) and the underlying database. It ensures that data
 * is properly synchronized across all storage layers.
 *
 * <p>The task acquires an exclusive lock to prevent concurrent persistence operations,
 * and handles both individual entity persistence and bulk persistence operations.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@RequiredArgsConstructor
public class EntityDataPersistenceTask implements TaskInterface<CompletableFuture<?>> {
    private final LockSettings lockSettings;
    private final LegacyEntityDataService service;
    private final Set<UUID> entityUuids;
    private final int limit;
    private final Duration ttl;
    private CompletionCallback completionCallback;

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for a specific entity.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param entityUuid   the UUID of the entity whose data should be persisted
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, UUID entityUuid) {
        return new EntityDataPersistenceTask(lockSettings, service,
                entityUuid != null ? Collections.singleton(entityUuid) : null, 0, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for a specific entity with custom TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param entityUuid   the UUID of the entity whose data should be persisted
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, UUID entityUuid, Duration ttl) {
        return new EntityDataPersistenceTask(lockSettings, service,
                entityUuid != null ? Collections.singleton(entityUuid) : null, 0, ttl);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for multiple specific entities.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param entityUuids  the set of UUIDs of entities whose data should be persisted
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, Set<UUID> entityUuids) {
        return new EntityDataPersistenceTask(lockSettings, service, entityUuids, 0, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for multiple specific entities with custom TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param entityUuids  the set of UUIDs of entities whose data should be persisted
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, Set<UUID> entityUuids, Duration ttl) {
        return new EntityDataPersistenceTask(lockSettings, service, entityUuids, 0, ttl);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for bulk persistence.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param limit        the maximum number of entity data entries to process
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, int limit) {
        return new EntityDataPersistenceTask(lockSettings, service, null, limit, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for bulk persistence with custom TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param limit        the maximum number of entity data entries to process
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, int limit, Duration ttl) {
        return new EntityDataPersistenceTask(lockSettings, service, null, limit, ttl);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for bulk persistence with default limit.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service) {
        return new EntityDataPersistenceTask(lockSettings, service, null, 1000, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for bulk persistence with default limit and custom TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, Duration ttl) {
        return new EntityDataPersistenceTask(lockSettings, service, null, 1000, ttl);
    }

    /**
     * Sets a callback to be invoked when the task completes.
     *
     * @param callback the callback to invoke
     * @return this task instance for method chaining
     */
    public EntityDataPersistenceTask setCompletionCallback(CompletionCallback callback) {
        this.completionCallback = callback;
        return this;
    }

    /**
     * Executes the persistence task, transferring entity data from L1 cache to the L2 cache and database.
     *
     * <p>If entity UUIDs are provided, it persists only those specific entities.
     * Otherwise, it performs a bulk persistence of all entity data in the L2 cache.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            boolean success = false;
            try {
                // If specific entities are provided, persist just those entities
                if (entityUuids != null && !entityUuids.isEmpty()) {
                    persistSpecificEntities();
                } else {
                    // Otherwise, perform bulk persistence
                    persistAllEntities();
                }
                success = true;
            } catch (Exception exception) {
                Log.error("Error during entity persistence", exception);
            } finally {
                // Notify completion if a callback is registered
                if (completionCallback != null) {
                    completionCallback.accept(success);
                }
            }
        });
    }

    /**
     * Persists specific entities' data from L1 cache to L2 cache and database.
     */
    private void persistSpecificEntities() {
        for (UUID uuid : entityUuids) {
            // Get entity from L1 cache
            LegacyEntityData entityData = service.getFromL1Cache(uuid).orElse(null);
            if (entityData == null) {
                continue;
            }

            // Persist to L2 cache (Redis)
            String entityKey = EntityRKeyUtil.getEntityKey(uuid, service);
            String serializedData = SimplixSerializer.serialize(entityData).toString();

            // Use getWithType with writeLock to store data in L2 cache
            RedisCacheServiceInterface l2Cache = service.getL2Cache();
            l2Cache.getWithType(
                    client -> client.getReadWriteLock(EntityRKeyUtil.getEntityReadWriteLockKey(entityKey)).writeLock(),
                    client -> null,
                    () -> {
                        // Store operation
                        RedissonClient client = l2Cache.getResource();
                        client.getBucket(entityKey).set(serializedData);

                        // Set TTL based on custom TTL if provided, otherwise use default
                        Duration ttlToApply = ttl != null ? ttl : LegacyEntityDataService.DEFAULT_TTL_DURATION;
                        boolean expireSuccess = client.getBucket(entityKey).expire(ttlToApply);
                        if (!expireSuccess) {
                            Log.warn(String.format("Failed to set TTL for entity %s", uuid));
                        }

                        return null;
                    },
                    null,
                    false,
                    lockSettings
            );

            // Persist to database
            service.getMongoDBConnectionConfig().getDatastore().save(entityData);
        }
    }

    /**
     * Persists all entity data from L2 cache to the database.
     */
    private void persistAllEntities() {
        RedisCacheServiceInterface l2Cache = service.getL2Cache();
        RedissonClient redissonClient = l2Cache.getResource();

        // Persistence
        String lockKey = EntityRKeyUtil.getEntityLockKey(service, "persistence-lock");
        RLock lock = redissonClient.getLock(lockKey);

        try {
            // Exclusive
            if (!lock.tryLock(lockSettings.getWaitTime(), lockSettings.getLeaseTime(), lockSettings.getTimeUnit())) {
                throw new RuntimeException("Could not acquire lock: " + lock.getName());
            }

            try {
                Datastore datastore = service.getMongoDBConnectionConfig().getDatastore();

                /*
                 * Get all entity keys and deserialize and save all LegacyEntityData
                 */
                RKeys keys = redissonClient.getKeys();
                KeysScanOptions keysScanOptions =
                        KeysScanOptions.defaults()
                                .pattern(EntityRKeyUtil.getEntityKeyPattern(service))
                                .limit(limit);

                for (String key : keys.getKeys(keysScanOptions)) {
                    if (keys.getType(key) != RType.OBJECT) {
                        continue;
                    }

                    String entityDataString =
                            l2Cache.getWithType(client -> client.getBucket(key).get(), () -> "", null, false);

                    if (!entityDataString.isEmpty()) {
                        datastore.save(SimplixSerializer.deserialize(entityDataString, LegacyEntityData.class));

                        // Set TTL for this entity based on provided custom TTL or current TTL status
                        if (ttl != null) {
                            // If custom TTL is provided, always apply it
                            boolean expireSuccess = redissonClient.getBucket(key).expire(ttl);
                            if (!expireSuccess) {
                                Log.warn(String.format("Failed to set custom TTL for entity key: %s", key));
                            }
                        } else {
                            // Check if key has no TTL and set default if needed
                            long currentTtl = redissonClient.getBucket(key).remainTimeToLive();
                            if (currentTtl < 0) {
                                boolean expireSuccess = redissonClient.getBucket(key).expire(LegacyEntityDataService.DEFAULT_TTL_DURATION);
                                if (!expireSuccess) {
                                    Log.warn(String.format("Failed to set default TTL for entity key: %s", key));
                                }
                            }
                        }
                    } else {
                        Log.error("The key value is not expected to be null, this should not happen!! key: " + key);
                    }
                }
            } finally {
                // Ensure the lock is always released
                lock.forceUnlock();
            }
        } catch (InterruptedException exception) {
            Log.error(exception);
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            Log.error(exception);
        }
    }

    /**
     * Callback interface for notifying task completion.
     */
    public interface CompletionCallback extends Consumer<Boolean> {
        /**
         * Called when the task completes.
         *
         * @param success true if the task completed successfully, false otherwise
         */
        void accept(Boolean success);
    }
} 