package net.legacy.library.player.task.redis.impl;

import net.legacy.library.player.annotation.RStreamAccepterRegister;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamAccepterInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

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
    public boolean isRecodeLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId, LegacyPlayerDataService legacyPlayerDataService, Pair<String, String> data) {

    }
}