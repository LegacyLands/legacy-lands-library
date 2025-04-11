package net.legacy.library.player.util;

import net.legacy.library.player.service.LegacyPlayerDataService;

import java.util.UUID;

/**
 * Utility class for generating standardized keys used in Redis caching and stream management
 * for {@link LegacyPlayerDataService}.
 *
 * <p>This class provides static methods to construct consistent keys based on the service name
 * and player UUIDs, ensuring uniformity across different caching and streaming operations.
 *
 * @author qwq-dev
 * @since 2025-01-03 19:37
 */
public class RKeyUtil {
    private static final String PREFIX_LEGACY = "legacy:player:";
    private static final String SUFFIX_STREAM = ":stream";
    private static final String SUFFIX_MAP = ":map";
    private static final String SUFFIX_MAP_CACHE = ":map-cache";
    private static final String SUFFIX_STREAM_GROUP = ":stream-group";
    private static final String SUFFIX_DATA = ":data";
    private static final String SUFFIX_RW_LOCK = ":rw-lock";
    private static final String COLON = ":";
    private static final String WILDCARD_SUFFIX = ":*";

    /**
     * Generates the Redis stream name key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis stream name key as a {@link String}
     */
    public static String getRStreamNameKey(LegacyPlayerDataService legacyPlayerDataService) {
        return PREFIX_LEGACY + legacyPlayerDataService.getName() + SUFFIX_STREAM;
    }

    /**
     * Generates the Redis map key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis map key as a {@link String}
     */
    public static String getRMapKey(LegacyPlayerDataService legacyPlayerDataService) {
        return PREFIX_LEGACY + legacyPlayerDataService.getName() + SUFFIX_MAP;
    }

    /**
     * Generates a temporary Redis map cache key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the temporary Redis map cache key as a {@link String}
     */
    public static String getTempRMapCacheKey(LegacyPlayerDataService legacyPlayerDataService) {
        return PREFIX_LEGACY + legacyPlayerDataService.getName() + SUFFIX_MAP_CACHE + COLON + UUID.randomUUID();
    }

    /**
     * Generates the Redis stream group key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis stream group key as a {@link String}
     */
    public static String getRStreamGroupKey(LegacyPlayerDataService legacyPlayerDataService) {
        return PREFIX_LEGACY + legacyPlayerDataService.getName() + SUFFIX_STREAM_GROUP;
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService} and a {@link UUID}.
     *
     * @param uuid                    the UUID of the player
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @param strings                 additional strings to append to the key
     * @return the generated key as a {@link String}
     */
    public static String getRLPDSKey(UUID uuid, LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        StringBuilder keyBuilder = new StringBuilder(PREFIX_LEGACY)
                .append(legacyPlayerDataService.getName())
                .append(SUFFIX_DATA)
                .append(COLON)
                .append(uuid.toString());

        if (strings != null) {
            for (String str : strings) {
                keyBuilder.append(COLON).append(str);
            }
        }
        return keyBuilder.toString();
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService} with additional identifiers.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @param strings                 additional strings to append to the key
     * @return the generated key as a {@link String}
     */
    public static String getRLPDSKey(LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        StringBuilder keyBuilder = new StringBuilder(PREFIX_LEGACY)
                .append(legacyPlayerDataService.getName())
                .append(SUFFIX_DATA);

        if (strings != null) {
            for (String str : strings) {
                keyBuilder.append(COLON).append(str);
            }
        }
        return keyBuilder.toString();
    }

    /**
     * Gets the pattern for player keys to match all player data keys.
     *
     * @param legacyPlayerDataService the player data service
     * @return the key pattern for all player data
     */
    public static String getPlayerKeyPattern(LegacyPlayerDataService legacyPlayerDataService) {
        return PREFIX_LEGACY + legacyPlayerDataService.getName() + SUFFIX_DATA + WILDCARD_SUFFIX;
    }

    /**
     * Generates a read-write lock key for a given Redis bucket key.
     *
     * @param bucketKey the Redis bucket key
     * @return the read-write lock key as a {@link String}
     */
    public static String getRLPDSReadWriteLockKey(String bucketKey) {
        return bucketKey + SUFFIX_RW_LOCK;
    }
}