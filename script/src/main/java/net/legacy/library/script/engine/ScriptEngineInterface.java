package net.legacy.library.script.engine;

import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.scope.ScriptScope;

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
     * @param script      The script string to execute
     * @param scriptScope The script scope to use for the script execution.
     *                    This can be used to provide variables and functions to the script. If {@code null}, use engine-level scope
     * @return The result of the script execution.  The specific type of the result depends on the scripting engine and the script itself
     * @throws ScriptException If an error occurs during script execution
     */
    Object execute(String script, ScriptScope scriptScope) throws ScriptException;

    /**
     * Executes a script using the engine-level scope.
     *
     * @param script The script string to execute.
     * @return The result of the script execution. The specific type of the result depends on the scripting engine and the script itself.
     * @throws ScriptException If an error occurs during script execution.
     */
    default Object execute(String script) throws ScriptException {
        return execute(script, null);
    }

    /**
     * Invokes a function defined in a script.
     *
     * @param script       Optional script string. If provided, this script will be executed first.
     *                     Generally, you should execute the script using the {@link #execute} method first,
     *                     and then set this parameter to {@code null} to invoke a defined function.
     *                     It is not recommended to pass a script here directly.
     *                     It's better to separate script execution (using {@link #execute}) and function invocation.
     *                     <br>For optimal performance with repeated function calls, it is strongly recommended to pre-compile
     *                     the script using {@link #compile} and then use {@link #invokeCompiledFunction}.
     *                     This avoids repeated parsing and compilation of the script.
     *                     If pre-compilation is not possible, then you should execute the script once using {@link #execute}
     *                     and then call this method with a {@code null} value for the {@code script} parameter
     * @param functionName The name of the function to invoke
     * @param scriptScope  The script scope to use for the script execution.
     *                     This can be used to provide variables and functions to the script. If {@code null}, use engine-level scope
     * @param args         The arguments to pass to the function
     * @return The result of the function invocation
     * @throws ScriptException If an error occurs during script execution or function invocation
     */
    Object invokeFunction(String script, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException;

    /**
     * Invokes a function defined within a previously executed script.
     *
     * @param functionName The name of the function to invoke.
     *                     The function must have been defined within a script that
     *                     was previously executed using {@link #execute}
     * @param scriptScope  The script scope to use. If {@code null}, the engine-level scope is used.
     *                     The function must be available in the provided scope (or the engine-level scope)
     * @param args         The arguments to pass to the function
     * @return The result of the function invocation
     * @throws ScriptException If an error occurs during script execution or function invocation
     */
    default Object invokeFunction(String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        return invokeFunction(null, functionName, scriptScope, args);
    }

    /**
     * Invokes a function defined in a script, using the engine-level scope.
     *
     * @param script       Optional script string.  See {@link #invokeFunction(String, String, ScriptScope, Object...)} for details.
     * @param functionName The name of the function to invoke.
     * @param args         The arguments to pass to the function.
     * @return The result of the function invocation.
     * @throws ScriptException If an error occurs during script execution or function invocation.
     */
    default Object invokeFunction(String script, String functionName, Object... args) throws ScriptException {
        return invokeFunction(script, functionName, null, args);
    }

    /**
     * Invokes a function defined within a previously executed script, using the engine-level scope.
     *
     * @param functionName The name of the function to invoke.
     *                     The function must have been defined within a script that
     *                     was previously executed using {@link #execute}.
     * @param args         The arguments to pass to the function.
     * @return The result of the function invocation.
     * @throws ScriptException If an error occurs during script execution or function invocation.
     */
    default Object invokeFunction(String functionName, Object... args) throws ScriptException {
        return invokeFunction(null, functionName, null, args);
    }

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
     * @param scriptScope    The script scope to use for the script execution.
     *                       This can be used to provide variables and functions to the script. If {@code null}, use engine-level scope
     * @return The result of the script execution
     * @throws ScriptException If an error occurs during script execution
     */
    Object executeCompiled(Object compiledScript, ScriptScope scriptScope) throws ScriptException;

    /**
     * Executes a previously compiled script using the engine-level scope.
     *
     * @param compiledScript The compiled script object (returned by {@link #compile}).
     * @return The result of the script execution.
     * @throws ScriptException If an error occurs during script execution.
     */
    default Object executeCompiled(Object compiledScript) throws ScriptException {
        return executeCompiled(compiledScript, null);
    }

    /**
     * Invokes a function defined in a previously compiled script.
     *
     * @param compiledScript The compiled script object (returned by {@link #compile}}).
     *                       This must be a valid-compiled script object; otherwise, the behavior is undefined and may result in an exception
     * @param functionName   The name of the function to invoke
     * @param scriptScope    The script scope to use for the script execution.
     *                       This can be used to provide variables and functions to the script. If {@code null}, use engine-level scope
     * @param args           The arguments to pass to the function
     * @return The result of the function invocation
     * @throws ScriptException If an error occurs during script execution or function invocation
     */
    Object invokeCompiledFunction(Object compiledScript, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException;

    /**
     * Invokes a function defined within a previously executed compiled script.
     *
     * @param functionName The name of the function to invoke.
     *                     The function must have been defined within a compiled script that
     *                     was previously executed using {@link #executeCompiled}
     * @param scriptScope  The script scope to use. If {@code null}, the engine-level scope is used.
     *                     The function must be available in the provided scope (or the engine-level scope)
     * @param args         The arguments to pass to the function
     * @return The result of the function invocation
     * @throws ScriptException If an error occurs during script execution or function invocation
     */
    default Object invokeCompiledFunction(String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        return invokeCompiledFunction(null, functionName, scriptScope, args);
    }

    /**
     * Invokes a function defined in a previously compiled script, using the engine-level scope.
     *
     * @param compiledScript The compiled script object (returned by {@link #compile}).  If null, the function must have been
     *                       defined in a previously *executed* script.
     * @param functionName   The name of the function to invoke.
     * @param args           The arguments to pass to the function.
     * @return The result of the function invocation.
     * @throws ScriptException If an error occurs during function invocation.
     */
    default Object invokeCompiledFunction(Object compiledScript, String functionName, Object... args) throws ScriptException {
        return invokeCompiledFunction(compiledScript, functionName, null, args);
    }

    /**
     * Invokes a function defined within a previously executed compiled script using engine-level scope.
     *
     * @param functionName The name of the function to invoke.
     *                     The function must have been defined within a compiled script that
     *                     was previously executed using {@link #executeCompiled}
     * @param args         The arguments to pass to the function.
     * @return The result of the function invocation.
     * @throws ScriptException If an error occurs during function invocation.
     */
    default Object invokeCompiledFunction(String functionName, Object... args) throws ScriptException {
        return invokeCompiledFunction(null, functionName, null, args);
    }

    /**
     * Gets the value of a global variable from the engine's scope.
     *
     * @param name The name of the global variable
     * @return The value of the global variable
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
     * @param name The name of the global variable
     */
    void removeGlobalVariable(String name);
}