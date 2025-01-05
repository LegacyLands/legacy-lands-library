package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import io.fairyproject.log.Log;
import kotlin.Pair;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.RedisStreamAccepter;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.L1ToL2DataSyncTask;
import net.legacy.library.player.task.redis.RStreamAcceptInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RedisStreamAccepter
public class PlayerDataSyncUUIDRedisStreamAccept implements RStreamAcceptInterface {
    @Override
    public String getActionName() {
        return "player-data-sync-uuid";
    }

    @Override
    public String getTargetLegacyPlayerDataServiceName() {
        return "";
    }

    @Override
    public void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> streamMessageIdMapEntry) {
        for (Map.Entry<Object, Object> entry : streamMessageIdMapEntry.getValue().entrySet()) {
            Object key = entry.getKey();
            String keyString = key.toString();
            Object value = entry.getValue();

            if (keyString.equals(getActionName())) {
                Pair<String, String> data = GsonUtil.getGson().fromJson(
                        value.toString(), new TypeToken<Pair<String, String>>() {
                        }.getType()
                );

                String first = data.getFirst();
                String second = data.getSecond();

                Optional<LegacyPlayerDataService> legacyPlayerDataService =
                        LegacyPlayerDataService.getLegacyPlayerDataService(first);

                legacyPlayerDataService.ifPresent(service -> {
                    L1ToL2DataSyncTask.of(UUID.fromString(second), service).start().getFuture().whenComplete((aVoid, throwable) -> {
                        if (throwable != null) {
                            Log.error("Error while syncing player data", throwable);
                            return;
                        }

                        rStream.ack(getActionName(), streamMessageIdMapEntry.getKey());
                    });
                });
            }
        }
    }
}