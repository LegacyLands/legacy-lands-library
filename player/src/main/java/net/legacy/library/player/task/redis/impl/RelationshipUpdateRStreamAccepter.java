package net.legacy.library.player.task.redis.impl;

import com.google.gson.JsonObject;
import io.fairyproject.log.Log;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
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
@EntityRStreamAccepterRegister
public class RelationshipUpdateRStreamAccepter implements EntityRStreamAccepterInterface {
    /**
     * Creates a new {@link EntityRStreamTask} for updating entity relationships.
     *
     * @param sourceEntityUuid the UUID of the source entity
     * @param targetEntityUuid the UUID of the target entity
     * @param relationshipType the type of relationship between the entities
     * @param remove          whether to remove the relationship (true) or add it (false)
     * @param expirationTime  the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance for updating entity relationships
     */
    public static EntityRStreamTask createRStreamTask(UUID sourceEntityUuid, UUID targetEntityUuid, 
                                                     String relationshipType, boolean remove, 
                                                     Duration expirationTime) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("sourceEntityUuid", sourceEntityUuid.toString());
        jsonObject.addProperty("targetEntityUuid", targetEntityUuid.toString());
        jsonObject.addProperty("relationshipType", relationshipType);
        jsonObject.addProperty("remove", remove);
        
        return EntityRStreamTask.of("relationship-update", GsonUtil.getGson().toJson(jsonObject), expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public String getActionName() {
        return "relationship-update";
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
     * @param stream  {@inheritDoc}
     * @param id      {@inheritDoc}
     * @param service {@inheritDoc}
     * @param data    {@inheritDoc}
     */
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