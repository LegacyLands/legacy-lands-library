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

import java.util.Map;
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
    public boolean recodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry) {
        for (Map.Entry<Object, Object> entry : streamMessageIdMapEntry.getValue().entrySet()) {
            Object value = entry.getValue();

            Pair<String, String> data = GsonUtil.getGson().fromJson(
                    value.toString(), new TypeToken<Pair<String, String>>() {
                    }.getType()
            );

            String first = data.getKey();
            String second = data.getValue();

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

                rStream.remove(streamMessageIdMapEntry.getKey());
            }));
        }
    }
}