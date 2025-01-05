package net.legacy.library.player.util;

import net.legacy.library.player.service.LegacyPlayerDataService;

import java.util.UUID;

/**
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

    public static String getRMapCacheKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-rmapcache";
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
        return legacyPlayerDataService.getName() + "-rlpds-" + uuid.toString() + "-" + String.join("-", strings);
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService}
     * @param strings                 additional strings to append to the key
     * @return the key
     */
    public static String getRLPDSKey(LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        return legacyPlayerDataService.getName() + "-rlpds-" + String.join("-", strings);
    }
}
