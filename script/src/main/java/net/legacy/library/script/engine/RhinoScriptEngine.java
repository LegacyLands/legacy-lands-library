package net.legacy.library.script.engine;

import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import org.apache.commons.lang3.Validate;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

import javax.script.*;

/**
 * An implementation of the {@link ScriptEngineInterface} using the Rhino JavaScript engine.
 *
 * <p><b>About Scope and the {@code invokeFunction} Issue:</b>
 * Rhino uses {@link Context} to manage different scopes. Unlike Nashorn, Rhino requires
 * more explicit scope management.
 *
 * <p>To address this, this implementation does the following:
 * <ol>
 *     <li><b>Explicitly uses {@link Context}:</b> Uses {@link ContextFactory} to create and manage Rhino's {@link Context}.</li>
 *     <li><b>Root Scope:</b> Uses {@code context.initStandardObjects()} to create a root scope ({@link ScriptableObject})
 *         to store global variables and functions.</li>
 *     <li><b>{@code createScope} Method:</b> For each {@code execute} and {@code invokeFunction} call, if {@link Bindings}
 *         are provided, a new {@link Scriptable} object is created, which inherits from the root scope,
 *         allowing local variables to be defined without polluting the global scope.</li>
 * </ol>
 */
@Getter
public class RhinoScriptEngine implements ScriptEngineInterface {
    private final Context rhinoContext;
    private final ScriptableObject rootScope;

    /**
     * Constructor, initializes the Rhino script engine.
     *
     * @throws IllegalStateException If the Rhino script engine is not found.
     */
    public RhinoScriptEngine() {
        try {
            // Use ContextFactory to control Rhino's context creation
            ContextFactory factory = new ContextFactory();
            rhinoContext = factory.enterContext();

            // Set the language version.  170 corresponds to ES5 + some ES6 features
            // 200 is the highest supported and enables more ES6+ features, but it's still not full ES6
            rhinoContext.setLanguageVersion(Context.VERSION_ES6);

            // Create a root scope for the engine
            rootScope = rhinoContext.initStandardObjects();
        } catch (Exception exception) {
            throw new IllegalStateException("Rhino script engine could not be initialized.  " +
                    "Make sure you have included the 'org.mozilla:rhino' dependency in your project.", exception);
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
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");
        try {
            // Use a provided scope or the root scope if none is provided.
            Scriptable scope = (bindings == null) ? rootScope : createScope(bindings);
            return rhinoContext.evaluateString(scope, script, "script", 1, null);

        } catch (Exception exception) {
            throw new ScriptException("Error executing Rhino script: " + exception.getMessage(), exception);
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
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        // Use a provided scope or the root scope if none is provided.
        Scriptable scope = (bindings == null) ? rootScope : createScope(bindings);

        try {
            if (script != null) {
                rhinoContext.evaluateString(scope, script, "script", 1, null);
            }

            Object function = scope.get(functionName, scope);
            if (!(function instanceof org.mozilla.javascript.Function rhinoFunction)) {
                throw new ScriptException("Function '" + functionName + "' not found or not a function.");
            }

            return rhinoFunction.call(rhinoContext, scope, scope, args);
        } catch (Exception exception) {
            throw new ScriptException("Error invoking function '" + functionName + "': " + exception.getMessage(), exception);
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
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        try {
            return rhinoContext.compileString(script, "compiledScript", 1, null);
        } catch (Exception exception) {
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
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        if (!(compiledScript instanceof org.mozilla.javascript.Script rhinoCompiledScript)) {
            throw new ScriptException("Invalid compiled script object.");
        }

        // Use a provided scope or the root scope if none is provided
        Scriptable scope = (bindings == null) ? rootScope : createScope(bindings);
        try {
            return rhinoCompiledScript.exec(rhinoContext, scope);
        } catch (Exception exception) {
            throw new ScriptException("Error executing compiled script: " + exception.getMessage(), exception);
        }
    }

    /**
     * Creates a new scope based on the provided Bindings, inheriting from the root scope.
     *
     * @param bindings The bindings to use for the new scope
     * @return A new Scriptable object representing the new scope
     */
    private Scriptable createScope(Bindings bindings) {
        Scriptable newScope = rhinoContext.newObject(rootScope); // Create a new scope inheriting from rootScope
        newScope.setPrototype(rootScope);  // Set prototype
        newScope.setParentScope(null);  // Set parent scope

        // Copy bindings to the new scope
        for (String key : bindings.keySet()) {
            Object value = bindings.get(key);
            // Wrap Java Object
            Object wrappedValue = Context.javaToJS(value, newScope);
            ScriptableObject.putProperty(newScope, key, wrappedValue);
        }
        return newScope;
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     */
    @Override
    public Object getGlobalVariable(String name) {
        Object value = ScriptableObject.getProperty(rootScope, name);
        return Context.jsToJava(value, Object.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     * @param value {@inheritDoc}
     */
    @Override
    public void setGlobalVariable(String name, Object value) {
        ScriptableObject.putProperty(rootScope, name, Context.javaToJS(value, rootScope));
    }

    /**
     * {@inheritDoc}
     *
     * @param name {@inheritDoc}
     */
    @Override
    public void removeGlobalVariable(String name) {
        rootScope.delete(name);
    }
}