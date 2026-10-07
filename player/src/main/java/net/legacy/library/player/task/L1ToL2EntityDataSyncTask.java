package net.legacy.library.player.task;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import net.legacy.library.player.util.LockTimeoutUtil;
import net.legacy.library.player.util.TTLUtil;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.client.RedisException;

import java.time.Duration;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Semaphore;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Task responsible for synchronizing entity data from the first-level (L1) cache to the
 * second-level (L2) Redis cache.
 *
 * <p>This task iterates through the L1 cache, serializes the {@link LegacyEntityData},
 * and updates the corresponding entries in the L2 cache. It ensures that the data
 * in both cache levels remains consistent. An entry another server changed since is merged with the cached entity,
 * by {@link LegacyEntityData#mergeChangesFrom}, rather than overwritten.
 *
 * @author qwq-dev
 */
@RequiredArgsConstructor
public class L1ToL2EntityDataSyncTask implements TaskInterface<CompletableFuture<?>> {

    private final Set<UUID> entityUuids;
    private final LegacyEntityDataService service;
    private final Duration ttl;

    /**
     * How many entities are written to L2 at the same time.
     */
    private static final int SYNC_PARALLELISM = 256;

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

            Map<UUID, LegacyEntityData> cached = l1Cache.getResource().asMap();

            // Only the given entities, looked up one by one: the whole cache may be far larger
            Iterable<UUID> uuids = this.entityUuids != null ? this.entityUuids : cached.keySet();

            /*
             * A few hundred Redis round trips at a time rather than one after another; an entity that fails, such as
             * on a write lock held too long, is left for the next run and does not stop the others
             */
            AtomicInteger attempted = new AtomicInteger();
            AtomicInteger deferred = new AtomicInteger();
            AtomicInteger failed = new AtomicInteger();
            AtomicReference<Exception> firstFailure = new AtomicReference<>();
            Semaphore permits = new Semaphore(SYNC_PARALLELISM);
            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (UUID uuid : uuids) {
                    LegacyEntityData entityData = cached.get(uuid);
                    if (entityData == null) {
                        continue;
                    }

                    permits.acquireUninterruptibly();
                    attempted.incrementAndGet();
                    executor.execute(() -> {
                        try {
                            syncEntity(uuid, entityData, l2Cache);
                        } catch (Exception exception) {
                            // Its save stays in the backlog, so the next run writes it
                            if (LockTimeoutUtil.isLockTimeout(exception)) {
                                // Another server writes the same entity; expected under contention
                                deferred.incrementAndGet();
                            } else {
                                failed.incrementAndGet();
                                firstFailure.compareAndSet(null, exception);
                            }
                        } finally {
                            permits.release();
                        }
                    });
                }
            }

            // Nothing written at all because Redis is unreachable: the run fails, and the next waits a little
            if (deferred.get() > 0) {
                Log.debug("Deferred %s entities to the next run: their L2 write locks stayed busy", deferred.get());
            }
            if (failed.get() > 0 && failed.get() == attempted.get() && isRedisFailure(firstFailure.get())) {
                throw new IllegalStateException("Could not write any of " + failed.get() + " entities to L2", firstFailure.get());
            }
            if (failed.get() > 0) {
                Log.warn("Could not write %s entities to L2 this run; they are written by the next", firstFailure.get(), failed.get());
            }
        });
    }

    private static boolean isRedisFailure(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof RedisException) {
                return true;
            }
        }
        return false;
    }

    /**
     * Writes one entity to L2, merged with what L2 holds, unless L2 holds the same already.
     *
     * @param uuid       the entity's UUID
     * @param entityData the entity as cached in L1
     * @param l2Cache    the Redis cache service
     */
    private void syncEntity(UUID uuid, LegacyEntityData entityData, RedisCacheServiceInterface l2Cache) {
        // Read before serializing: every save up to this ticket is in the state written
        PersistenceBacklog backlog = service.getPersistenceBacklog();
        long ticket = backlog.pendingTicket(uuid);
        String serialized = SimplixSerializer.serialize(entityData).toString();
        String entityKey = EntityRKeyUtil.getEntityKey(uuid, service);
        String currentCache = l2Cache.getWithType(client -> client.getBucket(entityKey).get(), () -> "", null, false);

        // If the data is the same, no need to sync
        if (currentCache.equals(serialized)) {
            backlog.written(uuid, ticket);
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

                    /*
                     * What another server wrote since is merged in first, under the same write lock, so L2
                     * only ever gains changes: a server loading the entity from it misses none published
                     */
                    String merged = serialized;
                    Object stored = bucket.get();
                    if (stored instanceof String storedString && !storedString.isEmpty() && !storedString.equals(serialized)) {
                        entityData.mergeChangesFrom(SimplixSerializer.deserialize(storedString, LegacyEntityData.class),
                                service.tombstoneCutoff());
                        merged = SimplixSerializer.serialize(entityData).toString();
                    }

                    bucket.set(merged);

                    // To persist to the database, by the next run of any server
                    redissonClient.getSet(EntityRKeyUtil.getPendingDatabaseKey(service)).add(uuid.toString());
                    backlog.written(uuid, ticket);

                    // Always use TTLUtil for consistent TTL setting
                    TTLUtil.setReliableTTL(redissonClient, entityKey, ttlToApply.getSeconds());

                    return null;
                },
                LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
        );
    }

}