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

    /**
     * Generates the Redis stream name key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis stream name key as a {@link String}
     */
    public static String getRStreamNameKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rstream";
    }

    /**
     * Generates the Redis map key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis map key as a {@link String}
     */
    public static String getRMapKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rmap";
    }

    /**
     * Generates a temporary Redis map cache key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the temporary Redis map cache key as a {@link String}
     */
    public static String getTempRMapCacheKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rmapcache-" + UUID.randomUUID();
    }

    /**
     * Generates the Redis stream group key for a given {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @return the Redis stream group key as a {@link String}
     */
    public static String getRStreamGroupKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rstreamgroup";
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
        String additional = (strings != null && strings.length > 0) ? "-" + String.join("-", strings) : "";
        return legacyPlayerDataService.getName() + "-rlpds-" + uuid.toString() + additional;
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService} with additional identifiers.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance
     * @param strings                 additional strings to append to the key
     * @return the generated key as a {@link String}
     */
    public static String getRLPDSKey(LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        String additional = (strings != null && strings.length > 0) ? "-" + String.join("-", strings) : "";
        return legacyPlayerDataService.getName() + "-rlpds" + additional;
    }

    /**
     * Generates a read-write lock key for a given Redis bucket key.
     *
     * @param bucketKey the Redis bucket key
     * @return the read-write lock key as a {@link String}
     */
    public static String getRLPDSReadWriteLockKey(String bucketKey) {
        return bucketKey + "-read-write-lock";
    }
}