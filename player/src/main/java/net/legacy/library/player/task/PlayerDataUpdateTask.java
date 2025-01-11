package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.util.RKeyUtil;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
 * Task responsible for updating player data both in the cache and the database.
 *
 * <p>This task publishes an update task to the Redis stream and then applies the update
 * to the L2 cache and persists the changes to the database. It ensures that player data
 * remains consistent across all storage layers.
 *
 * @author qwq-dev
 * @since 2025-01-04 12:53
 */
@RequiredArgsConstructor
public class PlayerDataUpdateTask implements TaskInterface {
    private final UUID uuid;
    private final String name;
    private final Duration expirationTime;
    private final Duration delay;
    private final Map<String, String> data;
    private final LegacyPlayerDataService legacyPlayerDataService;

    /**
     * Factory method to create a new {@link PlayerDataUpdateTask} using player name.
     *
     * @param name             the name of the player
     * @param expirationTime   the duration after which the task expires
     * @param delay            the delay before executing the update
     * @param data             the data to update
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link PlayerDataUpdateTask}
     */
    public static PlayerDataUpdateTask of(String name, Duration expirationTime, Duration delay, Map<String, String> data, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataUpdateTask(null, name, expirationTime, delay, data, legacyPlayerDataService);
    }

    /**
     * Factory method to create a new {@link PlayerDataUpdateTask} using player UUID.
     *
     * @param uuid             the UUID of the player
     * @param expirationTime   the duration after which the task expires
     * @param delay            the delay before executing the update
     * @param data             the data to update
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link PlayerDataUpdateTask}
     */
    public static PlayerDataUpdateTask of(UUID uuid, Duration expirationTime, Duration delay, Map<String, String> data, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataUpdateTask(uuid, null, expirationTime, delay, data, legacyPlayerDataService);
    }

    /**
     * Executes the player data update task.
     *
     * <p>This method publishes an update task to the Redis stream and then updates the
     * player data in the L2 cache. If the player is not present in the L2 cache,
     * it falls back to updating the database directly.
     *
     * @return a {@link ScheduledTask} representing the running update task
     */
    @Override
    public ScheduledTask<?> start() {
        Runnable runnable = () -> {
            if (uuid == null && name == null) {
                throw new IllegalArgumentException("UUID and name cannot be null at the same time!");
            }

            boolean isUuid = uuid != null;
            RStreamTask rStreamTask = isUuid ?
                    RStreamTask.of("player-data-sync-uuid", uuid.toString(), expirationTime) :
                    RStreamTask.of("player-data-sync-name", name, expirationTime);
            legacyPlayerDataService.pubRStreamTask(rStreamTask);
        };

        ScheduledTask<?> task = schedule(runnable);

        task.getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data (PlayerDataUpdateTask)", throwable);
                return;
            }

            legacyPlayerDataService.getFromL2Cache(uuid).ifPresentOrElse(legacyPlayerData -> {
                legacyPlayerData.addData(data);

                String bucketKey = RKeyUtil.getRLPDSKey(uuid, legacyPlayerDataService);
                String serialized = SimplixSerializer.serialize(legacyPlayerData).toString();

                // Save to cache, so we need write lock
                legacyPlayerDataService.getL2Cache().execute(
                        client -> client.getReadWriteLock(RKeyUtil.getRLPDSReadWriteLockKey(bucketKey)).writeLock(),
                        client -> {
                            client.getBucket(bucketKey).set(serialized);
                            return null;
                        },
                        LockSettings.of(5, 5, TimeUnit.MILLISECONDS)
                );

                // Optionally, persist to database here if necessary
            }, () -> legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore().save(legacyPlayerDataService.getFromDatabase(uuid).addData(data)));
        });

        return task;
    }
}