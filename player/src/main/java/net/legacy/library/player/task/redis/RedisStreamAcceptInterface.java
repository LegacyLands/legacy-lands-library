package net.legacy.library.player.task.redis;

import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:30
 */
public interface RedisStreamAcceptInterface {
    boolean canAccept(StreamMessageId streamMessageId);

    void accept(Map<Object, Object> message);
}
