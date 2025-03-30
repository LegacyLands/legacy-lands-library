package net.legacy.library.player.task.redis.impl;

import net.legacy.library.player.service.LegacyEntityDataService;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

/**
 * Interface for processing entity-related messages from Redis streams.
 *
 * <p>This interface defines a callback method that is invoked when a message
 * is received from a Redis stream. Implementations of this interface handle
 * specific types of entity operations that need to be synchronized across servers.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
public interface EntityRStreamAccepterInterface {
    /**
     * Gets the task name associated with this accepter.
     *
     * <p>This serves as a unique identifier or categorization for the specific type of task
     * being processed. For example, it could represent operations like "relationship-update".
     *
     * <p>If no specific task is required, this method can return {@code null}.
     *
     * @return a {@link String} representing the task name, or {@code null} if not applicable
     */
    default String getTaskName() {
        return null;
    }

    /**
     * Determines whether to limit task processing to prevent duplicates within a single server or connection.
     *
     * <p>If this method returns {@code true}, the task will be processed only once per
     * connection on each server. After the {@link #accept(RStream, StreamMessageId, LegacyEntityDataService, String)}
     * method is executed, the task will not be executed again by the same instance unless explicitly deleted.
     *
     * <p>However, if another server or connection processes the task, it can still
     * be handled there. A task remains in the RStream until it is correctly processed
     * and deleted, or until it expires.
     *
     * @return {@code true} if task records are limited to a single handling per connection,
     * {@code false} otherwise
     */
    default boolean isRecordLimit() {
        return false;
    }

    /**
     * Processes a message received from a Redis stream.
     *
     * @param stream  the Redis stream from which the message was received
     * @param id      the ID of the message in the stream
     * @param service the entity data service to use for processing
     * @param data    the data payload of the message
     */
    void accept(RStream<Object, Object> stream,
                StreamMessageId id,
                LegacyEntityDataService service,
                String data);

    /**
     * Acknowledges the task after it has been successfully processed.
     *
     * <p>This method removes the task from the Redis stream, indicating that it has been handled.
     *
     * @param stream the {@link RStream} object representing the Redis stream containing the task
     * @param id     the {@link StreamMessageId} object representing the unique ID of the task
     */
    default void ack(RStream<Object, Object> stream, StreamMessageId id) {
        stream.remove(id);
    }
} 