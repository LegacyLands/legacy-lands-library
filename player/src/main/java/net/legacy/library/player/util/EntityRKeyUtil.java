package net.legacy.library.player.util;

import net.legacy.library.player.service.LegacyEntityDataService;

import java.util.UUID;

/**
 * Utility class for generating Redis keys for entity-related operations.
 *
 * <p>This class provides methods to create standardized key formats for storing
 * and retrieving entity data in Redis. It ensures consistent key naming conventions
 * across the application.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
public class EntityRKeyUtil {

    private static final String ENTITY_KEY_PREFIX = "legacy:entity:data:";
    private static final String ENTITY_RW_LOCK_PREFIX = "legacy:entity:rw-lock:";
    private static final String ENTITY_STREAM_PREFIX = "legacy:entity:stream:";
    private static final String ENTITY_LOCK_PREFIX = "legacy:entity:lock:";
    private static final String ENTITY_TEMP_MAP_PREFIX = "legacy:entity:temp:";

    /**
     * Generates a Redis key for storing entity data.
     *
     * @param uuid    the UUID of the entity
     * @param service the entity data service
     * @return the generated Redis key
     */
    public static String getEntityKey(UUID uuid, LegacyEntityDataService service) {
        return ENTITY_KEY_PREFIX + service.getName() + ":" + uuid.toString();
    }

    /**
     * Generates a Redis key for a read-write lock on entity data.
     *
     * @param entityKey the entity key to create a lock for
     * @return the generated Redis lock key
     */
    public static String getEntityReadWriteLockKey(String entityKey) {
        return ENTITY_RW_LOCK_PREFIX + entityKey;
    }

    /**
     * Generates a Redis stream key for entity-related operations.
     *
     * @param service the entity data service
     * @return the generated Redis stream key
     */
    public static String getEntityStreamKey(LegacyEntityDataService service) {
        return ENTITY_STREAM_PREFIX + service.getName();
    }

    /**
     * Generates a Redis group key for entity stream consumers.
     *
     * @param service the entity data service
     * @return the generated Redis stream group key
     */
    public static String getEntityStreamGroupKey(LegacyEntityDataService service) {
        return "entity-service-group:" + service.getName();
    }

    /**
     * Generates a Redis consumer key for entity stream consumers.
     *
     * @param service    the entity data service
     * @param consumerId the unique identifier of the consumer
     * @return the generated Redis stream consumer key
     */
    public static String getEntityStreamConsumerKey(LegacyEntityDataService service, String consumerId) {
        return "entity-service-consumer:" + service.getName() + ":" + consumerId;
    }

    /**
     * Gets the lock key for entity operations.
     *
     * @param service   the entity data service
     * @param operation the operation or process name requiring the lock
     * @return the lock key
     */
    public static String getEntityLockKey(LegacyEntityDataService service, String operation) {
        return ENTITY_LOCK_PREFIX + service.getName() + ":" + operation;
    }

    /**
     * Gets the pattern for entity keys to match all entity data keys.
     *
     * @param service the entity data service
     * @return the key pattern for all entity data
     */
    public static String getEntityKeyPattern(LegacyEntityDataService service) {
        return ENTITY_KEY_PREFIX + service.getName() + ":*";
    }

    /**
     * Gets the key for a temporary map cache used for Redis stream operations.
     *
     * @param service the entity data service
     * @return the map cache key
     */
    public static String getTempRMapCacheKey(LegacyEntityDataService service) {
        return ENTITY_TEMP_MAP_PREFIX + service.getName() + ":map-cache";
    }

}