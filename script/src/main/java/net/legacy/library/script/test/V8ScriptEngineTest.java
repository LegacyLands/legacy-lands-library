package net.legacy.library.script.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.exception.ScriptException;
import net.legacy.library.script.factory.ScriptEngineFactory;

/**
 * Test class for V8ScriptEngine functionality.
 *
 * <p>This test class validates the V8 JavaScript engine implementation,
 * including script execution, function invocation, compilation, and scope management.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-22
 */
@ModuleTest(
        testName = "v8-script-engine-test",
        description = "Tests V8ScriptEngine core functionality and error handling",
        tags = {"script", "v8", "javascript", "engine"},
        priority = 3,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class V8ScriptEngineTest {
    /**
     * Tests basic script execution.
     */
    public static boolean testBasicScriptExecution() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Test arithmetic
            Object result = engine.execute("3 * 4");
            boolean isTwelve = result != null && result.toString().equals("12");

            // Test boolean operations
            Object boolResult = engine.execute("true && false || true");
            boolean isTrue = boolResult != null && boolResult.toString().equals("true");

            TestLogger.logInfo("script", "V8 basic execution test: arithmetic=%s, boolean=%s",
                    isTwelve, isTrue);

            return isTwelve && isTrue;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 basic execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests function invocation.
     */
    public static boolean testFunctionInvocation() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Test 1: Define function using var (to ensure global scope)
            engine.execute("var divide = function(a, b) { return a / b; };");
            Object result = engine.invokeFunction("divide", 20, 4);
            boolean isFive = result != null && (result.toString().equals("5") || result.toString().equals("5.0"));

            if (!isFive) {
                TestLogger.logFailure("script", "Divide function test failed: expected 5, got %s", result);
                return false;
            }

            // Test 2: Test regular function declaration (automatically global)
            engine.execute("function max(a, b) { return a > b ? a : b; }");
            Object maxResult = engine.invokeFunction("max", 10, 15);
            boolean isFifteen = maxResult != null && maxResult.toString().equals("15");

            if (!isFifteen) {
                TestLogger.logFailure("script", "Max function test failed: expected 15, got %s", maxResult);
                return false;
            }

            TestLogger.logInfo("script", "V8 function invocation test: divide=%s, max=%s",
                    isFive, isFifteen);

            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 function invocation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests script compilation and execution.
     */
    public static boolean testScriptCompilation() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // V8 doesn't support separate compilation, so we test UnsupportedOperationException
            boolean compilationNotSupported = false;
            try {
                String script = "let total = 0; [1, 2, 3, 4, 5].forEach(n => total += n); total;";
                engine.compile(script);
            } catch (UnsupportedOperationException unsupportedOperationException) {
                compilationNotSupported = true;
                TestLogger.logInfo("script", "V8 compilation correctly throws UnsupportedOperationException: %s", unsupportedOperationException.getMessage());
            }

            // Test that we can still execute the script directly using a different variable name
            String script = "let sum = 0; [1, 2, 3, 4, 5].forEach(n => sum += n); sum;";
            Object result = engine.execute(script);
            boolean isFifteen = result != null && result.toString().equals("15");

            // Test different script for consistency to avoid variable redeclaration
            String script2 = "let count = 5; count * 3;";
            Object result2 = engine.execute(script2);
            boolean isConsistent = result2 != null && result2.toString().equals("15");

            TestLogger.logInfo("script", "V8 compilation test: notSupported=%s, directExecution=%s, consistent=%s",
                    compilationNotSupported, isFifteen, isConsistent);

            return compilationNotSupported && isFifteen && isConsistent;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 compilation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests global variable management.
     */
    public static boolean testGlobalVariables() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Set global with object
            engine.setGlobalVariable("v8Config", "{ version: 8, enabled: true }");
            Object value = engine.getGlobalVariable("v8Config");
            boolean hasValue = value != null;

            // Use in script
            engine.setGlobalVariable("v8Number", 100);
            Object scriptResult = engine.execute("v8Number / 2");
            boolean isFifty = scriptResult != null && scriptResult.toString().equals("50");

            // Remove global
            engine.removeGlobalVariable("v8Number");
            Object removed = engine.getGlobalVariable("v8Number");
            boolean isRemoved = removed == null;

            TestLogger.logInfo("script", "V8 global variables test: set=%s, use=%s, remove=%s",
                    hasValue, isFifty, isRemoved);

            return hasValue && isFifty && isRemoved;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 global variables test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests error handling.
     */
    public static boolean testErrorHandling() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Test syntax error with modern JS
            boolean syntaxErrorCaught = false;
            try {
                engine.execute("const const = 5;"); // invalid syntax
            } catch (ScriptException scriptException) {
                syntaxErrorCaught = true;
                TestLogger.logInfo("script", "V8 syntax error caught: %s", scriptException.getMessage());
            }

            // Test runtime error
            boolean runtimeErrorCaught = false;
            try {
                engine.execute("null.toString()");
            } catch (ScriptException scriptException) {
                runtimeErrorCaught = true;
                TestLogger.logInfo("script", "V8 runtime error caught: %s", scriptException.getMessage());
            }

            // Test invalid function call
            boolean invalidFunctionCaught = false;
            try {
                engine.invokeFunction("nonExistentFunction", 1, 2);
            } catch (ScriptException scriptException) {
                invalidFunctionCaught = true;
                TestLogger.logInfo("script", "V8 invalid function error caught: %s", scriptException.getMessage());
            }

            TestLogger.logInfo("script", "V8 error handling test: syntax=%s, runtime=%s, invalidFunction=%s",
                    syntaxErrorCaught, runtimeErrorCaught, invalidFunctionCaught);

            return syntaxErrorCaught && runtimeErrorCaught && invalidFunctionCaught;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 error handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests modern JavaScript features.
     */
    public static boolean testModernJavaScriptFeatures() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Test template literals
            Object templateResult = engine.execute("const name = 'V8'; `Hello ${name}!`;");
            boolean hasTemplate = templateResult != null && templateResult.equals("Hello V8!");

            // Test destructuring
            engine.execute("const [first, second] = [10, 20];");
            Object destructResult = engine.execute("first + second");
            boolean hasDestruct = destructResult != null && destructResult.toString().equals("30");

            // Test spread operator
            Object spreadResult = engine.execute("Math.max(...[1, 5, 3, 9, 2])");
            boolean hasSpread = spreadResult != null && spreadResult.toString().equals("9");

            TestLogger.logInfo("script", "V8 modern JS test: template=%s, destructuring=%s, spread=%s",
                    hasTemplate, hasDestruct, hasSpread);

            return hasTemplate && hasDestruct && hasSpread;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 modern JS test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests asynchronous operations support.
     */
    public static boolean testAsyncOperations() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            // Test Promise creation using var for global scope
            engine.execute("var promise = new Promise(function(resolve) { resolve(42); });");
            Object promiseExists = engine.execute("promise instanceof Promise");
            boolean hasPromise = promiseExists != null && promiseExists.toString().equals("true");

            // Test function that returns a Promise (V8 may not support async/await syntax)
            engine.execute("var getNumber = function() { return new Promise(function(resolve) { resolve(99); }); };");
            Object asyncResult = engine.execute("getNumber() instanceof Promise");
            boolean hasAsync = asyncResult != null && asyncResult.toString().equals("true");

            TestLogger.logInfo("script", "V8 async operations test: promise=%s, promiseFunction=%s",
                    hasPromise, hasAsync);

            return hasPromise && hasAsync;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 async operations test failed: %s", exception.getMessage());
            return false;
        }
    }
}