package net.legacy.library.player.task;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.redis.RedisCacheServiceInterface;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.LockTimeoutUtil;
import net.legacy.library.player.util.RKeyUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task responsible for synchronizing player data from the first-level (L1) cache to the
 * second-level (L2) Redis cache.
 *
 * <p>This task iterates through the L1 cache, serializes the {@link LegacyPlayerData},
 * and updates the corresponding entries in the L2 cache. It ensures that the data
 * in both cache levels remains consistent.
 *
 * @author qwq-dev
 * @since 2025-01-05 12:10
 */
@RequiredArgsConstructor
public class L1ToL2PlayerDataSyncTask implements TaskInterface<CompletableFuture<?>> {

    private final UUID uuid;
    private final LegacyPlayerDataService legacyPlayerDataService;

    /**
     * Factory method to create a new {@link L1ToL2PlayerDataSyncTask} without specifying a UUID.
     *
     * <p>This variant synchronizes all player data in the L1 cache to the L2 cache.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link L1ToL2PlayerDataSyncTask}
     */
    public static L1ToL2PlayerDataSyncTask of(LegacyPlayerDataService legacyPlayerDataService) {
        return new L1ToL2PlayerDataSyncTask(null, legacyPlayerDataService);
    }

    /**
     * Factory method to create a new {@link L1ToL2PlayerDataSyncTask} for a specific player UUID.
     *
     * @param uuid                    the UUID of the player whose data is to be synchronized
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link L1ToL2PlayerDataSyncTask}
     */
    public static L1ToL2PlayerDataSyncTask of(UUID uuid, LegacyPlayerDataService legacyPlayerDataService) {
        return new L1ToL2PlayerDataSyncTask(uuid, legacyPlayerDataService);
    }

    /**
     * Executes the synchronization task, transferring player data from L1 cache to L2 cache.
     *
     * <p>If a specific UUID is provided, only that player's data is synchronized.
     * Otherwise, all player data in the L1 cache is synchronized.
     *
     * <p>Ensures that only changed data is updated in the L2 cache to optimize performance.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            CacheServiceInterface<Cache<UUID, LegacyPlayerData>, LegacyPlayerData> l1Cache =
                    legacyPlayerDataService.getL1Cache();
            RedisCacheServiceInterface l2Cache = legacyPlayerDataService.getL2Cache();

            int deferred = 0;
            for (Map.Entry<UUID, LegacyPlayerData> entry : l1Cache.getResource().asMap().entrySet()) {
                UUID key = entry.getKey();
                LegacyPlayerData legacyPlayerData = entry.getValue();
                if (this.uuid != null && !this.uuid.equals(key)) {
                    continue;
                }

                String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();
                String bucketKey = RKeyUtil.getRLPDSKey(key, legacyPlayerDataService);
                String nowCache = l2Cache.getWithType(client -> client.getBucket(bucketKey).get(), () -> "", null, false);

                // If the data is the same, no need to sync
                if (nowCache.equals(serialized)) {
                    continue;
                }

                String syncLockKey = RKeyUtil.getRLPDSReadWriteLockKey(bucketKey);

                // Write lock; a player whose lock stays busy, as another server writes it, is written by the next run
                try {
                    l2Cache.execute(
                            client -> client.getReadWriteLock(syncLockKey).writeLock(),
                            client -> {
                                client.getBucket(bucketKey).set(serialized);

                                // To persist to the database, by the next run of any server
                                client.getSet(RKeyUtil.getPendingDatabaseKey(legacyPlayerDataService)).add(key.toString());
                                return null;
                            },
                            LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
                    );
                } catch (RuntimeException exception) {
                    if (!LockTimeoutUtil.isLockTimeout(exception)) {
                        throw exception;
                    }
                    deferred++;
                }
            }

            if (deferred > 0) {
                Log.debug("Deferred %s players to the next run: their L2 write locks stayed busy", deferred);
            }
        });
    }

}