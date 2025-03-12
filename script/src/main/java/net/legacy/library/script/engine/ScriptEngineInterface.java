package net.legacy.library.script.engine;

import net.legacy.library.script.exception.ScriptException;

import javax.script.Bindings;

/**
 * Interface for interacting with a scripting engine.
 * Provides methods for executing scripts, invoking functions, compiling scripts, and managing global variables.
 *
 * @author qwq-dev
 * @since 2025-03-12 16:52
 */
public interface ScriptEngineInterface {
    /**
     * Executes a script.
     *
     * @param script   The script string to execute
     * @param bindings The bindings to use for the script execution.
     *                 This can be used to provide variables and functions to the script.
     *                 If {@code null}, a new {@link Bindings} object will typically be created by the engine
     * @return The result of the script execution.  The specific type of the result depends on the scripting engine and the script itself
     * @throws ScriptException If an error occurs during script execution
     */
    Object execute(String script, Bindings bindings) throws ScriptException;

    /**
     * Invokes a function defined in a script.
     *
     * @param script       Optional script string. If provided, this script will be executed first.
     *                     Generally, you should execute the script using the {@link #execute} method first,
     *                     and then set this parameter to {@code null} to invoke a defined function.
     *                     It is not recommended to pass a script here directly.
     *                     It's better to separate script execution (using {@link #execute}) and function invocation
     * @param functionName The name of the function to invoke
     * @param bindings     The bindings to use. It's important to use the same {@link Bindings} context
     *                     that was used when the function was defined (typically by a prior call to {@link #execute}).
     *                     If {@code null}, the behavior is engine-specific, but it will likely use a default or engine-level scope
     * @param args         The arguments to pass to the function
     * @return The result of the function invocation
     * @throws ScriptException If an error occurs during script execution or function invocation
     */
    Object invokeFunction(String script, String functionName, Bindings bindings, Object... args) throws ScriptException;

    /**
     * Compiles a script for later execution.
     * This can improve performance if the same script will be executed multiple times.
     *
     * @param script The script string to compile
     * @return An object representing the compiled script.
     * The specific type of this object is engine-dependent,
     * but it will typically implement the {@code CompiledScript} interface if the engine supports compilation
     * @throws ScriptException If an error occurs during script compilation
     */
    Object compile(String script) throws ScriptException;

    /**
     * Executes a previously compiled script.
     *
     * @param compiledScript The compiled script object (returned by {@link #compile}).
     * @param bindings       The bindings to use for script execution.
     *                       If {@code null}, the behavior is engine-specific,
     *                       but it will often create new bindings or use bindings associated with the compiled script
     * @return The result of the script execution
     * @throws ScriptException If an error occurs during script execution
     */
    Object executeCompiled(Object compiledScript, Bindings bindings) throws ScriptException;

    /**
     * Gets the value of a global variable from the engine's scope.
     *
     * @param name The name of the global variable
     * @return The value of the global variable, or {@code null} if the variable is not defined
     */
    Object getGlobalVariable(String name);

    /**
     * Sets the value of a global variable in the engine's scope.
     *
     * @param name  The name of the global variable
     * @param value The value to set for the global variable
     */
    void setGlobalVariable(String name, Object value);

    /**
     * Remove the value of a global variable in the engine's scope.
     *
     * @param name  The name of the global variable
     */
    void removeGlobalVariable(String name);
}