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
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByUuidRStreamAccepter implements RStreamAccepterInterface {
    public static RStreamTask createRStreamTask(UUID uuid, Duration expirationTime) {
        return createRStreamTask(uuid.toString(), expirationTime);
    }

    public static RStreamTask createRStreamTask(String uuid, Duration expirationTime) {
        return RStreamTask.of("player-data-sync-uuid", uuid, expirationTime);
    }

    @Override
    public String getActionName() {
        return "player-data-sync-uuid";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, String data) {
        L1ToL2PlayerDataSyncTask.of(UUID.fromString(data), legacyPlayerDataService).start().getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data", throwable);
                return;
            }
            rStream.remove(streamMessageId);
        });
    }
}