package net.legacy.library.player.task.redis;

import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:30
 */
public interface RStreamAcceptInterface {
    String getActionName();
    void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> entry);
}
