package net.legacy.library.cache.exception;

/**
 * Exception thrown when a lock could not be acquired within the wait time of its
 * {@link net.legacy.library.cache.model.LockSettings}.
 *
 * <p>Under contention this is expected rather than an error: a caller that retries later can catch this type and
 * defer the work. It extends {@link RuntimeException}, so callers catching that keep working.
 *
 * @author qwq-dev
 * @since 2026-10-07 14:00
 */
public class LockAcquisitionTimeoutException extends RuntimeException {

    /**
     * Creates the exception.
     *
     * @param message the detail message
     */
    public LockAcquisitionTimeoutException(String message) {
        super(message);
    }

}
