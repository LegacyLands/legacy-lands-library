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
 * Once a task is received, this accepter attempts to find the entity (by UUID) and
 * updates its data in L1 cache.
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
     * Creates a new {@link EntityRStreamTask} for updating entity data (legacy format without version).
     *
     * @param uuid           the {@link UUID} of the entity
     * @param entityData     the map of data to be updated
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance for updating data by entity UUID
     * @deprecated Use {@link #createRStreamTask(UUID, Map, long, Duration)} instead
     */
    @Deprecated
    public static EntityRStreamTask createRStreamTask(UUID uuid, Map<String, String> entityData, Duration expirationTime) {
        return createRStreamTask(uuid, entityData, 0, expirationTime);
    }

    /**
     * Creates a new {@link EntityRStreamTask} for updating entity data (legacy format without version).
     *
     * @param uuid           the string representation of the entity's UUID
     * @param entityData     the map of data to be updated
     * @param expirationTime the duration after which the task expires
     * @return a {@link EntityRStreamTask} instance for updating data by entity UUID (string)
     * @deprecated Use {@link #createRStreamTask(String, Map, long, Duration)} instead
     */
    @Deprecated
    public static EntityRStreamTask createRStreamTask(String uuid, Map<String, String> entityData, Duration expirationTime) {
        return createRStreamTask(uuid, entityData, 0, expirationTime);
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
     * <p>This method deserializes the incoming JSON (a pair of entity UUID and data map),
     * retrieves the entity data and updates its attributes in L1 cache.
     * The stream message is then acknowledged and removed if the update is successful.
     *
     * <p>This implementation includes version control to handle concurrent modifications:
     * <ul>
     *   <li>If the incoming entity has a lower version than the local entity, updates are merged.</li>
     *   <li>If the incoming entity has a higher version than the local entity, the local entity is replaced.</li>
     *   <li>If versions match, timestamps are used to determine the most recent update.</li>
     * </ul>
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

            if (tripleData != null) {
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

            // Get entity data from local cache
            LegacyEntityData localEntity = legacyEntityDataService.getEntityData(uuid);
            if (localEntity == null) {
                // Entity doesn't exist locally, no conflict to resolve
                Log.warn("Received update for non-existent entity: " + uuid);
                ack(rStream, streamMessageId);
                return;
            }

            long localVersion = localEntity.getVersion();
            boolean needsRepublish = false;

            // Handle version conflicts
            if (remoteVersion > localVersion) {
                // Remote version is newer, apply all updates
                localEntity.addAttributes(dataMap);
                localEntity.setVersion(remoteVersion);
                localEntity.updateLastModifiedTime();
            } else if (remoteVersion < localVersion) {
                /*
                 * Local version is newer, selectively merge updates.
                 * Create a temporary entity with the remote data for merging
                 */
                LegacyEntityData tempEntity = new LegacyEntityData(uuid);
                tempEntity.addAttributes(dataMap);
                tempEntity.setVersion(remoteVersion);

                // Merge changes from remote entity
                if (localEntity.mergeChangesFrom(tempEntity)) {
                    needsRepublish = true;
                }
            } else {
                /*
                 * Versions are equal, update based on timestamp.
                 * This is handled by adding the attributes which will update the timestamp
                 */
                localEntity.addAttributes(dataMap);

                // If attributes were changed, this might have updated the version
                if (localEntity.getVersion() > localVersion) {
                    needsRepublish = true;
                }
            }

            /*
             * Save the entity to ensure changes are persisted.
             * We use a special flag to avoid republishing from saveEntity method
             */
            if (needsRepublish) {
                // Avoid infinite loops in the synchronization process
                legacyEntityDataService.saveEntityWithoutRepublish(localEntity);

                // Manually publish the merged entity update
                legacyEntityDataService.pubEntityRStreamTask(createRStreamTask(
                        uuid,
                        localEntity.getAttributes(),
                        localEntity.getVersion(),
                        Duration.ofMinutes(5)
                ));
            } else {
                legacyEntityDataService.saveEntity(localEntity);
            }

            ack(rStream, streamMessageId);
        } catch (Exception exception) {
            Log.error("Error processing entity data update task.", exception);
        }
    }
}