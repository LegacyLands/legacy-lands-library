package net.legacy.library.player.task.redis.resilience;

/**
 * Exception thrown when retry counter operations fail.
 *
 * <p>This exception provides more context than generic RuntimeException,
 * allowing callers to handle retry counter failures specifically.
 *
 * @author qwq-dev
 * @since 2025-06-07 16:00
 */
public class RetryCounterException extends RuntimeException {

    /**
     * Constructs a new retry counter exception with the specified detail message.
     *
     * @param message the detail message
     */
    public RetryCounterException(String message) {
        super(message);
    }

    /**
     * Constructs a new retry counter exception with the specified detail message
     * and cause.
     *
     * @param message the detail message
     * @param cause   the cause
     */
    public RetryCounterException(String message, Throwable cause) {
        super(message, cause);
    }

}