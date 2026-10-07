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
import org.redisson.api.RSet;
import org.redisson.api.RType;
import org.redisson.api.RedissonClient;
import org.redisson.api.options.KeysScanOptions;

import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
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
     * How many entities one database write saves.
     */
    private static final int DATABASE_BATCH_SIZE = 1000;

    /**
     * Whether this is the last run before shutdown: it persists everything this server wrote to L2, and a database
     * step skipped is worth a warning, as nothing runs after it.
     */
    private boolean finalRun;

    /**
     * Whether only the entities saved since the last such run are written to L2, rather than the whole L1 cache.
     */
    private boolean savedOnly;

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
     * Marks this run as the last before shutdown: it persists to the database everything this server wrote to L2,
     * however much, and a database step skipped because the lock stayed held is logged as a warning.
     *
     * @return this task
     */
    public EntityDataPersistenceTask asFinalRun() {
        this.finalRun = true;
        return this;
    }

    /**
     * Writes to L2 only the entities saved since the last such run (see {@link PersistenceBacklog}), as the
     * persistence a save asks for does; the scheduled one writes the whole L1 cache, changes made without a save
     * included.
     *
     * @return this task
     */
    public EntityDataPersistenceTask savedOnly() {
        this.savedOnly = true;
        return this;
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
        return start(new CompletableFuture<>());
    }

    /**
     * Starts the task, completing a future once the L1 cache has reached L2, before the database step.
     *
     * @param synced the future to complete once the L1 cache is in L2, whether that step succeeded or not
     * @return a {@link CompletableFuture} of the whole task
     */
    public CompletableFuture<Void> start(CompletableFuture<Void> synced) {
        CompletableFuture<?> task = savedOnly
                ? L1ToL2EntityDataSyncTask.of(legacyEntityDataService.getPersistenceBacklog().savedEntries(), legacyEntityDataService).start()
                : L1ToL2EntityDataSyncTask.of(legacyEntityDataService).start();

        task.whenComplete((result, throwable) -> {
            legacyEntityDataService.reportL2Write(throwable == null);
            synced.complete(null);
        });

        return task.thenCompose(ignored -> submitWithVirtualThreadAsync(() -> {
            try {
                persistEntities();
            } catch (Exception exception) {
                Log.error("EntityDataPersistenceTask failed to persist entity data", exception);
                throw new CompletionException("Failed to persist entity data", exception);
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
                /*
                 * Another persistence, on this server or another, is writing to the database; what is waiting to be
                 * persisted stays in the pending set for the next run, of any server
                 */
                if (finalRun) {
                    Log.warn("Skipped the final persistence to the database: %s stayed held; %s entities written to L2 wait to be persisted by another server",
                            lock.getName(), redissonClient.getSet(EntityRKeyUtil.getPendingDatabaseKey(legacyEntityDataService)).size());
                } else {
                    Log.debug("Skipped persisting to the database, another persistence holds %s", lock.getName());
                }
                return;
            }
            /*
             * What is waiting to be persisted first; the scheduled run then scans L2 with the rest of the limit, for
             * entries written by a version that did not note them
             */
            int persisted = persistPendingEntities(l2Cache, datastore);
            if (!savedOnly && persisted < limit) {
                processEntitiesInL2Cache(l2Cache, redissonClient, datastore, limit - persisted);
            }
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
     * Saves to the database the entities written to L2 and not persisted yet, by any server: up to the limit, or all
     * of them on the final run, in batches.
     *
     * <p>The entities to persist are kept in a Redis set every write to L2 adds to, under the entry's write lock, so
     * an entry a server wrote and could not persist, such as one that crashed, is persisted by the next run of any
     * server. Each is saved as it is in L2, read a batch at a time, or as cached here if L2 no longer holds it. A
     * batch that fails goes back to the set for the next run.
     *
     * @param l2Cache   the Redis cache service
     * @param datastore the MongoDB datastore
     * @return how many entities were taken from the set
     */
    private int persistPendingEntities(RedisCacheServiceInterface l2Cache, Datastore datastore) {
        RedissonClient redissonClient = l2Cache.getResource();
        RSet<String> pending = redissonClient.getSet(EntityRKeyUtil.getPendingDatabaseKey(legacyEntityDataService));
        Map<UUID, LegacyEntityData> cached = legacyEntityDataService.getL1Cache().getResource().asMap();
        int max = finalRun ? Integer.MAX_VALUE : limit;
        int taken = 0;

        while (taken < max) {
            Set<String> batch = pending.removeRandom(Math.min(DATABASE_BATCH_SIZE, max - taken));
            if (batch.isEmpty()) {
                break;
            }
            taken += batch.size();

            try {
                Map<String, String> keys = new HashMap<>();
                batch.forEach(uuid -> keys.put(uuid, EntityRKeyUtil.getEntityKey(UUID.fromString(uuid), legacyEntityDataService)));
                Map<String, Object> stored = redissonClient.getBuckets().get(keys.values().toArray(new String[0]));

                List<LegacyEntityData> copies = new ArrayList<>();
                for (String uuid : batch) {
                    Object value = stored.get(keys.get(uuid));
                    LegacyEntityData entity = cached.get(UUID.fromString(uuid));
                    String serialized = value instanceof String string && !string.isEmpty() ? string
                            : entity != null ? SimplixSerializer.serialize(entity).toString() : "";
                    if (!serialized.isEmpty()) {
                        copies.add(SimplixSerializer.deserialize(serialized, LegacyEntityData.class));
                    }
                }
                datastore.save(copies);
            } catch (Exception exception) {
                pending.addAll(batch);
                Log.error("Failed to persist %s entities to the database; they are retried next run", exception, batch.size());
                break;
            }
        }
        return taken;
    }

    /**
     * Processes entity data from the L2 cache and saves it to the database.
     *
     * @param l2Cache        the Redis cache service
     * @param redissonClient the Redisson client
     * @param datastore      the MongoDB datastore
     * @param budget         how many entries to process at most
     */
    private void processEntitiesInL2Cache(RedisCacheServiceInterface l2Cache,
                                          RedissonClient redissonClient,
                                          Datastore datastore,
                                          int budget) {
        RKeys keys = redissonClient.getKeys();
        KeysScanOptions keysScanOptions = KeysScanOptions.defaults()
                .pattern(EntityRKeyUtil.getEntityKeyPattern(legacyEntityDataService))
                .limit(budget);

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