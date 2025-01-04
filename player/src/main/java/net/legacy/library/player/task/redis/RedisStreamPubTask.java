package net.legacy.library.player.task.redis;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamAddArgs;

import java.util.Map;

/**
 * @author qwq-dev
 * @since 2025-01-04 19:59
 */
@RequiredArgsConstructor
public class RedisStreamPubTask<K, V> implements TaskInterface {
    private final LegacyPlayerDataService legacyPlayerDataService;
    private final String streamName;
    private final Map<K, V> data;

    public static <K, V> RedisStreamPubTask<K, V> of(LegacyPlayerDataService legacyPlayerDataService, String streamName, Map<K, V> data) {
        return new RedisStreamPubTask<>(legacyPlayerDataService, streamName, data);
    }

    @Override
    public ScheduledTask<?> start() {
        return schedule(() -> {
            RStream<K, V> rStream =
                    legacyPlayerDataService.getL2Cache().getCache().getStream(streamName);
            rStream.add(StreamAddArgs.entries(data));
        });
    }
}
