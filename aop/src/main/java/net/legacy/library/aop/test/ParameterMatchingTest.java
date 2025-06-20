package net.legacy.library.aop.test;

import net.legacy.library.aop.pointcut.Pointcut;
import net.legacy.library.aop.pointcut.PointcutExpressionParser;
import net.legacy.library.foundation.util.TestLogger;

import java.lang.reflect.Method;
import java.util.List;
import java.util.Map;

/**
 * Tests parameter matching including generic types.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-21
 */
public class ParameterMatchingTest {
    private static final String MODULE_NAME = "aop";
    
    // Test methods with various parameter types
    public void noParams() {}
    public void singleParam(String s) {}
    public void multipleParams(String s, int i) {}
    public void genericParam(List<String> list) {}
    public void wildcardParam(List<?> list) {}
    public void multipleGenerics(Map<String, List<Integer>> map) {}
    public void mixedParams(String s, List<String> list, int i) {}
    public void varargs(String... args) {}
    public void arrayParam(String[] args) {}
    
    public void runTest() {
        TestLogger.logInfo(MODULE_NAME, "=== Parameter Matching Test ===");
        
        int passedCount = 0;
        int failureCount = 0;
        
        try {
            // Test no parameters
            if (testParameterMatching("execution(* *())", "noParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(..))", "noParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(String))", "noParams", false)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test single parameter
            if (testParameterMatching("execution(* *(String))", "singleParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(*))", "singleParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(..))", "singleParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test multiple parameters
            if (testParameterMatching("execution(* *(String, int))", "multipleParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(String, *))", "multipleParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(*, int))", "multipleParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test generic parameters
            // Note: Due to type erasure, we need to match the actual runtime representation
            if (testParameterMatching("execution(* *(List<*>))", "genericParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(List<String>))", "genericParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(List<?>))", "wildcardParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test complex generics
            if (testParameterMatching("execution(* *(Map<*, *>))", "multipleGenerics", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(Map<String, List<*>>))", "multipleGenerics", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test mixed parameters
            if (testParameterMatching("execution(* *(String, List, int))", "mixedParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            if (testParameterMatching("execution(* *(String, ..))", "mixedParams", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            // Test arrays
            if (testParameterMatching("execution(* *(String[]))", "arrayParam", true)) {
                passedCount++;
            } else {
                failureCount++;
            }
            
            TestLogger.logInfo(MODULE_NAME, "Parameter matching tests completed: %d passed, %d failed", 
                passedCount, failureCount);
            
            if (failureCount > 0) {
                throw new AssertionError(String.format("Parameter matching tests failed: %d failures", failureCount));
            }
            
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Unexpected error during parameter matching test: %s", exception.getMessage());
            throw new RuntimeException(exception);
        }
    }
    
    private boolean testParameterMatching(String expression, String methodName, boolean expectedMatch) {
        try {
            Method method = findMethod(methodName);
            if (method == null) {
                TestLogger.logFailure(MODULE_NAME, "Method not found: %s", methodName);
                return false;
            }
            
            PointcutExpressionParser parser = new PointcutExpressionParser();
            Pointcut pointcut = parser.parse(expression);
            
            boolean matches = pointcut.matches(method, this.getClass());
            
            // Debug: log parameter types
            Class<?>[] paramTypes = method.getParameterTypes();
            if (paramTypes.length > 0) {
                StringBuilder paramStr = new StringBuilder();
                for (int i = 0; i < paramTypes.length; i++) {
                    if (i > 0) paramStr.append(", ");
                    paramStr.append(paramTypes[i].getSimpleName());
                }
                TestLogger.logInfo(MODULE_NAME, "Method %s has parameters: %s", methodName, paramStr.toString());
            }
            
            if (matches == expectedMatch) {
                TestLogger.logSuccess(MODULE_NAME, "✓ %s matches %s: %s (expected)", expression, methodName, matches);
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "✗ %s matches %s: %s (expected %s)", expression, methodName, matches, expectedMatch);
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Error testing %s on %s: %s", expression, methodName, exception.getMessage());
            return false;
        }
    }
    
    private Method findMethod(String name) {
        for (Method method : this.getClass().getDeclaredMethods()) {
            if (method.getName().equals(name) && !method.isSynthetic()) {
                return method;
            }
        }
        return null;
    }
}