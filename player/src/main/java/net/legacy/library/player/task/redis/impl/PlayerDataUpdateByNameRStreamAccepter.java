package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import net.legacy.library.player.task.redis.RStreamTask;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-05 12:37
 */
@RStreamAccepterRegister
public class PlayerDataUpdateByNameRStreamAccepter implements RStreamAccepterInterface {
    public static RStreamTask createRStreamTask(String name, Map<String, String> playerData, Duration expirationTime) {
        return RStreamTask.of("player-data-update-name", GsonUtil.getGson().toJson(Pair.of(name, playerData)), expirationTime);
    }

    @Override
    public String getActionName() {
        return "player-data-update-name";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, String data) {
        Pair<String, Map<String, String>> pairData =
                GsonUtil.getGson().fromJson(data, new TypeToken<Pair<String, Map<String, String>>>() {
                }.getType());

        String playerName = pairData.getLeft();
        Map<String, String> dataMap = pairData.getRight();

        // Now we can update the player data
        Player player = Bukkit.getPlayer(playerName);

        /*
         * If the player is online
         * the player's data should be obtained in time
         * even if it has not been obtained before (not present in L1)
         *
         * If the player is not online, we actually don't need to do anything
         */
        if (player != null && player.isOnline()) {
            legacyPlayerDataService.getLegacyPlayerData(player.getUniqueId()).addData(dataMap);
            ack(rStream, streamMessageId);
        }
    }
}