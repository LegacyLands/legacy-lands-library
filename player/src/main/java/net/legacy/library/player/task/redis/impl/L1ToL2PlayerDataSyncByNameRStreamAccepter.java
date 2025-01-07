package net.legacy.library.player.task.redis.impl;

import io.fairyproject.log.Log;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByNameRStreamAccepter implements RStreamAccepterInterface {
    @Override
    public String getActionName() {
        return "player-data-sync-uuid";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, Pair<String, String> data) {
        // Scound must be player uuid
        String second = data.getValue();

        L1ToL2PlayerDataSyncTask.of(UUID.fromString(second), legacyPlayerDataService).start().getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data", throwable);
                return;
            }
            rStream.remove(streamMessageId);
        });
    }
}