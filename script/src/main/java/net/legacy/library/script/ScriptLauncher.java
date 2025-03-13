package net.legacy.library.script;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.script.engine.RhinoScriptEngine;
import net.legacy.library.script.scope.RhinoScriptScope;

/**
 * The type Script launcher.
 *
 * @author qwq-dev
 * @since 2025-3-12 16:47
 */
@FairyLaunch
@InjectableComponent
public class ScriptLauncher extends Plugin {
    @Override
    public void onPluginEnable() {
        RhinoScriptEngine scriptEngine = new RhinoScriptEngine();

        try {
            // --- Test 1: execute() ---
            RhinoScriptScope scriptScope = new RhinoScriptScope(scriptEngine);
            scriptScope.setVariable("x", 10);
            Object result = scriptEngine.execute("var y = x * 2; y", scriptScope);
            System.out.println("Result of execute(): " + result); // Output: 20.0

            // --- Test 2 invokeFunction() ---
            String script = "function add(a, b) { return a + b; }";
            Object sum = scriptEngine.invokeFunction(script, "add", scriptScope, 5, 3);
            System.out.println(sum); // 8

            // --- Test 3: compile() and executeCompiled() ---
            String compileScript = "function multiply(a,b) {return a * b;}";
            Object compiled = scriptEngine.compile(compileScript);
            scriptEngine.executeCompiled(compiled, scriptScope);
            System.out.println(scriptEngine.invokeCompiledFunction(null, "multiply", scriptScope, 5, 3)); // 15

            // --- Test 4: getGlobalVariable and setGlobalVariable
            scriptEngine.setGlobalVariable("globalVar", 100);
            Object get = scriptEngine.getGlobalVariable("globalVar");
            System.out.println("Result of getGlobalVariable: " + get);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
