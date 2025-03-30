package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import dev.morphia.Datastore;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
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

import java.util.UUID;

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
public class EntityDataPersistenceTask implements TaskInterface {
    private final LockSettings lockSettings;
    private final LegacyEntityDataService service;
    private final UUID entityUuid;
    private final int limit;
    
    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for a specific entity.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param entityUuid   the UUID of the entity whose data should be persisted
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, UUID entityUuid) {
        return new EntityDataPersistenceTask(lockSettings, service, entityUuid, 0);
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
        return new EntityDataPersistenceTask(lockSettings, service, null, limit);
    }
    
    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} for bulk persistence with default limit.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service) {
        return new EntityDataPersistenceTask(lockSettings, service, null, 1000);
    }
    
    /**
     * Executes the persistence task, transferring entity data from L1 cache to the L2 cache and database.
     *
     * <p>If an entity UUID is provided, it persists only that specific entity.
     * Otherwise, it performs a bulk persistence of all entity data in the L2 cache.
     *
     * @return a {@link ScheduledTask} representing the running task
     */
    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            // If a specific entity is provided, persist just that entity
            if (entityUuid != null) {
                persistSingleEntity();
            } else {
                // Otherwise, perform bulk persistence
                persistAllEntities();
            }
        });
    }
    
    /**
     * Persists a single entity's data from L1 cache to L2 cache and database.
     */
    private void persistSingleEntity() {
        // Get entity from L1 cache
        LegacyEntityData entityData = service.getFromL1Cache(entityUuid).orElse(null);
        if (entityData == null) {
            return;
        }
        
        // Persist to L2 cache (Redis)
        String entityKey = EntityRKeyUtil.getEntityKey(entityUuid, service);
        String serializedData = SimplixSerializer.serialize(entityData).toString();
        
        // Use getWithType with writeLock to store data in L2 cache
        RedisCacheServiceInterface l2Cache = service.getL2Cache();
        l2Cache.getWithType(
            client -> client.getReadWriteLock(EntityRKeyUtil.getEntityReadWriteLockKey(entityKey)).writeLock(),
            client -> null,
            () -> {
                // Store operation
                l2Cache.getResource().getBucket(entityKey).set(serializedData);
                return null;
            },
            null,
            false,
            lockSettings
        );
        
        // Persist to database
        service.getMongoDBConnectionConfig().getDatastore().save(entityData);
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
} 