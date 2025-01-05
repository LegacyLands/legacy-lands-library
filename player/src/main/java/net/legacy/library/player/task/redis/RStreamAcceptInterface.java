package net.legacy.library.player.task.redis;

import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-04 20:30
 */
public interface RStreamAcceptInterface {
    /**
     * Get the action name.
     *
     * @return action name
     */
    String getActionName();

    /**
     * Get the target legacy player data service name.
     *
     * @return target legacy player data service name
     */
    String getTargetLegacyPlayerDataServiceName();

    /**
     * Handle the data.
     *
     * <p>If the data is processed as expected, the data can be deleted in this method.
     * If it is different from the expected, it will not be processed and handed over to other connections for processing. It is not exclusive.
     * Unless it expires or is processed correctly by a connection, the data will always exist.
     *
     * @param rStream rStream
     * @param entry entry
     */
    void accept(RStream<Object, Object> rStream, Map.Entry<StreamMessageId, Map<Object, Object>> entry);
}
