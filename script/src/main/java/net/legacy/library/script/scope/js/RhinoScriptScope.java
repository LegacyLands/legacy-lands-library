package net.legacy.library.script.scope.js;

import lombok.Getter;
import lombok.Setter;
import net.legacy.library.script.engine.js.RhinoScriptEngine;
import net.legacy.library.script.scope.ScriptScope;
import org.mozilla.javascript.Context;
import org.mozilla.javascript.Scriptable;
import org.mozilla.javascript.ScriptableObject;

/**
 * Rhino script scope.
 *
 * @author qwq-dev
 * @since 2025-03-13 14:31
 */
@Getter
@Setter
public class RhinoScriptScope implements ScriptScope {

    private Scriptable scriptable;
    private ScriptableObject scriptEngineRootScope;

    public RhinoScriptScope(RhinoScriptEngine rhinoScriptEngine) {
        this.scriptEngineRootScope = rhinoScriptEngine.getRootScope();
        this.scriptable = rhinoScriptEngine.getRhinoContext().newObject(scriptEngineRootScope);
        this.scriptable.setPrototype(scriptEngineRootScope);
        this.scriptable.setParentScope(null);
    }

    public static RhinoScriptScope of(RhinoScriptEngine rhinoScriptEngine) {
        return new RhinoScriptScope(rhinoScriptEngine);
    }

    @Override
    public Object getVariable(String name) {
        return Context.jsToJava(ScriptableObject.getProperty(scriptEngineRootScope, name), Object.class);
    }

    @Override
    public void setVariable(String name, Object value) {
        ScriptableObject.putProperty(scriptEngineRootScope, name, Context.javaToJS(value, scriptEngineRootScope));
    }

    @Override
    public void removeVariable(String name) {
        scriptEngineRootScope.delete(name);
    }

}
