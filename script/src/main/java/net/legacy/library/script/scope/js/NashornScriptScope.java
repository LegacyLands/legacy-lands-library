package net.legacy.library.script.scope.js;

import lombok.Getter;
import lombok.Setter;
import net.legacy.library.script.scope.ScriptScope;

import javax.script.Bindings;
import javax.script.SimpleBindings;

/**
 * Nashorn script scope.
 *
 * @author qwq-dev
 * @since 2025-03-13 14:31
 */
@Getter
@Setter
public class NashornScriptScope implements ScriptScope {

    private Bindings bindings;

    public NashornScriptScope() {
        this.bindings = new SimpleBindings();
    }

    public NashornScriptScope(Bindings bindings) {
        this.bindings = bindings;
    }

    public static NashornScriptScope of() {
        return new NashornScriptScope();
    }

    public static NashornScriptScope of(Bindings bindings) {
        return new NashornScriptScope(bindings);
    }

    @Override
    public Object getVariable(String name) {
        return bindings.get(name);
    }

    @Override
    public void setVariable(String name, Object value) {
        bindings.put(name, value);
    }

    @Override
    public void removeVariable(String name) {
        bindings.remove(name);
    }

}
