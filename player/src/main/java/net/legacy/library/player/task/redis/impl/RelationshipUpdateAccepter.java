package net.legacy.library.player.task.redis.impl;

import com.google.gson.JsonObject;
import io.fairyproject.log.Log;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.util.UUID;

/**
 * Accepter for handling relationship update operations via Redis streams.
 *
 * <p>This accepter processes entity relationship updates that are published
 * to Redis streams. It demonstrates how to implement a concrete stream accepter
 * that synchronizes relationship changes across servers.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@EntityRStreamAccepterRegister(taskName = "relationship-update")
public class RelationshipUpdateAccepter implements EntityRStreamAccepterInterface {

    /**
     * Returns the task name that this accepter handles.
     *
     * @return the task name "relationship-update"
     */
    @Override
    public String getTaskName() {
        return "relationship-update";
    }

    /**
     * Determines whether this accepter should process a message only once.
     *
     * @return true to limit processing to once per message per connection
     */
    @Override
    public boolean isRecordLimit() {
        return true;
    }

    @Override
    public void accept(RStream<Object, Object> stream,
                       StreamMessageId id,
                       LegacyEntityDataService service,
                       String data) {
        try {
            // Parse the data using commons' GsonUtil
            JsonObject jsonObject = GsonUtil.getGson().fromJson(data, JsonObject.class);

            // Extract relationship parameters
            UUID sourceEntityUuid = UUID.fromString(jsonObject.get("sourceEntityUuid").getAsString());
            UUID targetEntityUuid = UUID.fromString(jsonObject.get("targetEntityUuid").getAsString());
            String relationshipType = jsonObject.get("relationshipType").getAsString();
            boolean remove = jsonObject.has("remove") && jsonObject.get("remove").getAsBoolean();

            // Get the source entity
            LegacyEntityData sourceEntity = service.getEntityData(sourceEntityUuid);
            if (sourceEntity == null) {
                return;
            }

            // Update the relationship
            if (remove) {
                sourceEntity.removeRelationship(relationshipType, targetEntityUuid);
            } else {
                sourceEntity.addRelationship(relationshipType, targetEntityUuid);
            }

            // Save the updated entity
            service.saveEntity(sourceEntity);

            ack(stream, id);
        } catch (Exception exception) {
            Log.error(exception);
        }
    }
} 