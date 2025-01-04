package net.legacy.library.player.util;

import net.legacy.library.player.service.LegacyPlayerDataService;

import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-03 19:37
 */
public class KeyUtil {
    public static String getRedisStreamPlayerDataSyncKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-pdsr";
    }

    public static String getRedisStreamNameKey(LegacyPlayerDataService legacyPlayerDataService) {
        return legacyPlayerDataService.getName() + "-redis-stream";
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService} and a {@link UUID}.
     *
     * @param uuid                    the UUID
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService}
     * @param strings                 additional strings to append to the key
     * @return the key
     */
    public static String getLegacyPlayerDataServiceKey(UUID uuid, LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        return legacyPlayerDataService.getName() + "-lpds-" + uuid.toString() + "-" + String.join("-", strings);
    }

    /**
     * Generates a key for a {@link LegacyPlayerDataService}.
     *
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService}
     * @param strings                 additional strings to append to the key
     * @return the key
     */
    public static String getLegacyPlayerDataServiceKey(LegacyPlayerDataService legacyPlayerDataService, String... strings) {
        return legacyPlayerDataService.getName() + "-lpds-" + String.join("-", strings);
    }
}
