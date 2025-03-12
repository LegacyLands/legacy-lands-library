package net.legacy.library.script.engine;

import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import org.apache.commons.lang3.Validate;
import org.openjdk.nashorn.api.scripting.NashornScriptEngineFactory;

import javax.script.Bindings;
import javax.script.Compilable;
import javax.script.CompiledScript;
import javax.script.Invocable;
import javax.script.ScriptContext;
import javax.script.ScriptEngine;

/**
 * An implementation of the {@link ScriptEngineInterface} using the Nashorn JavaScript engine.
 *
 * <p><b>About Scope and the {@code invokeFunction} Issue (The problem we encountered earlier):</b>
 * Nashorn uses {@link ScriptContext} to manage different scopes (global scope, engine scope, etc.).
 * {@code invokeFunction}, when looking up a function, defaults to searching the <em>global scope</em> of the
 * {@link ScriptEngine}, <em>not</em> the {@link Bindings} object you pass to it. The {@link Bindings} object is only used
 * during script <em>execution</em> (e.g., {@code eval} or {@code execute}) to set the script's context.
 *
 * <p>To address this issue, this implementation does the following:
 * <ol>
 *     <li><b>Explicitly uses {@link ScriptContext}:</b> Obtains the {@link ScriptContext} via
 *         {@code scriptEngine.getContext()} and uses it to manage {@link Bindings}.</li>
 *     <li><b>{@code ENGINE_SCOPE}:</b> Uses {@code context.getBindings(ScriptContext.ENGINE_SCOPE)} to get the
 *         engine-level {@link Bindings}.  This ensures that {@code execute} and {@code invokeFunction} use the same
 *         context. Alternatively, you can create a new {@link Bindings} using {@code createBindings()} and then
 *         set it to the engine scope using {@code context.setBindings(bindings, ScriptContext.ENGINE_SCOPE)}.</li>
 *     <li><b>Prioritizes passed-in {@link Bindings}:</b> The {@code invokeFunction} method prioritizes the
 *         {@link Bindings} passed in by the caller; if none are provided, it uses the engine scope {@link Bindings}.</li>
 *     <li><b>Supports two invocation styles:</b> The {@code invokeFunction} method supports both the
 *         {@code execute} then {@code invokeFunction} calling style and passing the script directly
 *         into {@code invokeFunction} (although passing the script directly is not recommended
 *         because it's less flexible and more error-prone).</li>
 *     <li><b>Restores Engine Scope:</b> Restores the original Engine Scope after {@code invokeFunction}
 *         executes to prevent accidental modifications to the global environment.</li>
 * </ol>
 */
@Getter
public class NashornScriptEngine implements ScriptEngineInterface {
    private final ScriptEngine scriptEngine;

    /**
     * Constructor, initializes the Nashorn script engine.
     *
     * @throws IllegalStateException If the Nashorn script engine is not found
     */
    public NashornScriptEngine() {
        try {
            scriptEngine = new NashornScriptEngineFactory().getScriptEngine("--language=es6");
        } catch (Exception exception) {
            throw new IllegalStateException("Nashorn script engine not found. " +
                    "Make sure you are include the 'org.openjdk.nashorn:nashorn-core' dependency.", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script   {@inheritDoc}
     * @param bindings {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object execute(String script, Bindings bindings) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        try {
            return scriptEngine.eval(script, bindings == null ? scriptEngine.createBindings() : bindings);
        } catch (javax.script.ScriptException exception) {
            throw new ScriptException("Error executing Nashorn script: " + exception.getMessage(), exception);
        } catch (Exception exception) {
            throw new ScriptException("Unexpected error during Nashorn script execution: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script       {@inheritDoc}
     * @param functionName {@inheritDoc}
     * @param bindings     {@inheritDoc}
     * @param args         {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeFunction(String script, String functionName, Bindings bindings, Object... args) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(scriptEngine instanceof Invocable invocable)) {
            throw new ScriptException("Nashorn engine does not support function invocation.");
        }

        ScriptContext context = scriptEngine.getContext();
        Bindings engineScopeBindings = context.getBindings(ScriptContext.ENGINE_SCOPE);

        try {
            // Prioritize passed-in bindings; if none are provided, use the engine scope bindings
            Bindings actualBindings = (bindings != null) ? bindings : engineScopeBindings;

            // If script is provided, execute it first
            if (script != null) {
                execute(script, actualBindings);
            }

            // Set the passed-in (or engine scope) bindings to ENGINE_SCOPE
            // ensuring that invokeFunction can find the function in the correct context
            context.setBindings(actualBindings, ScriptContext.ENGINE_SCOPE);

            // Invoke the function
            return invocable.invokeFunction(functionName, args);
        } catch (javax.script.ScriptException | NoSuchMethodException exception) {
            throw new ScriptException("Error invoking function '" + functionName + "': " + exception.getMessage(), exception);
        } finally {
            // Restore the original Engine Scope to prevent polluting the global environment
            context.setBindings(engineScopeBindings, ScriptContext.ENGINE_SCOPE);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object compile(String script) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(scriptEngine instanceof Compilable compilable)) {
            throw new ScriptException("Nashorn scriptEngine does not support script compilation.");
        }

        try {
            return compilable.compile(script);
        } catch (javax.script.ScriptException exception) {
            throw new ScriptException("Error compiling script: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param compiledScript {@inheritDoc}
     * @param bindings       {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object executeCompiled(Object compiledScript, Bindings bindings) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(compiledScript instanceof CompiledScript nashornCompiledScript)) {
            throw new ScriptException("Invalid compiled script object.");
        }

        try {
            return nashornCompiledScript.eval(bindings == null ? scriptEngine.createBindings() : bindings);
        } catch (javax.script.ScriptException exception) {
            throw new ScriptException("Error executing compiled script: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Object getGlobalVariable(String name) {
        return getScriptEngine().getBindings(ScriptContext.ENGINE_SCOPE).get(name);
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     * @param value {@inheritDoc}
     */
    @Override
    public void setGlobalVariable(String name, Object value) {
        getScriptEngine().getBindings(ScriptContext.ENGINE_SCOPE).put(name, value);
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     */
    @Override
    public void removeGlobalVariable(String name) {
        getScriptEngine().getBindings(ScriptContext.ENGINE_SCOPE).remove(name);
    }
}