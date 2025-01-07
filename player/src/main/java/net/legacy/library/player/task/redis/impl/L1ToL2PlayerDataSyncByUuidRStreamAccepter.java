package net.legacy.library.player.task.redis.impl;

import io.fairyproject.log.Log;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByUuidRStreamAccepter implements RStreamAccepterInterface {
    @Override
    public String getActionName() {
        return "player-data-sync-name";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, Pair<String, String> data) {
        // Scound must be player name
        String second = data.getValue();

        // Very slow, but it's async so it's fine
        OfflinePlayer offlinePlayer =
                Bukkit.getOfflinePlayer(second);

        L1ToL2PlayerDataSyncTask.of(offlinePlayer.getUniqueId(), legacyPlayerDataService).start().getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data", throwable);
                return;
            }
            rStream.remove(streamMessageId);
        });
    }
}