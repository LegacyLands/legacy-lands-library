package net.legacy.library.player.task;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;

import java.time.Duration;
import java.util.Collections;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task responsible for synchronizing entity data from the first-level (L1) cache to the
 * second-level (L2) Redis cache.
 *
 * <p>This task iterates through the L1 cache, serializes the {@link LegacyEntityData},
 * and updates the corresponding entries in the L2 cache. It ensures that the data
 * in both cache levels remains consistent.
 *
 * @author qwq-dev
 */
@RequiredArgsConstructor
public class L1ToL2EntityDataSyncTask implements TaskInterface<CompletableFuture<?>> {

    private final Set<UUID> entityUuids;
    private final LegacyEntityDataService service;
    private final Duration ttl;

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} without specifying UUIDs.
     *
     * <p>This variant synchronizes all entity data in the L1 cache to the L2 cache.
     *
     * @param service the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(LegacyEntityDataService service) {
        return new L1ToL2EntityDataSyncTask(null, service, null);
    }

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} without specifying UUIDs but with custom TTL.
     *
     * <p>This variant synchronizes all entity data in the L1 cache to the L2 cache with the specified TTL.
     *
     * @param service the {@link LegacyEntityDataService} instance to use
     * @param ttl     the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(LegacyEntityDataService service, Duration ttl) {
        return new L1ToL2EntityDataSyncTask(null, service, ttl);
    }

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} for a specific entity.
     *
     * @param entityUuid the UUID of the entity whose data is to be synchronized
     * @param service    the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(UUID entityUuid, LegacyEntityDataService service) {
        return new L1ToL2EntityDataSyncTask(
                entityUuid != null ? Collections.singleton(entityUuid) : null,
                service,
                null);
    }

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} for a specific entity with custom TTL.
     *
     * @param entityUuid the UUID of the entity whose data is to be synchronized
     * @param service    the {@link LegacyEntityDataService} instance to use
     * @param ttl        the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(UUID entityUuid, LegacyEntityDataService service, Duration ttl) {
        return new L1ToL2EntityDataSyncTask(
                entityUuid != null ? Collections.singleton(entityUuid) : null,
                service,
                ttl);
    }

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} for multiple specific entities.
     *
     * @param entityUuids the set of UUIDs of entities whose data is to be synchronized
     * @param service     the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(Set<UUID> entityUuids, LegacyEntityDataService service) {
        return new L1ToL2EntityDataSyncTask(entityUuids, service, null);
    }

    /**
     * Factory method to create a new {@link L1ToL2EntityDataSyncTask} for multiple specific entities with custom TTL.
     *
     * @param entityUuids the set of UUIDs of entities whose data is to be synchronized
     * @param service     the {@link LegacyEntityDataService} instance to use
     * @param ttl         the custom Time-To-Live duration to set for entity data in Redis
     * @return a new instance of {@link L1ToL2EntityDataSyncTask}
     */
    public static L1ToL2EntityDataSyncTask of(Set<UUID> entityUuids, LegacyEntityDataService service, Duration ttl) {
        return new L1ToL2EntityDataSyncTask(entityUuids, service, ttl);
    }

    /**
     * Executes the synchronization task, transferring entity data from L1 cache to L2 cache.
     *
     * <p>If specific entity UUIDs are provided, only those entities' data is synchronized.
     * Otherwise, all entity data in the L1 cache is synchronized.
     *
     * <p>Ensures that only changed data is updated in the L2 cache to optimize performance.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            CacheServiceInterface<Cache<UUID, LegacyEntityData>, LegacyEntityData> l1Cache = service.getL1Cache();
            RedisCacheServiceInterface l2Cache = service.getL2Cache();

            l1Cache.getResource().asMap().forEach((uuid, entityData) -> {
                // Skip if we have specific UUIDs and this one is not in the set
                if (this.entityUuids != null && !this.entityUuids.contains(uuid)) {
                    return;
                }

                String serialized = SimplixSerializer.serialize(entityData).toString();
                String entityKey = EntityRKeyUtil.getEntityKey(uuid, service);
                String currentCache = l2Cache.getWithType(client -> client.getBucket(entityKey).get(), () -> "", null, false);

                // If the data is the same, no need to sync
                if (currentCache.equals(serialized)) {
                    return;
                }

                String syncLockKey = EntityRKeyUtil.getEntityReadWriteLockKey(entityKey);

                // Write lock
                l2Cache.execute(
                        client -> client.getReadWriteLock(syncLockKey).writeLock(),
                        client -> {
                            // Store operation
                            RedissonClient redissonClient = l2Cache.getResource();

                            // Set TTL based on custom TTL if provided, otherwise use default
                            Duration ttlToApply = this.ttl != null ? this.ttl : LegacyEntityDataService.DEFAULT_TTL_DURATION;
                            RBucket<Object> bucket = redissonClient.getBucket(entityKey);

                            bucket.set(serialized);

                            // Always use TTLUtil for consistent TTL setting
                            TTLUtil.setReliableTTL(redissonClient, entityKey, ttlToApply.getSeconds());

                            return null;
                        },
                        LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
                );
            });
        });
    }

}