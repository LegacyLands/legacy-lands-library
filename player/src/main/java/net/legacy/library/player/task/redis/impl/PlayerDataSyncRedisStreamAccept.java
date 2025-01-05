package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RedisStreamAccept;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.L1ToL2DataSyncTask;
import net.legacy.library.player.task.redis.RedisStreamAcceptInterface;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RedisStreamAccept
public class PlayerDataSyncRedisStreamAccept implements RedisStreamAcceptInterface {
    @Override
    public void accept(Map<Object, Object> message) {
        for (Map.Entry<Object, Object> entry : message.entrySet()) {
            // If the key is player-data-sync, cast the value to a map
            if (entry.getKey().toString().equals("player-data-sync")) {
                // This map key is LPDS name, value is player uuid
                Map<String, String> value = GsonUtil.GSON.fromJson(
                        entry.getValue().toString(), new TypeToken<Map<String, String>>() {
                        }.getType()
                );

                // L1 -> L2
                for (Map.Entry<String, String> dataSyncEntry : value.entrySet()) {
                    String lpdsName = dataSyncEntry.getValue();
                    String playerUuid = dataSyncEntry.getKey();
                    UUID uuid = UUID.fromString(playerUuid);

                    Optional<LegacyPlayerDataService> legacyPlayerDataService =
                            LegacyPlayerDataService.getLegacyPlayerDataService(lpdsName);
                    legacyPlayerDataService.ifPresent(service -> L1ToL2DataSyncTask.of(uuid, service).start());
                }
            }
        }
    }
}