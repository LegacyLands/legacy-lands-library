package net.legacy.library.player.task.redis.impl;

import net.legacy.library.player.annotation.RedisStreamAccept;
import net.legacy.library.player.task.redis.RedisStreamAcceptInterface;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:59
 */
@RedisStreamAccept(redisStreamNames = "player-data-sync")
public class PlayerDataSyncRedisStreamAccept implements RedisStreamAcceptInterface {
    @Override
    public boolean canAccept(StreamMessageId streamMessageId) {
        return false;
    }

    @Override
    public void accept(Map<Object, Object> message) {
        // TODO: player data sync (l1 -> l2)
    }
}
