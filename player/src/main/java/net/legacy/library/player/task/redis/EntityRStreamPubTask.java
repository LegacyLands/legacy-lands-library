package net.legacy.library.player.task.redis;

import lombok.RequiredArgsConstructor;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.util.EntityRKeyUtil;
import org.redisson.api.RStream;
import org.redisson.api.stream.StreamAddArgs;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

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
     * The message also carries the {@link LegacyEntityDataService#getInstanceId() instance id} of the publishing
     * service, so accepters can tell their own messages apart.
     *
     * <p>The entries are built for this publication alone and added in a single {@code XADD}, so concurrent
     * publications, on this server or another, never see or overwrite each other's entries.
     *
     * @return {@inheritDoc}
     */
    @Override
    public CompletableFuture<?> start() {
        return submitWithVirtualThreadAsync(() -> {
            // Get the stream key and client
            String streamKey = EntityRKeyUtil.getEntityStreamKey(service);
            RStream<Object, Object> stream = service.getL2Cache().getResource().getStream(streamKey);

            // Set task data
            Map<Object, Object> entries = new LinkedHashMap<>();
            entries.put("actionName", entityRStreamTask.getActionName());
            entries.put("data", entityRStreamTask.getData());
            entries.put("publisher", service.getInstanceId().toString());

            // Add expiration time if timeout is set
            long timeoutMillis = entityRStreamTask.getExpirationTimeMillis();
            if (timeoutMillis > 0) {
                String expirationTime = String.valueOf(System.currentTimeMillis() + timeoutMillis);
                entries.put("timeout", expirationTime);
                entries.put("expiration-time", expirationTime);
            }

            // Add unique UUID to prevent duplicate processing
            entries.put("uuid", UUID.randomUUID().toString());

            // Publish to Redis stream using StreamAddArgs
            stream.add(StreamAddArgs.entries(entries));
        });
    }

}