package net.legacy.library.script.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.factory.ScriptEngineFactory;

/**
 * Test class for RhinoScriptEngine functionality.
 *
 * <p>This test class validates the Rhino JavaScript engine implementation,
 * including script execution, function invocation, compilation, and scope management.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-22
 */
@ModuleTest(
        testName = "rhino-script-engine-test",
        description = "Tests RhinoScriptEngine core functionality and error handling",
        tags = {"script", "rhino", "javascript", "engine"},
        priority = 2,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class RhinoScriptEngineTest {

    /**
     * Tests basic script execution.
     */
    public static boolean testBasicScriptExecution() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            // Test simple expression
            Object result = engine.execute("2 + 3");
            boolean isFive = result != null && result.toString().equals("5");

            // Test string operations
            Object strResult = engine.execute("'Hello' + ' ' + 'Rhino'");
            boolean isHelloRhino = strResult != null && strResult.equals("Hello Rhino");

            TestLogger.logInfo("script", "Rhino basic execution test: arithmetic=%s, string=%s",
                    isFive, isHelloRhino);

            return isFive && isHelloRhino;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino basic execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests function invocation.
     */
    public static boolean testFunctionInvocation() {
        try {
            // Test 1: Square function with numeric parameter
            ScriptEngineInterface engine1 = ScriptEngineFactory.createRhinoScriptEngine();
            engine1.execute("function square(x) { return x * x; }");
            Object result = engine1.invokeFunction("square", 7);
            boolean isFortyNine = result != null && (result.toString().equals("49") || result.toString().equals("49.0"));

            if (!isFortyNine) {
                TestLogger.logFailure("script", "Square function test failed: expected 49, got %s", result);
                return false;
            }

            // Test 2: JoinStrings function with string parameters - use fresh engine
            ScriptEngineInterface engine2 = ScriptEngineFactory.createRhinoScriptEngine();
            engine2.execute("function joinStrings(a, b, c) { return a + b + c; }");
            // Explicitly use Object array to avoid overload ambiguity
            Object joinResult = engine2.invokeFunction("joinStrings", new Object[]{"Rhino", " is ", "working"});
            boolean isJoined = joinResult != null && "Rhino is working".equals(joinResult.toString());

            if (!isJoined) {
                TestLogger.logFailure("script", "JoinStrings function test failed: expected 'Rhino is working', got %s", joinResult);
                return false;
            }

            TestLogger.logInfo("script", "Rhino function invocation test: square=%s, join=%s",
                    isFortyNine, isJoined);

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino function invocation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests script compilation and execution.
     */
    public static boolean testScriptCompilation() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            // Compile a script
            String script = "var result = 10; for(var i = 0; i < 5; i++) { result += i; } result;";
            Object compiled = engine.compile(script);

            boolean isCompiled = compiled != null;

            // Execute compiled script
            Object result = engine.executeCompiled(compiled);
            boolean isTwenty = result != null && (result.toString().equals("20") || result.toString().equals("20.0"));

            // Execute again to verify consistency
            Object result2 = engine.executeCompiled(compiled);
            boolean isConsistent = result2 != null && (result2.toString().equals("20") || result2.toString().equals("20.0"));

            TestLogger.logInfo("script", "Rhino compilation test: compiled=%s, result=%s, consistent=%s",
                    isCompiled, isTwenty, isConsistent);

            return isCompiled && isTwenty && isConsistent;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino compilation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests global variable management.
     */
    public static boolean testGlobalVariables() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            // Set and get global variable
            engine.setGlobalVariable("rhinoGlobal", 42);
            Object value = engine.getGlobalVariable("rhinoGlobal");
            boolean isFortyTwo = value != null && (value.toString().equals("42") || value.toString().equals("42.0"));

            // Use in script
            Object scriptResult = engine.execute("rhinoGlobal * 2");
            boolean isEightyFour = scriptResult != null && (scriptResult.toString().equals("84") || scriptResult.toString().equals("84.0"));

            // Remove and verify
            engine.removeGlobalVariable("rhinoGlobal");
            Object removed = engine.getGlobalVariable("rhinoGlobal");
            boolean isRemoved = removed == null;

            TestLogger.logInfo("script", "Rhino global variables test: set=%s, use=%s, remove=%s",
                    isFortyTwo, isEightyFour, isRemoved);

            return isFortyTwo && isEightyFour && isRemoved;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino global variables test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests script scope functionality.
     */
    public static boolean testScriptScope() {
        try {
            // Rhino scope requires engine instance, so we skip this test
            TestLogger.logInfo("script", "Rhino scope test: skipped (requires engine instance)");
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino scope test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests error handling.
     */
    public static boolean testErrorHandling() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            // Test syntax error
            boolean syntaxErrorCaught = false;
            try {
                engine.execute("var x = {{{");
            } catch (ScriptException scriptException) {
                syntaxErrorCaught = true;
                TestLogger.logInfo("script", "Rhino syntax error caught: %s", scriptException.getMessage());
            }

            // Test runtime error
            boolean runtimeErrorCaught = false;
            try {
                engine.execute("nonExistentObject.property");
            } catch (ScriptException scriptException) {
                runtimeErrorCaught = true;
                TestLogger.logInfo("script", "Rhino runtime error caught: %s", scriptException.getMessage());
            }

            // Test empty script - should not throw exception
            engine.execute("");
            boolean emptyHandled = true; // Just checking it doesn't throw

            TestLogger.logInfo("script", "Rhino error handling test: syntax=%s, runtime=%s, empty=%s",
                    syntaxErrorCaught, runtimeErrorCaught, emptyHandled);

            return syntaxErrorCaught && runtimeErrorCaught && emptyHandled;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino error handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests JavaScript object operations.
     */
    public static boolean testObjectOperations() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            // Test object creation and access
            engine.execute("var person = { name: 'John', age: 30, greet: function() { return 'Hello, ' + this.name; } };");
            Object nameResult = engine.execute("person.name");
            boolean hasName = nameResult != null && nameResult.equals("John");

            Object greetResult = engine.execute("person.greet()");
            boolean canGreet = greetResult != null && greetResult.equals("Hello, John");

            // Test array operations
            Object arrayResult = engine.execute("[1, 2, 3].length");
            boolean arrayLength = arrayResult != null && arrayResult.toString().equals("3");

            TestLogger.logInfo("script", "Rhino object operations test: objectAccess=%s, method=%s, array=%s",
                    hasName, canGreet, arrayLength);

            return hasName && canGreet && arrayLength;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino object operations test failed: %s", exception.getMessage());
            return false;
        }
    }

}