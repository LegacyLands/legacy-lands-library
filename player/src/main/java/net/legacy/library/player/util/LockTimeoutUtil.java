package net.legacy.library.player.util;

import lombok.experimental.UtilityClass;
import net.legacy.library.cache.exception.LockAcquisitionTimeoutException;

/**
 * Tells a lock that could not be acquired in time apart from other failures of a cache operation.
 *
 * <p>Servers writing the same entry to L2 at once wait for each other's write lock; one that waits longer than its
 * lock settings allow gets a {@link LockAcquisitionTimeoutException} and retries on the next round. That is expected
 * under contention, not an error.
 *
 * @author qwq-dev
 * @since 2026-10-07 09:20
 */
@UtilityClass
public class LockTimeoutUtil {

    /**
     * Checks whether a failure, or any of its causes (such as the cause of a {@code CompletionException}), is a lock
     * not acquired within its wait time.
     *
     * @param throwable the failure
     * @return {@code true} if it is a lock timeout
     */
    public static boolean isLockTimeout(Throwable throwable) {
        for (Throwable cause = throwable; cause != null; cause = cause.getCause()) {
            if (cause instanceof LockAcquisitionTimeoutException) {
                return true;
            }
        }
        return false;
    }

}
