package net.legacy.library.script.scope;

/**
 * @author qwq-dev
 * @since 2025-03-13 14:31
 */
public interface ScriptScope {

    /**
     * Gets the value.
     *
     * @param name The name
     * @return The value
     */
    Object getVariable(String name);

    /**
     * Sets the value.
     *
     * @param name  The name
     * @param value The value
     */
    void setVariable(String name, Object value);

    /**
     * Remove the value.
     *
     * @param name The name
     */
    void removeVariable(String name);

}
