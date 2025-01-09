package net.legacy.library.player.task.redis;

import lombok.Getter;
import lombok.RequiredArgsConstructor;
import org.apache.commons.lang3.tuple.Pair;

import java.time.Duration;

/**
 * @author qwq-dev
 * @since 2025-01-09 12:44
 */
@Getter
@RequiredArgsConstructor
public class RStreamTask {
    private final String actionName;
    private final String data;
    private final Duration expirationTime;

    public static RStreamTask of(String actionName, String data, Duration expirationTime) {
        return new RStreamTask(actionName, data, expirationTime);
    }

    public static RStreamTask of(Pair<String, String> data, Duration expirationTime) {
        return new RStreamTask(data.getRight(), data.getLeft(), expirationTime);
    }
}
