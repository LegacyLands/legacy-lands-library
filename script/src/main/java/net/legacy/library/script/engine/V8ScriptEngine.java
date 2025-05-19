package net.legacy.library.script.engine;

import com.eclipsesource.v8.V8;
import com.eclipsesource.v8.V8Array;
import com.eclipsesource.v8.V8Function;
import com.eclipsesource.v8.V8Object;
import com.eclipsesource.v8.V8ResultUndefined;
import com.eclipsesource.v8.V8ScriptCompilationException;
import com.eclipsesource.v8.V8ScriptExecutionException;
import com.eclipsesource.v8.V8TypedArray;
import com.eclipsesource.v8.V8Value;
import io.fairyproject.log.Log;
import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.scope.ScriptScope;
import org.apache.commons.lang3.Validate;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.BiConsumer;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import java.util.stream.Stream;

/**
 * An implementation of the {@link ScriptEngineInterface} using the V8 JavaScript engine.
 *
 * @author qwq-dev
 * @since 2025-03-13 18:58
 */
@Getter
public class V8ScriptEngine implements ScriptEngineInterface, AutoCloseable {
    private final V8 v8;

    /**
     * Constructor, initializes the V8 script engine.
     *
     * @throws IllegalStateException If the V8 script engine could not be initialized
     */
    public V8ScriptEngine() {
        try {
            this.v8 = V8.createV8Runtime();
        } catch (Exception exception) {
            throw new IllegalStateException("V8 script engine could not be initialized.", exception);
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

        Validate.notNull(v8, "V8 is not initialized.");

        try {
            return v8.executeScript(script);
        } catch (V8ScriptExecutionException | V8ScriptCompilationException exception) {
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

        Validate.notNull(v8, "V8 is not initialized.");

        try {
            v8.executeVoidScript(script);
            V8Function function = (V8Function) v8.get(functionName);
            if (function == null || function.isUndefined()) {
                throw new ScriptException("Function '" + functionName + "' is not defined.");
            }

            V8Array parameters = new V8Array(v8);
            try {
                Stream.of(args).forEach(arg -> pushToV8Array(parameters, arg));
                return convertV8Result(function.call(v8, parameters));
            } finally {
                function.release();
                parameters.release();
            }
        } catch (V8ScriptExecutionException | V8ScriptCompilationException | V8ResultUndefined exception) {
            throw new ScriptException("Error invoking function: " + exception.getMessage(), exception);
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
        return v8.get(name);
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
     * @param value {@inheritDoc}
     */
    @Override
    public void setGlobalVariable(String name, Object value) {
        addValueToV8((key, val) -> {
            switch (val) {
                case null -> v8.addNull(key);
                case Integer i -> v8.add(key, i);
                case Double d -> v8.add(key, d);
                case Boolean b -> v8.add(key, b);
                case String s -> v8.add(key, s);
                case V8Value v8Value -> addV8Value(key, v8Value);
                case Map<?, ?> map -> addMapToV8(key, map);
                case List<?> list -> addListToV8(key, list);
                default ->
                        throw new IllegalArgumentException("Unsupported variable type: " + val.getClass().getName());
            }
        }, name, value);
    }

    @Override
    public void removeGlobalVariable(String name) {
        v8.addNull(name);
    }

    @Override
    public void close() {
        v8.release();
    }

    private void pushToV8(V8Object container, String key, Object value) {
        switch (value) {
            case null -> container.addNull(key);
            case Integer i -> container.add(key, i);
            case Double d -> container.add(key, d);
            case Boolean b -> container.add(key, b);
            case String s -> container.add(key, s);
            case V8Value vv -> {
                switch (vv) {
                    case V8TypedArray vta -> container.add(key, vta);
                    case V8Array va -> container.add(key, va);
                    case V8Function vf -> container.add(key, vf);
                    case V8Object vo -> container.add(key, vo);
                    default ->
                            throw new IllegalArgumentException("Unsupported V8Value subtype in pushToV8: " + vv.getClass().getName());
                }
            }
            case Map<?, ?> map -> {
                V8Object v8Object = new V8Object(v8);
                try {
                    map.forEach((k, v) -> {
                        if (!(k instanceof String)) {
                            throw new IllegalArgumentException("Map keys must be strings.");
                        }
                        pushToV8(v8Object, (String) k, v);
                    });
                    container.add(key, v8Object);
                } finally {
                    v8Object.release();
                }
            }
            case List<?> list -> {
                V8Array v8Array = new V8Array(v8);
                try {
                    list.forEach(element -> pushToV8(v8Array, "", element));
                    container.add(key, v8Array);
                } finally {
                    v8Array.release();
                }
            }
            default -> throw new IllegalArgumentException("Unsupported type in pushToV8: " + value.getClass().getName());
        }
    }

    private void pushToV8Array(V8Array v8Array, Object value) {
        pushToV8(v8Array, "", value);
    }

    private Object convertV8Result(Object result) {
        return switch (result) {
            case V8TypedArray v8TypedArray -> v8TypedArray;
            case V8Array v8Array -> convertV8ArrayToList(v8Array);
            case V8Function v8Function -> v8Function;
            case V8Object v8Object -> convertV8ObjectToMap(v8Object);
            case Double d -> {
                long longVal = d.longValue();
                yield (d == longVal) ? longVal : d;
            }
            default -> result;
        };
    }

    private Map<String, Object> convertV8ObjectToMap(V8Object v8Object) {
        return Arrays.stream(v8Object.getKeys())
                .collect(Collectors.toMap(
                        key -> key,
                        key -> convertV8Result(v8Object.get(key)),
                        (existing, replacement) -> replacement,
                        LinkedHashMap::new));
    }

    private List<Object> convertV8ArrayToList(V8Array v8Array) {
        return IntStream.range(0, v8Array.length())
                .mapToObj(v8Array::get)
                .map(this::convertV8Result)
                .collect(Collectors.toList());
    }

    private void addValueToV8(BiConsumer<String, Object> addFunction, String name, Object value) {
        switch (value) {
            case null -> addFunction.accept(name, null);
            case Integer i -> addFunction.accept(name, i);
            case Double d -> addFunction.accept(name, d);
            case Boolean b -> addFunction.accept(name, b);
            case String s -> addFunction.accept(name, s);
            case V8Value v8Value -> addV8Value(name, v8Value);
            case Map<?, ?> map -> addMapToV8(name, map);
            case List<?> list -> addListToV8(name, list);
            default ->
                    throw new IllegalArgumentException("Unsupported variable type: " + value.getClass().getName());
        }
    }

    private void addV8Value(String name, V8Value v8Value) {
        switch (v8Value) {
            case V8TypedArray v8TypedArray -> v8.add(name, v8TypedArray);
            case V8Array v8Array -> v8.add(name, v8Array);
            case V8Function v8Function -> v8.add(name, v8Function);
            case V8Object v8Obj -> v8.add(name, v8Obj.isUndefined() ? V8.getUndefined() : v8Obj);
            default ->
                    throw new IllegalArgumentException("Unsupported V8Value subtype: " + v8Value.getClass().getName());
        }
    }

    private void addMapToV8(String name, Map<?, ?> map) {
        V8Object v8Object = new V8Object(v8);
        try {
            map.forEach((key, value) -> {
                if (!(key instanceof String)) {
                    throw new IllegalArgumentException("Map keys must be strings.");
                }
                addToV8Object(v8Object, (String) key, value);
            });
            v8.add(name, v8Object);
        } finally {
            v8.release();
        }
    }

    private void addListToV8(String name, List<?> list) {
        V8Array v8Array = new V8Array(v8);
        try {
            list.forEach(element -> addToV8Array(v8Array, element));
            v8.add(name, v8Array);
        } finally {
            v8.release();
        }
    }

    private void addToV8Object(V8Object v8Object, String key, Object value) {
        addValueToV8((k, v) -> {
            switch (v) {
                case Integer i -> v8Object.add(k, i);
                case Double d -> v8Object.add(k, d);
                case Boolean b -> v8Object.add(k, b);
                case String s -> v8Object.add(k, s);
                case V8Value vv -> {
                    switch (vv) {
                        case V8TypedArray vta -> v8Object.add(k, vta);
                        case V8Array va -> v8Object.add(k, va);
                        case V8Function vf -> v8Object.add(k, vf);
                        case V8Object vo -> v8Object.add(k, vo);
                        default ->
                                throw new IllegalArgumentException("Unsupported V8Value subtype in addToV8Object: " + vv.getClass().getName());
                    }
                }
                case null -> v8Object.addNull(k);
                default ->
                        throw new IllegalArgumentException("Unsupported type in addToV8Object: " + v.getClass().getName());
            }
        }, key, value);
    }

    private void addToV8Array(V8Array v8Array, Object value) {
        addValueToV8((dummyKey, v) -> {
            switch (v) {
                case Integer i -> v8Array.push(i);
                case Double d -> v8Array.push(d);
                case Boolean b -> v8Array.push(b);
                case String s -> v8Array.push(s);
                case V8Value vv -> {
                    switch (vv) {
                        case V8TypedArray vta -> v8Array.push(vta);
                        case V8Array va -> v8Array.push(va);
                        case V8Function vf -> v8Array.push(vf);
                        case V8Object vo -> v8Array.push(vo);
                        default ->
                                throw new IllegalArgumentException("Unsupported V8Value subtype in addToV8Array: " + vv.getClass().getName());
                    }
                }
                case null -> v8Array.pushNull();
                default ->
                        throw new IllegalArgumentException("Unsupported type in addToV8Array: " + v.getClass().getName());
            }
        }, "", value);
    }
}