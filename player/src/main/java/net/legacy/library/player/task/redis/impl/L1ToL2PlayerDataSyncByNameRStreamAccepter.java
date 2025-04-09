package net.legacy.library.player.task.redis.impl;

import io.fairyproject.log.Log;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import net.legacy.library.player.task.redis.RStreamTask;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;

/**
 * An {@link RStreamAccepterInterface} implementation that listens for tasks
 * to synchronize player data from L1 to L2 cache, identified by a player's <b>name</b>.
 *
 * <p>The action name for tasks recognized by this class is {@code "player-data-sync-name"}.
 * Once a task is received, this accepter uses {@link L1ToL2PlayerDataSyncTask} to
 * synchronize data for the specified offline player.
 *
 * <p>Classes annotated with {@link RStreamAccepterRegister} are automatically
 * discovered and registered for handling Redis stream tasks.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByNameRStreamAccepter implements RStreamAccepterInterface {
    /**
     * Creates a new {@link RStreamTask} for synchronizing player data based on the player's name.
     *
     * @param name           the name of the player
     * @param expirationTime the duration after which the task expires
     * @return a {@link RStreamTask} instance for syncing data by player name
     */
    public static RStreamTask createRStreamTask(String name, Duration expirationTime) {
        return RStreamTask.of("player-data-sync-name", name, expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * @return the action name associated with this accepter, which is {@code "player-data-sync-name"}
     */
    @Override
    public String getActionName() {
        return "player-data-sync-name";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code true}, indicating that this accepter processes each task only once per connection
     */
    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method retrieves an {@link OfflinePlayer} by name and initiates an
     * L1-to-L2 sync task via {@link L1ToL2PlayerDataSyncTask}.
     * If the sync completes successfully, the message is acknowledged and removed from the stream.
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyPlayerDataService legacyPlayerDataService, String data) {
        // OfflinePlayer retrieval by name
        OfflinePlayer offlinePlayer = Bukkit.getOfflinePlayer(data);

        L1ToL2PlayerDataSyncTask.of(offlinePlayer.getUniqueId(), legacyPlayerDataService)
                .start()
                .whenComplete((aVoid, throwable) -> {
                    if (throwable != null) {
                        Log.error("Error while syncing player data (L1ToL2PlayerDataSyncByNameRStreamAccepter)", throwable);
                        return;
                    }
                    // Acknowledge the message upon success
                    ack(rStream, streamMessageId);
                });
    }
}