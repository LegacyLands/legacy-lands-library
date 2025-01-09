package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-05 12:37
 */
@RStreamAccepterRegister
public class PlayerDataUpdateByNameRStreamAccepter implements RStreamAccepterInterface {
    @Override
    public String getActionName() {
        return "player-data-update-name";
    }

    @Override
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, Pair<String, String> data) {
        String playerName = data.getKey();
        String playerDataString = data.getValue();

        Map<String, String> playerData =
                GsonUtil.getGson().fromJson(playerDataString, new TypeToken<Map<String, String>>() {
                }.getType());

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
            legacyPlayerDataService.getLegacyPlayerData(player.getUniqueId()).addData(playerData);
            rStream.remove(streamMessageId);
        }
    }
}