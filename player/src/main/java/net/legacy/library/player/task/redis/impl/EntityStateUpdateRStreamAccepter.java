package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
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

import java.lang.reflect.Type;
import java.time.Duration;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * An {@link EntityRStreamAccepterInterface} implementation that merges the stamped changes of an entity published by
 * {@link LegacyEntityDataService#saveEntity(LegacyEntityData)} on another server.
 *
 * <p>The action name for tasks recognized by this class is {@code "entity-state-update"}. A message carries the
 * attributes and relationships changed since the publisher's previous publication of the entity, with the stamp of
 * every one, removals and clearings included; it is merged by {@link LegacyEntityData#mergeChanges} into the copy of
 * the entity this server holds in L1. The merge does not depend on the order messages arrive in, so every server that
 * reads them settles on the same state.
 *
 * <p>The action differs from the {@code "entity-data-update"} of the triple and pair formats, so a server running an
 * older version of this library, whose accepter cannot read this format, never receives these messages.
 *
 * @author qwq-dev
 * @since 2026-10-07 03:10
 */
@EntityRStreamAccepterRegister
public class EntityStateUpdateRStreamAccepter implements EntityRStreamAccepterInterface {

    /**
     * The action name of the messages this accepter handles.
     */
    public static final String ACTION_NAME = "entity-state-update";

    @SuppressWarnings("UnstableApiUsage")
    private static final Type STRING_MAP = new TypeToken<Map<String, String>>() {
    }.getType();

    @SuppressWarnings("UnstableApiUsage")
    private static final Type STRING_SET = new TypeToken<Set<String>>() {
    }.getType();

    /**
     * Creates a new {@link EntityRStreamTask} carrying the entity's changes not yet published from this instance.
     *
     * <p>The changes are taken out of the entity with {@link LegacyEntityData#takeUnpublishedChanges()}, so the next
     * task created for it carries only what changed since.
     *
     * @param entityData     the entity whose changes are published
     * @param publisher      the {@link LegacyEntityDataService#getInstanceId() instance id} of the publishing service
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance carrying the changes
     */
    public static EntityRStreamTask createRStreamTask(LegacyEntityData entityData, UUID publisher, Duration expirationTime) {
        return createRStreamTask(entityData.getUuid(), entityData.takeUnpublishedChanges(), publisher, expirationTime);
    }

    /**
     * Creates a new {@link EntityRStreamTask} carrying changes of an entity.
     *
     * @param uuid           the entity's UUID
     * @param changes        the changes, as taken with {@link LegacyEntityData#takeUnpublishedChanges()}
     * @param publisher      the {@link LegacyEntityDataService#getInstanceId() instance id} of the publishing service
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance carrying the changes
     */
    public static EntityRStreamTask createRStreamTask(UUID uuid, LegacyEntityData.Changes changes, UUID publisher,
                                                      Duration expirationTime) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.addProperty("uuid", uuid.toString());
        jsonObject.add("attributes", GsonUtil.getGson().toJsonTree(changes.attributes()));
        jsonObject.add("stamps", GsonUtil.getGson().toJsonTree(changes.attributeStamps()));
        jsonObject.add("relationships", GsonUtil.getGson().toJsonTree(changes.relationships()));
        jsonObject.add("relationshipStamps", GsonUtil.getGson().toJsonTree(changes.relationshipStamps()));
        jsonObject.addProperty("version", changes.version());
        jsonObject.addProperty("lastModifiedTime", changes.lastModifiedTime());
        jsonObject.addProperty("publisher", publisher.toString());

        return EntityRStreamTask.of(ACTION_NAME, GsonUtil.getGson().toJson(jsonObject), expirationTime);
    }

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
     * <p>Returns {@code false}: the changes this service published are already in its own L1 cache, so the stamp
     * merge would change nothing, and they are not read back.
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
     * <p>Only an entity this server holds in L1 is merged into; any other is loaded from L2 or the database when it is
     * first needed, so receiving its changes neither loads nor caches it. The changes are published once they are in
     * L2, so such a load has them. The message is not removed from the stream: every server must read it, and it is
     * removed once it expires.
     *
     * @param rStream                 {@inheritDoc}
     * @param streamMessageId         {@inheritDoc}
     * @param legacyEntityDataService {@inheritDoc}
     * @param data                    {@inheritDoc}
     */
    @Override
    public void accept(RStream<Object, Object> rStream, StreamMessageId streamMessageId,
                       LegacyEntityDataService legacyEntityDataService, String data) {
        try {
            JsonObject jsonObject = GsonUtil.getGson().fromJson(data, JsonObject.class);
            UUID uuid = UUID.fromString(jsonObject.get("uuid").getAsString());

            // Noted before L1 is checked, so a load of the entity running meanwhile reads L2 once more
            legacyEntityDataService.noteUpdateReceived(uuid);
            legacyEntityDataService.getFromL1Cache(uuid).ifPresent(localEntity -> {
                Map<String, String> attributes = GsonUtil.getGson().fromJson(jsonObject.get("attributes"), STRING_MAP);
                Map<String, String> stamps = GsonUtil.getGson().fromJson(jsonObject.get("stamps"), STRING_MAP);
                Set<String> relationships = jsonObject.has("relationships")
                        ? GsonUtil.getGson().fromJson(jsonObject.get("relationships"), STRING_SET) : new HashSet<>();
                Map<String, String> relationshipStamps = jsonObject.has("relationshipStamps")
                        ? GsonUtil.getGson().fromJson(jsonObject.get("relationshipStamps"), STRING_MAP) : Map.of();

                // Merged into the cached instance itself; the scheduled persistence writes it on
                localEntity.mergeChanges(new LegacyEntityData.Changes(attributes, stamps, relationships, relationshipStamps,
                        jsonObject.get("version").getAsLong(), jsonObject.get("lastModifiedTime").getAsLong(), 0),
                        legacyEntityDataService.tombstoneCutoff());
            });
        } catch (Exception exception) {
            Log.error("Error processing entity state update task.", exception);
        }
    }

}
