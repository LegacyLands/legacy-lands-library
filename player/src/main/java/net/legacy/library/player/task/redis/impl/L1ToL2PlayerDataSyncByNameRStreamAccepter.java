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
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class L1ToL2PlayerDataSyncByNameRStreamAccepter implements RStreamAccepterInterface {
    public static RStreamTask createRStreamTask(String name, Duration expirationTime) {
        return RStreamTask.of("player-data-sync-name", name, expirationTime);
    }

    @Override
    public String getActionName() {
        return "player-data-sync-name";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, String data) {
        // Very slow, but it's async so it's fine
        OfflinePlayer offlinePlayer =
                Bukkit.getOfflinePlayer(data);

        L1ToL2PlayerDataSyncTask.of(offlinePlayer.getUniqueId(), legacyPlayerDataService).start().getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data (L1ToL2PlayerDataSyncByNameRStreamAccepter)", throwable);
                return;
            }
            rStream.remove(streamMessageId);
        });
    }
}
