package net.legacy.library.script.engine;

import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.scope.NashornScriptScope;
import net.legacy.library.script.scope.ScriptScope;
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
 * @author qwq-dev
 * @since 2025-3-12 16:47
 */
@Getter
public class NashornScriptEngine implements ScriptEngineInterface {
    private final ScriptEngine scriptEngine;
    private final Bindings engineScopeBindings;

    /**
     * Constructor, initializes the Nashorn script engine.
     *
     * @throws IllegalStateException If the Nashorn script engine is not found
     */
    public NashornScriptEngine() {
        try {
            scriptEngine = new NashornScriptEngineFactory().getScriptEngine("--language=es6");
            engineScopeBindings = scriptEngine.getContext().getBindings(ScriptContext.ENGINE_SCOPE);
        } catch (Exception exception) {
            throw new IllegalStateException("Nashorn script engine not found. " +
                    "Make sure you are include the 'org.openjdk.nashorn:nashorn-core' dependency.", exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param script      {@inheritDoc}
     * @param scriptScope {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object execute(String script, ScriptScope scriptScope) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        try {
            return scriptEngine.eval(script, scriptScope == null ? engineScopeBindings : ((NashornScriptScope) scriptScope).getBindings());
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
     * @param scriptScope  {@inheritDoc}
     * @param args         {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeFunction(String script, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        // noinspection DuplicatedCode
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(scriptEngine instanceof Invocable invocable)) {
            throw new ScriptException("Nashorn engine does not support function invocation.");
        }

        ScriptContext context = scriptEngine.getContext();

        try {
            // Prioritize passed-in bindings; if none are provided, use the engine scope bindings
            Bindings actualBindings = scriptScope == null ? engineScopeBindings : ((NashornScriptScope) scriptScope).getBindings();

            // If script is provided, execute it first
            if (script != null) {
                execute(script, scriptScope);
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
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object executeCompiled(Object compiledScript, ScriptScope scriptScope) throws ScriptException {
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(compiledScript instanceof CompiledScript nashornCompiledScript)) {
            throw new ScriptException("Invalid compiled script object.");
        }

        try {
            return nashornCompiledScript.eval(scriptScope == null ? engineScopeBindings : ((NashornScriptScope) scriptScope).getBindings());
        } catch (javax.script.ScriptException exception) {
            throw new ScriptException("Error executing compiled script: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param compiledScript {@inheritDoc}
     * @param functionName   {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeCompiledFunction(Object compiledScript, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        // noinspection DuplicatedCode
        Validate.notNull(scriptEngine, "Nashorn engine is not initialized.");

        if (!(scriptEngine instanceof Invocable invocable)) {
            throw new ScriptException("Nashorn engine does not support function invocation.");
        }

        ScriptContext context = scriptEngine.getContext();

        try {
            // Prioritize passed-in bindings. If none, use engine scope
            Bindings actualBindings = scriptScope == null ? engineScopeBindings : ((NashornScriptScope) scriptScope).getBindings();

            // Execute the compiled script with the chosen bindings
            if (compiledScript != null) {
                executeCompiled(compiledScript, scriptScope);
            }

            // Set bindings for invokeFunction
            context.setBindings(actualBindings, ScriptContext.ENGINE_SCOPE);

            // Invoke the function
            return invocable.invokeFunction(functionName, args);
        } catch (javax.script.ScriptException | NoSuchMethodException exception) {
            throw new ScriptException("Error invoking compiled function '" + functionName + "': " + exception.getMessage(), exception);
        } finally {
            // Restore original engine scope.
            context.setBindings(engineScopeBindings, ScriptContext.ENGINE_SCOPE);
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
     * @param name {@inheritDoc}
     */
    @Override
    public void removeGlobalVariable(String name) {
        getScriptEngine().getBindings(ScriptContext.ENGINE_SCOPE).remove(name);
    }
}