package net.legacy.library.script.exception;

/**
 * A custom exception class for handling errors related to scripting.
 *
 * @author qwq-dev
 * @since 2025-03-12 16:52
 */
public class ScriptException extends Exception {
    /**
     * Constructs a new script exception with the specified detail message.
     *
     * @param message the detail message
     */
    public ScriptException(String message) {
        super(message);
    }

    /**
     * Constructs a new script exception with the specified detail message and cause.
     *
     * @param message the detail message
     * @param cause   the cause of this exception
     */
    public ScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}