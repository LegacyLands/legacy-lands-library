package net.legacy.library.player.task.redis.impl;

import io.fairyproject.log.Log;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import net.legacy.library.player.task.redis.RStreamTask;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.UUID;

/**
 * An {@link RStreamAccepterInterface} implementation that listens for tasks
 * to synchronize player data from L1 to L2 cache, identified by a player's <b>UUID</b>.
 *
 * <p>The action name for tasks recognized by this class is {@code "player-data-sync-uuid"}.
 * Once a task is received, this accepter uses {@link L1ToL2PlayerDataSyncTask}
 * to synchronize data for the specified player's UUID.
 *
 * <p>Classes annotated with {@link RStreamAccepterRegister} are automatically
 * discovered and registered for handling Redis stream tasks.
 *
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByUuidRStreamAccepter implements RStreamAccepterInterface {

    /**
     * Creates a new {@link RStreamTask} for synchronizing player data based on a player's {@link UUID}.
     *
     * @param uuid           the UUID of the player
     * @param expirationTime the duration after which the task expires
     * @return a {@link RStreamTask} instance for syncing data by player UUID
     */
    public static RStreamTask createRStreamTask(UUID uuid, Duration expirationTime) {
        return createRStreamTask(uuid.toString(), expirationTime);
    }

    /**
     * Creates a new {@link RStreamTask} for synchronizing player data based on a player's UUID (string form).
     *
     * @param uuid           the string representation of the player's UUID
     * @param expirationTime the duration after which the task expires
     * @return a {@link RStreamTask} instance for syncing data by player UUID string
     */
    public static RStreamTask createRStreamTask(String uuid, Duration expirationTime) {
        return RStreamTask.of("player-data-sync-uuid", uuid, expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public String getActionName() {
        return "player-data-sync-uuid";
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public boolean isRecordLimit() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * <p>This method parses the UUID from the incoming data and initiates an
     * L1-to-L2 sync task via {@link L1ToL2PlayerDataSyncTask}.
     * If the sync completes successfully, the message is acknowledged and removed from the stream.
     *
     * @param rStream                 {@inheritDoc}
     * @param streamMessageId         {@inheritDoc}
     * @param legacyPlayerDataService {@inheritDoc}
     * @param data                    {@inheritDoc}
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyPlayerDataService legacyPlayerDataService, String data) {
        L1ToL2PlayerDataSyncTask.of(UUID.fromString(data), legacyPlayerDataService)
                .start()
                .whenComplete((aVoid, throwable) -> {
                    if (throwable != null) {
                        Log.error("Error while syncing player data (L1ToL2PlayerDataSyncByUuidRStreamAccepter)", throwable);
                        return;
                    }
                    // Acknowledge the message upon success
                    ack(rStream, streamMessageId);
                });
    }

}