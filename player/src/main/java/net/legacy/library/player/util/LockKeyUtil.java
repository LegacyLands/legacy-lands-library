package net.legacy.library.player.util;

import java.util.UUID;

/**
 * @author qwq-dev
 * @since 2025-01-03 19:37
 */
public class LockKeyUtil {
    public static String getPlayerLockKey(UUID uuid, String... strings) {
        return uuid.toString() + "-" + String.join("-", strings);
    }
}
