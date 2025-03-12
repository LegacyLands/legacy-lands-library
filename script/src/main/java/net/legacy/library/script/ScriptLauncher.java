package net.legacy.library.script;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.plugin.Plugin;

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
//        RhinoScriptEngine scriptEngine = new RhinoScriptEngine();
//
//        try {
//            // --- Test 1: execute() ---
//            Bindings bindings1 = new SimpleBindings();
//            bindings1.put("x", 10);
//            Object result = scriptEngine.execute("var y = x * 2; y", bindings1);
//            System.out.println("Result of execute(): " + result); // Output: 20.0
//
//            // --- Test 2 invokeFunction() ---
//            String script = "function add(a, b) { return a + b; }";
//            Object sum = scriptEngine.invokeFunction(script, "add", bindings1, 5, 3);
//            System.out.println(sum);
//
//            // --- Test 3: compile() and executeCompiled() ---
//            String compileScript = "function multiply(a,b) {return a * b;}";
//            Object compiled = scriptEngine.compile(compileScript);
//            Object mulResult = scriptEngine.executeCompiled(compiled, null);
//            System.out.println(mulResult);
//
//            // --- Test 4: getGlobalVariable and setGlobalVariable
//            scriptEngine.setGlobalVariable("globalVar", 100);
//            Object get = scriptEngine.getGlobalVariable("globalVar");
//            System.out.println("Result of getGlobalVariable: " + get);
//        } catch (Exception exception) {
//            exception.printStackTrace();
//        }
    }
}
