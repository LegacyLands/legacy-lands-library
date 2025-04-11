package net.legacy.library.player.task.redis;

import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.api.RMapCache;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamAddArgs;

import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Task responsible for publishing {@link EntityRStreamTask} instances to the Redis stream.
 *
 * <p>This task serializes task data, sets expiration times, and adds the task
 * to the appropriate Redis stream for processing by registered accepters.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@RequiredArgsConstructor
public class EntityRStreamPubTask implements TaskInterface<CompletableFuture<?>> {
    private final LegacyEntityDataService service;
    private final EntityRStreamTask entityRStreamTask;

    /**
     * Factory method to create a new {@link EntityRStreamPubTask}.
     *
     * @param service           the {@link LegacyEntityDataService} instance to use
     * @param entityRStreamTask the {@link EntityRStreamTask} to be published
     * @return a new instance of {@link EntityRStreamPubTask}
     */
    public static EntityRStreamPubTask of(LegacyEntityDataService service, EntityRStreamTask entityRStreamTask) {
        return new EntityRStreamPubTask(service, entityRStreamTask);
    }

    /**
     * Publishes the {@link EntityRStreamTask} to the Redis stream.
     *
     * <p>This method creates a message map containing the task data and adds it to the Redis stream.
     * If an expiration time is set, it will also include an expiration timestamp in the message.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            // Get the stream key and client
            String streamKey = EntityRKeyUtil.getEntityStreamKey(service);

            // Create map cache for storing data with expiration
            RMapCache<Object, Object> mapCache = service.getL2Cache().getResource().getMapCache(
                    EntityRKeyUtil.getTempRMapCacheKey(service)
            );

            // Set task data
            mapCache.put("actionName", entityRStreamTask.getActionName());
            mapCache.put("data", entityRStreamTask.getData());

            // Add expiration time if timeout is set
            long timeoutMillis = entityRStreamTask.getExpirationTimeMillis();
            if (timeoutMillis > 0) {
                long expirationTime = System.currentTimeMillis() + timeoutMillis;
                mapCache.put(
                        "timeout",
                        String.valueOf(expirationTime),
                        timeoutMillis,
                        TimeUnit.MILLISECONDS
                );

                // Set expiration time for the cache
                mapCache.put(
                        "expiration-time",
                        String.valueOf(expirationTime),
                        timeoutMillis + 200,
                        TimeUnit.MILLISECONDS
                );
            }

            // Add unique UUID to prevent duplicate processing
            mapCache.put("uuid", UUID.randomUUID().toString());

            // Publish to Redis stream using StreamAddArgs
            service.getL2Cache().getWithType(
                    client -> null,
                    client -> {
                        RStream<Object, Object> stream = client.getStream(streamKey);
                        return stream.add(StreamAddArgs.entries(mapCache));
                    },
                    () -> null,
                    null,
                    false,
                    LockSettings.of(500, 500, TimeUnit.MILLISECONDS)
            );
        });
    }
} 