package net.legacy.library.script.scope.groovy;

import groovy.lang.Binding;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.script.scope.ScriptScope;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author qwq-dev
 * @since 2025-09-11 03:41
 */
@Getter
@RequiredArgsConstructor
public class GroovyScriptScope implements ScriptScope {

    private final Binding binding;
    private final Map<String, Object> variables;

    public GroovyScriptScope() {
        this.variables = new ConcurrentHashMap<>();
        this.binding = new Binding(variables);
    }

    public GroovyScriptScope(Binding binding) {
        this.binding = binding;
        this.variables = new ConcurrentHashMap<>();
    }

    @Override
    public Object getVariable(String name) {
        try {
            return binding.getVariable(name);
        } catch (groovy.lang.MissingPropertyException e) {
            return null;
        }
    }

    @Override
    public void setVariable(String name, Object value) {
        binding.setVariable(name, value);

        if (variables != null) {
            variables.put(name, value);
        }
    }

    @Override
    public void removeVariable(String name) {
        if (variables != null) {
            variables.remove(name);
        } else {
            binding.setVariable(name, null);
        }
    }

}