package net.legacy.library.player.task.redis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;

/**
 * Represents a task to be published to a Redis stream for entity operations.
 * Each task is identified by a task name, contains associated data,
 * and has an expiration time after which it should be discarded.
 *
 * <p>This class provides factory methods to create instances of {@code EntityRStreamTask}
 * using different input parameters.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Getter
@RequiredArgsConstructor
public class EntityRStreamTask {

    /**
     * The name of the task, used for identifying the type of operation.
     */
    private final String actionName;

    /**
     * The data payload for the task, typically serialized as JSON.
     */
    private final String data;

    /**
     * The expiration time duration for the task.
     * After this duration expires, the task will be removed from the stream.
     */
    private final Duration expirationTime;

    /**
     * Creates a new {@link EntityRStreamTask} instance with the specified task name, data, and expiration time.
     *
     * @param actionName     the name of the task associated with this task
     * @param data           the data payload of the task
     * @param expirationTime the duration after which the task expires
     * @return a new {@link EntityRStreamTask} instance
     */
    public static EntityRStreamTask of(String actionName, String data, Duration expirationTime) {
        return new EntityRStreamTask(actionName, data, expirationTime);
    }

    /**
     * Creates a new {@link EntityRStreamTask} instance from a {@link Pair} of task name and data,
     * along with the specified expiration time.
     *
     * @param dataPair       a {@link Pair} where the left element is the task name and the right element is the data
     * @param expirationTime the duration after which the task expires
     * @return a new {@link EntityRStreamTask} instance
     */
    public static EntityRStreamTask of(Pair<String, String> dataPair, Duration expirationTime) {
        return new EntityRStreamTask(dataPair.getLeft(), dataPair.getRight(), expirationTime);
    }

    /**
     * Gets the expiration time value in milliseconds.
     *
     * @return the expiration time value in milliseconds, or 0 if no expiration is set
     */
    public long getExpirationTimeMillis() {
        return expirationTime != null ? expirationTime.toMillis() : 0;
    }

}