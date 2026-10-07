package net.legacy.library.player.test;

import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A test accepter that declines its own service's messages, recording the data of every message it receives.
 *
 * @author qwq-dev
 * @since 2026-10-07 04:00
 */
@EntityRStreamAccepterRegister
public class SkippingOwnMessagesTestAccepter implements EntityRStreamAccepterInterface {

    /**
     * The action name of the messages this accepter handles.
     */
    public static final String ACTION_NAME = "test-own-skipped";

    /**
     * The data of every message received, by any instance.
     */
    public static final Set<String> RECEIVED = ConcurrentHashMap.newKeySet();

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public String getActionName() {
        return ACTION_NAME;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public boolean isRecordLimit() {
        return true;
    }

    /**
     * {@inheritDoc}
     *
     * @return {@code false}
     */
    @Override
    public boolean acceptOwnMessages() {
        return false;
    }

    /**
     * {@inheritDoc}
     *
     * @param rStream                 {@inheritDoc}
     * @param streamMessageId         {@inheritDoc}
     * @param legacyEntityDataService {@inheritDoc}
     * @param data                    {@inheritDoc}
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyEntityDataService legacyEntityDataService, String data) {
        RECEIVED.add(data);
    }

}
