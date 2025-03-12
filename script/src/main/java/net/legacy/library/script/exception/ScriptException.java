package net.legacy.library.script.exception;

/**
 * A custom exception class for handling errors related to scripting.
 *
 * @author qwq-dev
 * @since 2025-03-12 16:52
 */
public class ScriptException extends Exception {
    /**
     * {@inheritDoc}
     *
     * @param message {@inheritDoc}
     */
    public ScriptException(String message) {
        super(message);
    }

    /**
     * {@inheritDoc}
     *
     * @param message {@inheritDoc}
     * @param cause   {@inheritDoc}
     */
    public ScriptException(String message, Throwable cause) {
        super(message, cause);
    }
}