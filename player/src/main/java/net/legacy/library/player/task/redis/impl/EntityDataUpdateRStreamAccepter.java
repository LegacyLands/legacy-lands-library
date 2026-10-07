package net.legacy.library.player.task.redis.impl;

import com.google.common.reflect.TypeToken;
import io.fairyproject.log.Log;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.player.annotation.EntityRStreamAccepterRegister;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInterface;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.redisson.api.RStream;
import org.redisson.api.StreamMessageId;

import java.time.Duration;
import java.util.Map;
import java.util.UUID;

/**
 * An {@link EntityRStreamAccepterInterface} implementation that updates entity data by entity UUID.
 *
 * <p>The action name for tasks recognized by this class is {@code "entity-data-update"}.
 * Once a task is received, this accepter attempts to find the entity (by UUID) in L1 cache and merges the data
 * into it.
 *
 * <p>Classes annotated with {@link EntityRStreamAccepterRegister} are automatically
 * discovered and registered for handling Redis stream tasks.
 *
 * @author qwq-dev
 * @since 2025-04-11 16:15
 */
@EntityRStreamAccepterRegister
public class EntityDataUpdateRStreamAccepter implements EntityRStreamAccepterInterface {

    /**
     * Creates a new {@link EntityRStreamTask} for updating entity data based on the entity's UUID.
     *
     * @param uuid           the {@link UUID} of the entity
     * @param entityData     the map of data to be updated
     * @param version        the current version of the entity
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance for updating data by entity UUID
     */
    public static EntityRStreamTask createRStreamTask(UUID uuid, Map<String, String> entityData,
                                                      long version, Duration expirationTime) {
        return EntityRStreamTask.of("entity-data-update",
                GsonUtil.getGson().toJson(Triple.of(uuid.toString(), entityData, version)),
                expirationTime);
    }

    /**
     * Creates a new {@link EntityRStreamTask} for updating entity data based on the entity's UUID (string form).
     *
     * @param uuid           the string representation of the entity's UUID
     * @param entityData     the map of data to be updated
     * @param version        the current version of the entity
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance for updating data by entity UUID (string)
     */
    public static EntityRStreamTask createRStreamTask(String uuid, Map<String, String> entityData,
                                                      long version, Duration expirationTime) {
        return EntityRStreamTask.of("entity-data-update",
                GsonUtil.getGson().toJson(Triple.of(uuid, entityData, version)),
                expirationTime);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public String getActionName() {
        return "entity-data-update";
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
     * <p>This method deserializes the incoming JSON (a triple of entity UUID, data map and version, or the older pair
     * without a version) and merges the data into the entity this server holds in L1, with
     * {@link LegacyEntityData#mergeAttributes}. These formats carry no stamps, so each attribute is taken as stamped
     * with the message version and no time: it wins over older local changes and loses to local changes of the same
     * version. An entity not held in L1 is left alone; it is loaded from L2 or the database when it is first needed.
     *
     * <p>{@link LegacyEntityDataService#saveEntity(LegacyEntityData)} publishes the stamped
     * {@code "entity-state-update"} format instead (see {@link EntityStateUpdateRStreamAccepter}); this accepter
     * handles messages published with {@link #createRStreamTask(UUID, Map, long, Duration)} and by older versions.
     * The message is not removed from the stream: every server must read it, and it is removed once it expires.
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
            //Try to parse as Triple first (new format with version)
            Pair<String, Map<String, String>> pairData = null;
            Triple<String, Map<String, String>, Long> tripleData = null;

            String uuidString;
            long remoteVersion;
            Map<String, String> dataMap;

            try {
                @SuppressWarnings("UnstableApiUsage")
                Triple<String, Map<String, String>, Long> parsed = GsonUtil.getGson().fromJson(
                        data, new TypeToken<Triple<String, Map<String, String>, Long>>() {
                        }.getType());
                tripleData = parsed;
            } catch (Exception exception) {
                @SuppressWarnings("UnstableApiUsage")
                Pair<String, Map<String, String>> parsed = GsonUtil.getGson().fromJson(
                        data, new TypeToken<Pair<String, Map<String, String>>>() {
                        }.getType());
                pairData = parsed;
            }

            if (tripleData != null && tripleData.getMiddle() != null) {
                uuidString = tripleData.getLeft();
                dataMap = tripleData.getMiddle();
                remoteVersion = tripleData.getRight();
            } else if (pairData != null) {
                uuidString = pairData.getLeft();
                dataMap = pairData.getRight();
                remoteVersion = 0;
            } else {
                throw new IllegalArgumentException("Invalid data format");
            }

            UUID uuid = UUID.fromString(uuidString);
            long version = remoteVersion;

            // Merged into the cached instance itself; the scheduled persistence writes it on
            legacyEntityDataService.noteUpdateReceived(uuid);
            legacyEntityDataService.getFromL1Cache(uuid).ifPresent(localEntity -> localEntity.mergeAttributes(
                    dataMap, Map.of(), version, 0, new LegacyEntityData.Stamp(version, 0, "")));
        } catch (Exception exception) {
            Log.error("Error processing entity data update task.", exception);
        }
    }

}