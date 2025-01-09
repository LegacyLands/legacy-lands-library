package net.legacy.library.player.task;

import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.log.Log;
import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.util.RKeyUtil;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/**
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

    public static PlayerDataUpdateTask of(String name, Duration expirationTime, Duration delay, Map<String, String> data, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataUpdateTask(null, name, expirationTime, delay, data, legacyPlayerDataService);
    }

    public static PlayerDataUpdateTask of(UUID uuid, Duration expirationTime, Duration delay, Map<String, String> data, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataUpdateTask(uuid, null, expirationTime, delay, data, legacyPlayerDataService);
    }

    @Override
    public ScheduledTask<?> start() {
        Runnable runnable = () -> {
            if (uuid == null && name == null) {
                throw new IllegalArgumentException("UUID and name cannot be null at the same time!");
            }

            boolean isUuid = uuid != null;
            Pair<String, String> data = isUuid ?
                    Pair.of("player-data-sync-uuid", uuid.toString()) : Pair.of("player-data-sync-name", name);

            legacyPlayerDataService.pubRStreamTask(data, expirationTime);
        };

        ScheduledTask<?> task = schedule(runnable);

        task.getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data", throwable);
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

                // Save to database
            }, () -> legacyPlayerDataService.getMongoDBConnectionConfig().getDatastore().save(legacyPlayerDataService.getFromDatabase(uuid).addData(data)));
        });

        return task;
    }
}
