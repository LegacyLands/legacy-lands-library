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
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RKeys;
import org.redisson.api.RLock;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;

/**
 * Task for persisting entity data from L2 cache (Redis) to the database.
 *
 * <p>This task handles the asynchronous persistence of entity data from the L2 cache (Redis)
 * to the underlying database. It ensures that data is properly persisted.
 *
 * <p>The task acquires an exclusive lock to prevent concurrent persistence operations,
 * and handles the bulk persistence of entity data.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@RequiredArgsConstructor
public class EntityDataPersistenceTask implements TaskInterface<CompletableFuture<?>> {
    private final LockSettings lockSettings;
    private final LegacyEntityDataService legacyEntityDataService;
    private final int limit;
    private final Duration ttl;

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask}.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service) {
        return of(lockSettings, service, 1000, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} with custom TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, Duration ttl) {
        return of(lockSettings, service, 1000, ttl);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} with custom limit.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param limit        the maximum number of entity data entries to process
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, int limit) {
        return of(lockSettings, service, limit, null);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTask} with custom limit and TTL.
     *
     * @param lockSettings the settings for lock acquisition
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param limit        the maximum number of entity data entries to process
     * @param ttl          the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link EntityDataPersistenceTask}
     */
    public static EntityDataPersistenceTask of(LockSettings lockSettings, LegacyEntityDataService service, int limit, Duration ttl) {
        return new EntityDataPersistenceTask(lockSettings, service, limit, ttl);
    }

    /**
     * Executes the persistence task, transferring entity data from L2 cache to the database.
     *
     * <p>Synchronizes L1 cache to L2 cache, then persists all entity data from L2 cache to the database.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<Void> start() {
        CompletableFuture<?> task = L1ToL2EntityDataSyncTask.of(legacyEntityDataService).start();

        task.whenComplete((result, throwable) -> {
            if (throwable != null) {
                Log.error("L1ToL2EntityDataSyncTask failed to sync L1 cache to L2 cache: %s", throwable.getMessage());
            }
        });

        return task.thenCompose(ignored -> submitWithVirtualThreadAsync(() -> {
            try {
                persistEntities();
            } catch (Exception exception) {
                Log.error("EntityDataPersistenceTask failed to persist player data. error: %s", exception.getMessage());
                throw new CompletionException("Failed to persist player data", exception);
            }
        }));
    }

    /**
     * Persists entity data from L2 cache to the database.
     */
    private void persistEntities() {
        RedisCacheServiceInterface l2Cache = legacyEntityDataService.getL2Cache();
        RedissonClient redissonClient = l2Cache.getResource();
        Datastore datastore = legacyEntityDataService.getMongoDBConnectionConfig().getDatastore();

        // Get Redis persistence lock
        String lockKey = EntityRKeyUtil.getEntityLockKey(legacyEntityDataService, "persistence-lock");
        RLock lock = redissonClient.getLock(lockKey);

        try {
            if (!lock.tryLock(lockSettings.getWaitTime(), lockSettings.getLeaseTime(), lockSettings.getTimeUnit())) {
                throw new RuntimeException("Could not acquire lock: " + lock.getName());
            }
            processEntitiesInL2Cache(l2Cache, redissonClient, datastore);
        } catch (InterruptedException exception) {
            Log.error("Task interrupted during persistence", exception);
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            Log.error("Exception during entity persistence", exception);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                try {
                    lock.unlock();
                } catch (Exception unlockException) {
                    Log.warn("Failed to unlock persistence lock", unlockException);
                }
            }
        }
    }

    /**
     * Processes all entity data from the L2 cache and saves it to the database.
     *
     * @param l2Cache        the Redis cache service
     * @param redissonClient the Redisson client
     * @param datastore      the MongoDB datastore
     */
    private void processEntitiesInL2Cache(RedisCacheServiceInterface l2Cache,
                                          RedissonClient redissonClient,
                                          Datastore datastore) {
        RKeys keys = redissonClient.getKeys();
        KeysScanOptions keysScanOptions = KeysScanOptions.defaults()
                .pattern(EntityRKeyUtil.getEntityKeyPattern(legacyEntityDataService))
                .limit(limit);

        for (String key : keys.getKeys(keysScanOptions)) {
            if (keys.getType(key) != RType.OBJECT) {
                continue;
            }

            String entityDataString = l2Cache.getWithType(
                    client -> client.getBucket(key).get(),
                    () -> "",
                    null,
                    false
            );

            if (entityDataString.isEmpty()) {
                Log.error("The Entity data key value is not expected to be null, this should not happen!! key: %s", key);
                continue;
            }

            // Save to database
            datastore.save(SimplixSerializer.deserialize(entityDataString, LegacyEntityData.class));

            // Update TTL
            if (ttl != null) {
                TTLUtil.setTTLIfMissing(redissonClient, key, ttl.getSeconds());
            } else {
                TTLUtil.setTTLIfMissing(redissonClient, key, LegacyEntityDataService.DEFAULT_TTL_DURATION.getSeconds());
            }
        }
    }
} 