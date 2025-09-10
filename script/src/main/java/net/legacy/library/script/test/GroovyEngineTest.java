package net.legacy.library.script.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.engine.groovy.GroovyEngine;
import net.legacy.library.script.scope.groovy.GroovyScriptScope;

/**
 * Test class for GroovyEngine functionality.
 *
 * <p>This test class validates the Groovy script engine implementation,
 * including script execution, function invocation, compilation, closures, and scope management.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-09-10
 */
@ModuleTest(
        testName = "groovy-engine-test",
        description = "Tests GroovyEngine core functionality including closures and error handling",
        tags = {"script", "groovy", "engine"},
        priority = 1,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class GroovyEngineTest {

    /**
     * Tests basic script execution.
     */
    public static boolean testBasicScriptExecution() {
        try {
            ScriptEngineInterface engine = new GroovyEngine();

            // Test simple expression
            Object result = engine.execute("1 + 2");
            boolean isThree = result != null && (result.equals(3) || "3".equals(result.toString()));

            // Test variable assignment and retrieval
            Object varResult = engine.execute("def x = 10; x * 2");
            boolean isTwenty = varResult != null && (varResult.equals(20) || "20".equals(varResult.toString()));

            // Test Groovy string interpolation
            Object gstringResult = engine.execute("def name = 'Groovy'; \"Hello, ${name}!\"");
            boolean isHelloGroovy = gstringResult != null && "Hello, Groovy!".equals(gstringResult.toString());

            TestLogger.logInfo("script", "Basic execution test: simpleExpression=%s, variableAssignment=%s, gstring=%s",
                    isThree, isTwenty, isHelloGroovy);

            return isThree && isTwenty && isHelloGroovy;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Basic execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests function invocation including closures.
     */
    public static boolean testFunctionInvocation() {
        try {
            ScriptEngineInterface engine = new GroovyEngine();

            // Test 1: Regular function with numeric parameters (using closure)
            engine.execute("add = { a, b -> a + b }");
            Object result = engine.invokeFunction("add", 5, 3);
            boolean isEight = result != null && (result.equals(8) || "8".equals(result.toString()));

            if (!isEight) {
                TestLogger.logFailure("script", "Add function test failed: expected 8, got %s", result);
                return false;
            }

            // Test 2: Closure definition and invocation
            ScriptEngineInterface engine2 = new GroovyEngine();
            engine2.execute("multiply = { a, b -> a * b }");
            Object closureResult = engine2.invokeFunction("multiply", 4, 5);
            boolean isTwenty = closureResult != null && (closureResult.equals(20) || "20".equals(closureResult.toString()));

            if (!isTwenty) {
                TestLogger.logFailure("script", "Closure test failed: expected 20, got %s", closureResult);
                return false;
            }

            // Test 3: String concatenation function
            ScriptEngineInterface engine3 = new GroovyEngine();
            engine3.execute("concat = { a, b -> a + b }");
            Object strResult = engine3.invokeFunction("concat", new Object[]{"Hello", " World"});
            boolean isHelloWorld = strResult != null && "Hello World".equals(strResult.toString());

            if (!isHelloWorld) {
                TestLogger.logFailure("script", "String concat test failed: expected 'Hello World', got %s", strResult);
                return false;
            }

            TestLogger.logInfo("script", "Function invocation test: function=%s, closure=%s, stringConcat=%s",
                    isEight, isTwenty, isHelloWorld);

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
            ScriptEngineInterface engine = new GroovyEngine();

            // Compile a script
            String script = "multiply = { a, b -> a * b }; multiply(4, 5)";
            Object compiled = engine.compile(script);
            boolean isCompiled = compiled != null;

            // Execute compiled script
            Object result = engine.executeCompiled(compiled);
            boolean isTwenty = result != null && (result.equals(20) || "20".equals(result.toString()));

            // Execute compiled script multiple times
            Object result2 = engine.executeCompiled(compiled);
            boolean isConsistent = result2 != null && (result2.equals(20) || "20".equals(result2.toString()));

            // Test compiled function invocation
            String functionScript = "square = { n -> n * n }";
            Object compiledFunc = engine.compile(functionScript);
            engine.executeCompiled(compiledFunc);
            Object funcResult = engine.invokeCompiledFunction("square", 6);
            boolean isThirtySix = funcResult != null && (funcResult.equals(36) || "36".equals(funcResult.toString()));

            TestLogger.logInfo("script", "Script compilation test: compiled=%s, executed=%s, consistent=%s, function=%s",
                    isCompiled, isTwenty, isConsistent, isThirtySix);

            return isCompiled && isTwenty && isConsistent && isThirtySix;
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
            ScriptEngineInterface engine = new GroovyEngine();

            // Set global variable
            engine.setGlobalVariable("globalVar", "TestValue");

            // Get global variable
            Object value = engine.getGlobalVariable("globalVar");
            boolean isTestValue = "TestValue".equals(value);

            // Use global variable in script
            Object scriptResult = engine.execute("globalVar + ' from script'");
            boolean isFromScript = scriptResult != null && scriptResult.equals("TestValue from script");

            // Set complex global variable
            engine.setGlobalVariable("globalList", java.util.Arrays.asList(1, 2, 3));
            Object listResult = engine.execute("globalList.sum()");
            boolean isSix = listResult != null && (listResult.equals(6) || "6".equals(listResult.toString()));

            // Remove global variable
            engine.removeGlobalVariable("globalVar");
            Object removedValue = engine.getGlobalVariable("globalVar");
            boolean isRemoved = removedValue == null;

            TestLogger.logInfo("script", "Global variables test: set=%s, useInScript=%s, complexVar=%s, removed=%s",
                    isTestValue, isFromScript, isSix, isRemoved);

            return isTestValue && isFromScript && isSix && isRemoved;
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
            ScriptEngineInterface engine = new GroovyEngine();
            GroovyScriptScope scope = new GroovyScriptScope();

            // Set variable in scope
            scope.setVariable("scopeVar", "ScopeValue");

            // Execute script with scope
            Object result = engine.execute("scopeVar + ' in scope'", scope);
            boolean hasScopeValue = result != null && result.equals("ScopeValue in scope");

            // Test scope isolation - global scope shouldn't have the scope variable
            // Check if the global engine can access the scope variable
            boolean isIsolated = true;
            try {
                Object globalResult = engine.execute("scopeVar + ' leaked'");
                // If we get here without exception, variable leaked
                isIsolated = false;
            } catch (Exception exception) {
                // Expected - scopeVar should not be accessible in global scope
                isIsolated = true;
            }

            // Test scope variable manipulation
            scope.setVariable("counter", 0);
            engine.execute("counter = counter + 1", scope);
            Object counterValue = scope.getVariable("counter");
            boolean isOne = counterValue != null && (counterValue.equals(1) || "1".equals(counterValue.toString()));

            // Test scope variable removal
            scope.removeVariable("scopeVar");
            Object removedVar = scope.getVariable("scopeVar");
            boolean isVarRemoved = removedVar == null;

            TestLogger.logInfo("script", "Script scope test: scopeValue=%s, isolated=%s, manipulation=%s, removal=%s",
                    hasScopeValue, isIsolated, isOne, isVarRemoved);

            return hasScopeValue && isIsolated && isOne && isVarRemoved;
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
            ScriptEngineInterface engine = new GroovyEngine();

            // Test syntax error
            boolean syntaxErrorCaught = false;
            try {
                engine.execute("def { invalid syntax without closing brace");
            } catch (Exception exception) {
                syntaxErrorCaught = true;
                TestLogger.logInfo("script", "Syntax error caught: %s", exception.getMessage());
            }

            // Test runtime error
            boolean runtimeErrorCaught = false;
            try {
                engine.execute("nonExistentVariable.someMethod()");
            } catch (Exception exception) {
                runtimeErrorCaught = true;
                TestLogger.logInfo("script", "Runtime error caught: %s", exception.getMessage());
            }

            // Test null script
            boolean nullScriptHandled = false;
            try {
                engine.execute(null);
            } catch (Exception exception) {
                nullScriptHandled = true;
                TestLogger.logInfo("script", "Null script handled: %s", exception.getMessage());
            }

            // Test unsupported scope type
            boolean unsupportedScopeHandled = false;
            try {
                engine.execute("1 + 1", new net.legacy.library.script.scope.js.NashornScriptScope());
            } catch (Exception exception) {
                unsupportedScopeHandled = true;
                TestLogger.logInfo("script", "Unsupported scope handled: %s", exception.getMessage());
            }

            TestLogger.logInfo("script", "Error handling test: syntaxError=%s, runtimeError=%s, nullScript=%s, unsupportedScope=%s",
                    syntaxErrorCaught, runtimeErrorCaught, nullScriptHandled, unsupportedScopeHandled);

            return syntaxErrorCaught && runtimeErrorCaught && nullScriptHandled && unsupportedScopeHandled;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Error handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests complex Groovy operations.
     */
    public static boolean testComplexOperations() {
        try {
            ScriptEngineInterface engine = new GroovyEngine();

            // Test list operations
            Object listResult = engine.execute("[1, 2, 3, 4, 5].collect { it * 2 }.sum()");
            boolean isThirty = listResult != null && (listResult.equals(30) || "30".equals(listResult.toString()));

            // Test map operations
            Object mapResult = engine.execute("def person = [name: 'John', age: 30]; \"${person.name} is ${person.age} years old\"");
            boolean isJohnThirty = mapResult != null && "John is 30 years old".equals(mapResult.toString());

            // Test closure with find
            Object findResult = engine.execute("def numbers = [1, 2, 3, 4, 5]; numbers.find { it > 3 }");
            boolean isFour = findResult != null && (findResult.equals(4) || "4".equals(findResult.toString()));

            // Test class definition and instantiation
            Object calcResult = engine.execute("""
                        class Calculator {
                            def add(a, b) { return a + b }
                            def multiply(a, b) { return a * b }
                        }
                        calc = new Calculator()
                        calc.add(5, 3) + calc.multiply(2, 4)
                    """);
            boolean isSixteen = calcResult != null && (calcResult.equals(16) || "16".equals(calcResult.toString()));

            // Test GString with expressions
            Object gstringResult = engine.execute("def x = 5; def y = 3; \"Result: ${x + y}\"");
            boolean isResultEight = gstringResult != null && "Result: 8".equals(gstringResult.toString());

            TestLogger.logInfo("script", "Complex operations test: list=%s, map=%s, closure=%s, class=%s, gstring=%s",
                    isThirty, isJohnThirty, isFour, isSixteen, isResultEight);

            return isThirty && isJohnThirty && isFour && isSixteen && isResultEight;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Complex operations test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests compilation cache functionality.
     */
    public static boolean testCompilationCache() {
        try {
            GroovyEngine engine = new GroovyEngine();

            String script = "def test() { return 'cached' }; test()";

            // First compilation
            Object compiled1 = engine.compile(script);
            boolean firstCompiled = compiled1 != null;

            // Second compilation of the same script should use cache
            Object compiled2 = engine.compile(script);
            boolean secondCompiled = compiled2 != null;

            // The compiled objects should be the same (from cache)
            boolean isCached = compiled1 == compiled2;

            // Both should execute correctly
            Object result1 = engine.executeCompiled(compiled1);
            Object result2 = engine.executeCompiled(compiled2);
            boolean bothExecute = "cached".equals(result1) && "cached".equals(result2);

            TestLogger.logInfo("script", "Compilation cache test: firstCompiled=%s, secondCompiled=%s, cached=%s, bothExecute=%s",
                    firstCompiled, secondCompiled, isCached, bothExecute);

            return firstCompiled && secondCompiled && isCached && bothExecute;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Compilation cache test failed: %s", exception.getMessage());
            return false;
        }
    }

}