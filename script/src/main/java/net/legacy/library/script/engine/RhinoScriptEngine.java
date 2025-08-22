package net.legacy.library.script.engine;

import lombok.Getter;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.scope.RhinoScriptScope;
import net.legacy.library.script.scope.ScriptScope;
import org.apache.commons.lang3.Validate;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.ContextFactory;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * An implementation of the {@link ScriptEngineInterface} using the Rhino JavaScript engine.
 *
 * @author qwq-dev
 * @since 2025-3-12 16:47
 */
@Getter
public class RhinoScriptEngine implements ScriptEngineInterface {

    private final Context rhinoContext;
    private final ScriptableObject rootScope;

    /**
     * Constructor, initializes the Rhino script engine.
     *
     * @throws IllegalStateException If the Rhino script engine could not be initialized.
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
            throw new IllegalStateException("Rhino script engine could not be initialized.", exception);
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
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        try {
            // Use a provided scope or the root scope if none is provided
            Scriptable scope = scriptScope == null ? rootScope : ((RhinoScriptScope) scriptScope).getScriptable();
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
     * @param scriptScope  {@inheritDoc}
     * @param args         {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeFunction(String script, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        // Use a provided scope or the root scope if none is provided
        Scriptable scope = scriptScope == null ? rootScope : ((RhinoScriptScope) scriptScope).getScriptable();

        try {
            if (script != null) {
                execute(script, scriptScope);
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
     * @param scriptScope    {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object executeCompiled(Object compiledScript, ScriptScope scriptScope) throws ScriptException {
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        if (!(compiledScript instanceof org.mozilla.javascript.Script rhinoCompiledScript)) {
            throw new ScriptException("Invalid compiled script object.");
        }

        // Use a provided scope or the root scope if none is provided
        Scriptable scope = scriptScope == null ? rootScope : ((RhinoScriptScope) scriptScope).getScriptable();

        try {
            return rhinoCompiledScript.exec(rhinoContext, scope);
        } catch (Exception exception) {
            throw new ScriptException("Error executing compiled script: " + exception.getMessage(), exception);
        }
    }

    /**
     * {@inheritDoc}
     *
     * @param compiledScript {@inheritDoc}
     * @param functionName   {@inheritDoc}
     * @param scriptScope    {@inheritDoc}
     * @param args           {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ScriptException {@inheritDoc}
     */
    @Override
    public Object invokeCompiledFunction(Object compiledScript, String functionName, ScriptScope scriptScope, Object... args) throws ScriptException {
        Validate.notNull(rhinoContext, "Rhino context is not initialized.");

        // Use a provided scope or the root scope if none is provided
        Scriptable scope = scriptScope == null ? rootScope : ((RhinoScriptScope) scriptScope).getScriptable();

        try {
            if (compiledScript != null) {
                executeCompiled(compiledScript, scriptScope);
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
     * @param name {@inheritDoc}
     */
    @Override
    public Object getGlobalVariable(String name) {
        Object property = ScriptableObject.getProperty(rootScope, name);
        if (property == Scriptable.NOT_FOUND) {
            return null;
        }
        return Context.jsToJava(property, Object.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param name  {@inheritDoc}
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