package net.legacy.library.script.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.factory.ScriptEngineFactory;
import net.legacy.library.script.scope.NashornScriptScope;

/**
 * Test class for NashornScriptEngine functionality.
 *
 * <p>This test class validates the Nashorn JavaScript engine implementation,
 * including script execution, function invocation, compilation, and scope management.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-22
 */
@ModuleTest(
        testName = "nashorn-script-engine-test",
        description = "Tests NashornScriptEngine core functionality and error handling",
        tags = {"script", "nashorn", "javascript", "engine"},
        priority = 1,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class NashornScriptEngineTest {

    /**
     * Tests basic script execution.
     */
    public static boolean testBasicScriptExecution() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            // Test simple expression
            Object result = engine.execute("1 + 2");
            boolean isThree = result != null && result.toString().equals("3");

            // Test variable assignment and retrieval
            Object varResult = engine.execute("var x = 10; x * 2");
            boolean isTwenty = varResult != null && (varResult.toString().equals("20") || varResult.toString().equals("20.0"));

            TestLogger.logInfo("script", "Basic execution test: simpleExpression=%s, variableAssignment=%s",
                    isThree, isTwenty);

            return isThree && isTwenty;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Basic execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests function invocation.
     */
    public static boolean testFunctionInvocation() {
        try {
            // Test 1: Add function with numeric parameters
            ScriptEngineInterface engine1 = ScriptEngineFactory.createNashornScriptEngine();
            engine1.execute("function add(a, b) { return a + b; }");
            TestLogger.logInfo("script", "About to call add function with parameters: 5, 3");
            Object result = engine1.invokeFunction("add", 5, 3);
            TestLogger.logInfo("script", "Add function returned: %s", result);
            boolean isEight = result != null && (result.toString().equals("8") || result.toString().equals("8.0"));

            if (!isEight) {
                TestLogger.logFailure("script", "Add function test failed: expected 8, got %s", result);
                return false;
            }

            // Test 2: Concat function with string parameters - use fresh engine to avoid state issues
            ScriptEngineInterface engine2 = ScriptEngineFactory.createNashornScriptEngine();
            engine2.execute("function concat(a, b) { return a + b; }");
            TestLogger.logInfo("script", "About to call concat function with parameters: 'Hello', ' World'");

            Object strResult;
            try {
                // Explicitly call the correct overload by casting the string arguments to Object
                strResult = engine2.invokeFunction("concat", new Object[]{"Hello", " World"});
                TestLogger.logInfo("script", "Concat function returned: %s", strResult);
            } catch (Exception exception) {
                TestLogger.logFailure("script", "Failed to invoke concat: %s", exception.getMessage());
                throw exception;
            }

            boolean isHelloWorld = strResult != null && "Hello World".equals(strResult.toString());

            if (!isHelloWorld) {
                TestLogger.logFailure("script", "Concat function test failed: expected 'Hello World', got %s", strResult);
                return false;
            }

            TestLogger.logInfo("script", "Function invocation test: addition=%s, concatenation=%s",
                    isEight, isHelloWorld);

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Function invocation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests script compilation and execution.
     */
    public static boolean testScriptCompilation() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            // Compile a script
            String script = "function multiply(a, b) { return a * b; } multiply(4, 5);";
            Object compiled = engine.compile(script);

            boolean isCompiled = compiled != null;

            // Execute compiled script
            Object result = engine.executeCompiled(compiled);
            boolean isTwenty = result != null && (result.toString().equals("20") || result.toString().equals("20.0"));

            // Execute compiled script multiple times
            Object result2 = engine.executeCompiled(compiled);
            boolean isConsistent = result2 != null && (result2.toString().equals("20") || result2.toString().equals("20.0"));

            TestLogger.logInfo("script", "Script compilation test: compiled=%s, executed=%s, consistent=%s",
                    isCompiled, isTwenty, isConsistent);

            return isCompiled && isTwenty && isConsistent;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Script compilation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests global variable management.
     */
    public static boolean testGlobalVariables() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            // Set global variable
            engine.setGlobalVariable("globalVar", "TestValue");

            // Get global variable
            Object value = engine.getGlobalVariable("globalVar");
            boolean isTestValue = "TestValue".equals(value);

            // Use global variable in script
            Object scriptResult = engine.execute("globalVar + ' from script'");
            boolean isFromScript = scriptResult != null && scriptResult.equals("TestValue from script");

            // Remove global variable
            engine.removeGlobalVariable("globalVar");
            Object removedValue = engine.getGlobalVariable("globalVar");
            boolean isRemoved = removedValue == null;

            TestLogger.logInfo("script", "Global variables test: set=%s, useInScript=%s, removed=%s",
                    isTestValue, isFromScript, isRemoved);

            return isTestValue && isFromScript && isRemoved;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Global variables test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests script scope functionality.
     */
    public static boolean testScriptScope() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();
            NashornScriptScope scope = new NashornScriptScope();

            // Set variable in scope
            scope.setVariable("scopeVar", "ScopeValue");

            // Execute script with scope
            Object result = engine.execute("scopeVar + ' in scope'", scope);
            boolean hasScopeValue = result != null && result.equals("ScopeValue in scope");

            // Test scope isolation
            Object globalResult = engine.execute("typeof scopeVar");
            boolean isIsolated = globalResult != null && globalResult.equals("undefined");

            TestLogger.logInfo("script", "Script scope test: scopeValue=%s, isolated=%s",
                    hasScopeValue, isIsolated);

            return hasScopeValue && isIsolated;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Script scope test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests error handling with invalid scripts.
     */
    public static boolean testErrorHandling() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            // Test syntax error
            boolean syntaxErrorCaught = false;
            try {
                engine.execute("function { invalid syntax");
            } catch (ScriptException scriptException) {
                syntaxErrorCaught = true;
                TestLogger.logInfo("script", "Syntax error caught: %s", scriptException.getMessage());
            }

            // Test runtime error
            boolean runtimeErrorCaught = false;
            try {
                engine.execute("undefinedFunction()");
            } catch (ScriptException scriptException) {
                runtimeErrorCaught = true;
                TestLogger.logInfo("script", "Runtime error caught: %s", scriptException.getMessage());
            }

            // Test null script
            boolean nullScriptHandled = false;
            try {
                engine.execute(null);
            } catch (Exception exception) {
                nullScriptHandled = true;
                TestLogger.logInfo("script", "Null script handled: %s", exception.getMessage());
            }

            TestLogger.logInfo("script", "Error handling test: syntaxError=%s, runtimeError=%s, nullScript=%s",
                    syntaxErrorCaught, runtimeErrorCaught, nullScriptHandled);

            return syntaxErrorCaught && runtimeErrorCaught && nullScriptHandled;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Error handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests complex JavaScript operations.
     */
    public static boolean testComplexOperations() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            // Test array operations
            Object arrayResult = engine.execute("[1, 2, 3, 4, 5].map(function(x) { return x * 2; }).reduce(function(a, b) { return a + b; })");
            boolean isThirty = arrayResult != null && (arrayResult.toString().equals("30") || arrayResult.toString().equals("30.0"));

            // Test object operations
            engine.execute("var obj = { name: 'test', value: 42 };");
            Object objResult = engine.execute("obj.name + ':' + obj.value");
            boolean isTestFortyTwo = objResult != null && objResult.equals("test:42");

            // Test closure
            engine.execute("function makeCounter() { var count = 0; return function() { return ++count; }; }");
            engine.execute("var counter = makeCounter();");
            Object count1 = engine.execute("counter()");
            Object count2 = engine.execute("counter()");
            boolean closureWorks = ("1".equals(count1.toString()) || "1.0".equals(count1.toString())) &&
                    ("2".equals(count2.toString()) || "2.0".equals(count2.toString()));

            TestLogger.logInfo("script", "Complex operations test: array=%s, object=%s, closure=%s",
                    isThirty, isTestFortyTwo, closureWorks);

            return isThirty && isTestFortyTwo && closureWorks;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Complex operations test failed: %s", exception.getMessage());
            return false;
        }
    }

}