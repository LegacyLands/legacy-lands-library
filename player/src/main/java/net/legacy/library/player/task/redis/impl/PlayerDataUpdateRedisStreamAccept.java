package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Triple;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;
import java.util.Optional;

/**
 * @author qwq-dev
 * @since 2025-01-05 12:37
 */
@RStreamAccepterRegister
public class PlayerDataUpdateRedisStreamAccept implements RStreamAccepterInterface {
    @Override
    public String getActionName() {
        return "player-data-update-name";
    }

    @Override
    public String getTargetLegacyPlayerDataServiceName() {
        return null;
    }

    @Override
    public boolean recodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry) {
        for (Map.Entry<Object, Object> entry : streamMessageIdMapEntry.getValue().entrySet()) {
            Object value = entry.getValue();

            Triple<String, String, String> triple = GsonUtil.getGson().fromJson(
                    value.toString(), new TypeToken<Triple<String, String, String>>() {
                    }.getType()
            );

            String lpdsName = triple.getLeft();
            String playerName = triple.getMiddle();
            String newDataString = triple.getRight();

            Map<String, String> newDataMap = GsonUtil.getGson().fromJson(
                    newDataString, new TypeToken<Map<String, String>>() {
                    }.getType()
            );

            Player player = Bukkit.getPlayer(playerName);
            Optional<LegacyPlayerDataService> legacyPlayerDataService =
                    LegacyPlayerDataService.getLegacyPlayerDataService(lpdsName);

            if (player != null) {
                legacyPlayerDataService.ifPresent(service -> {
                    service.getLegacyPlayerData(player.getUniqueId()).getData().putAll(newDataMap);
                    rStream.remove(streamMessageIdMapEntry.getKey());
                });
            }
        }
    }
}