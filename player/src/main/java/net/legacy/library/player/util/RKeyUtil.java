package net.legacy.library.player.util;

import net.legacy.library.player.service.LegacyPlayerDataService;

import java.util.UUID;

/**
 * Utility class for generating keys related to {@link LegacyPlayerDataService}.
 *
 * @author qwq-dev
 * @since 2025-01-03 19:37
 */
public class RKeyUtil {

    public static String getRStreamNameKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rstream";
    }

    public static String getRMapKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rmap";
    }

    public static String getTempRMapCacheKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rmapcache-" + UUID.randomUUID();
    }

    public static String getRStreamGroupKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rstreamgroup";
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService} and a {@link UUID}.
     *
     * @param uuid                    the UUID
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService}
     * @param strings                 additional strings to append to the key
     * @return the key
     */
    public static String getRLPDSKey(UUID uuid, LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        String additional = (strings != null && strings.length > 0) ? "-" + String.join("-", strings) : "";
        return legacyPlayerDataService.getName() + "-rlpds-" + uuid.toString() + additional;
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService}
     * @param strings                 additional strings to append to the key
     * @return the key
     */
    public static String getRLPDSKey(LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        String additional = (strings != null && strings.length > 0) ? "-" + String.join("-", strings) : "";
        return legacyPlayerDataService.getName() + "-rlpds" + additional;
    }

    public static String getRLPDSReadWriteLockKey(String bucketKey) {
        return bucketKey + "-read-write-lock";
    }
}
