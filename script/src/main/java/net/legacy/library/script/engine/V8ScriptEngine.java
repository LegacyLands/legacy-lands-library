package net.legacy.library.script.engine;

import com.caoccao.javet.interop.V8Runtime;
import com.caoccao.javet.interop.converters.JavetObjectConverter;
import com.caoccao.javet.values.V8Value;
import com.caoccao.javet.values.reference.V8ValueFunction;
import com.caoccao.javet.values.reference.V8ValueObject;
import io.fairyproject.log.Log;
import lombok.Cleanup;
import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.manager.V8EngineManager;
import net.legacy.library.script.scope.ScriptScope;
import org.apache.commons.lang3.Validate;

/**
 * An implementation of the {@link ScriptEngineInterface} using the V8 JavaScript engine.
 *
 * @author qwq-dev
 * @since 2025-07-20 01:39
 */
@Getter
public class V8ScriptEngine implements ScriptEngineInterface, AutoCloseable {
    private final V8Runtime v8Runtime;
    private final JavetObjectConverter converter;

    /**
     * Constructor, initializes the V8 script engine using Javet.
     *
     * @throws IllegalStateException If the V8 script engine could not be initialized
     */
    public V8ScriptEngine() {
        try {
            // Ensure V8 engine manager is initialized
            if (!V8EngineManager.isInitialized() && !V8EngineManager.initialize()) {
                throw new IllegalStateException("Failed to initialize V8EngineManager");
            }

            // Create runtime through manager
            this.v8Runtime = V8EngineManager.createRuntime();
            this.converter = new JavetObjectConverter();
            this.v8Runtime.setConverter(converter);
        } catch (Exception exception) {
            throw new IllegalStateException("V8 script engine could not be initialized: " + exception.getMessage(), exception);
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
        if (scriptScope != null) {
            Log.warn("ScriptScope is not supported and was passed with a non-null value. It will be ignored.");
        }

        Validate.notNull(v8Runtime, "V8Runtime is not initialized.");

        try {
            @Cleanup
            V8Value result = v8Runtime.getExecutor(script).execute();
            return convertV8ToJava(result);
        } catch (Exception exception) {
            throw new ScriptException("Error executing script: " + exception.getMessage(), exception);
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
        if (scriptScope != null) {
            Log.warn("ScriptScope is not supported and was passed with a non-null value. It will be ignored.");
        }

        Validate.notNull(v8Runtime, "V8Runtime is not initialized.");

        try {
            // Execute script if provided
            if (script != null && !script.trim().isEmpty()) {
                v8Runtime.getExecutor(script).executeVoid();
            }

            V8Value[] v8Args = convertArgumentsToV8(args);

            @Cleanup
            V8ValueObject globalObject = v8Runtime.getGlobalObject();
            @Cleanup
            V8ValueFunction function = retrieveFunction(globalObject, functionName);

            try {
                @Cleanup
                V8Value result = function.call(null, v8Args);
                return convertV8ToJava(result);
            } finally {
                releaseV8Values(v8Args);
            }
        } catch (Exception exception) {
            throw new ScriptException("Error invoking function '" + functionName + "': " + exception.getMessage(), exception);
        }
    }

    private V8ValueFunction retrieveFunction(V8ValueObject globalObject, String functionName) throws Exception {
        if (!globalObject.has(functionName)) {
            throw new ScriptException("Function '" + functionName + "' is not defined.");
        }

        @Cleanup
        V8Value functionValue = globalObject.get(functionName);
        if (!(functionValue instanceof V8ValueFunction function)) {
            throw new ScriptException("'" + functionName + "' is not a function.");
        }

        return function.toClone();
    }

    private V8Value[] convertArgumentsToV8(Object... args) throws Exception {
        V8Value[] v8Args = new V8Value[args.length];

        for (int i = 0; i < args.length; i++) {
            v8Args[i] = converter.toV8Value(v8Runtime, args[i]);
        }

        return v8Args;
    }

    private void releaseV8Values(V8Value... v8Values) {
        for (V8Value value : v8Values) {
            if (value != null) {
                try {
                    value.close();
                } catch (Exception exception) {
                    Log.debug("Failed to close V8 value: %s", exception.getMessage());
                }
            }
        }
    }

    /**
     * {@inheritDoc}
     *
     * <p>V8 does not support separate compilation.
     *
     * @param script {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object compile(String script) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("V8 does not support separate compilation." +
                "Pre-execute the script and get the V8Function for reuse, or use executeScript() each time.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>V8 does not support separate compilation.
     *
     * @param compiledScript {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object executeCompiled(Object compiledScript, ScriptScope scriptScope) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("executeCompiled is not supported because compile is not supported.");
    }

    /**
     * {@inheritDoc}
     *
     * <p>V8 does not support separate compilation.
     *
     * @param compiledScript {@inheritDoc}
     * @param functionName   {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeCompiledFunction(Object compiledScript, String functionName, ScriptScope scriptScope, Object... args) throws UnsupportedOperationException {
        throw new UnsupportedOperationException("invokeCompiledFunction is not supported because compile is not supported.");
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public Object getGlobalVariable(String name) {
        try (V8ValueObject globalObject = v8Runtime.getGlobalObject()) {
            if (!globalObject.has(name)) {
                return null;
            }

            @Cleanup
            V8Value value = globalObject.get(name);
            return convertV8ToJava(value);
        } catch (Exception exception) {
            Log.error("Failed to get global variable '%s': %s", name, exception.getMessage(), exception);
            return null;
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     * @param value {@inheritDoc}
     */
    @Override
    public void setGlobalVariable(String name, Object value) {
        try (V8ValueObject globalObject = v8Runtime.getGlobalObject(); V8Value v8Value = converter.toV8Value(v8Runtime, value)) {
            globalObject.set(name, v8Value);
        } catch (Exception exception) {
            throw new IllegalArgumentException("Failed to set global variable '" + name + "': " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     */
    @Override
    public void removeGlobalVariable(String name) {
        try (V8ValueObject globalObject = v8Runtime.getGlobalObject()) {
            globalObject.delete(name);
        } catch (Exception exception) {
            Log.error("Failed to remove global variable '%s': %s", name, exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void close() {
        try {
            if (v8Runtime != null) {
                v8Runtime.close();
            }
        } catch (Exception exception) {
            Log.error("Failed to close V8 engine: %s", exception.getMessage(), exception);
        }
    }

    private Object convertV8ToJava(V8Value value) throws Exception {
        return value == null ? null : converter.toObject(value);
    }
}