package net.legacy.library.player.task.redis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;

/**
 * Represents a task to be published to a Redis stream.
 * Each task is identified by an action name, contains associated data,
 * and has an expiration time after which it should be discarded.
 *
 * <p>This class provides factory methods to create instances of {@code RStreamTask}
 * using different input parameters.
 *
 * @author qwq-dev
 * @since 2025-01-09 12:44
 */
@Getter
@RequiredArgsConstructor
public class RStreamTask {

    private final String actionName;
    private final String data;
    private final Duration expirationTime;

    /**
     * Creates a new {@link RStreamTask} instance with the specified action name, data, and expiration time.
     *
     * @param actionName     the name of the action associated with this task
     * @param data           the data payload of the task
     * @param expirationTime the duration after which the task expires
     * @return a new {@link RStreamTask} instance
     */
    public static RStreamTask of(String actionName, String data, Duration expirationTime) {
        return new RStreamTask(actionName, data, expirationTime);
    }

    /**
     * Creates a new {@link RStreamTask} instance from a {@link Pair} of action name and data,
     * along with the specified expiration time.
     *
     * @param dataPair       a {@link Pair} where the left element is the action name and the right element is the data
     * @param expirationTime the duration after which the task expires
     * @return a new {@link RStreamTask} instance
     */
    public static RStreamTask of(Pair<String, String> dataPair, Duration expirationTime) {
        return new RStreamTask(dataPair.getLeft(), dataPair.getRight(), expirationTime);
    }

}