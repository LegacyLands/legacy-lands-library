package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import io.fairyproject.log.Log;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.L1ToL2DataSyncTask;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Optional;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RStreamAccepterRegister
public class PlayerDataSyncNameRedisStreamAccept implements RStreamAccepterInterface {
    @Override
    public String getActionName() {
        return "player-data-sync-name";
    }

    public String getTargetLegacyPlayerDataServiceName() {
        return "player-data-service";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, Pair<String, String> data) {
        Object value = data.getValue();

        Pair<String, String> pair = GsonUtil.getGson().fromJson(
                value.toString(), new TypeToken<Pair<String, String>>() {
                }.getType()
        );

        String first = pair.getKey();
        String second = pair.getValue();

        // Very slow, but it's async so it's fine
        OfflinePlayer offlinePlayer =
                Bukkit.getOfflinePlayer(second);

        Optional<LegacyPlayerDataService> legacyPlayerDataService =
                LegacyPlayerDataService.getLegacyPlayerDataService(first);

        legacyPlayerDataService.ifPresent(service -> L1ToL2DataSyncTask.of(offlinePlayer.getUniqueId(), service).start().getFuture().whenComplete((aVoid, throwable) -> {
            if (throwable != null) {
                Log.error("Error while syncing player data", throwable);
                return;
            }

            rStream.remove(streamMessageId);
        }));
    }
}