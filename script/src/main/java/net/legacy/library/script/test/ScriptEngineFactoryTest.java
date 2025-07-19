package net.legacy.library.script.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.script.engine.NashornScriptEngine;
import net.legacy.library.script.engine.RhinoScriptEngine;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.engine.V8ScriptEngine;
import net.legacy.library.script.factory.ScriptEngineFactory;

/**
 * Test class for ScriptEngineFactory functionality.
 *
 * <p>This test class validates the factory pattern implementation for creating
 * different JavaScript engine instances.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-22
 */
@ModuleTest(
        testName = "script-engine-factory-test",
        description = "Tests ScriptEngineFactory creation methods",
        tags = {"script", "factory", "engine", "creation"},
        priority = 1,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class ScriptEngineFactoryTest {
    /**
     * Tests creation of Nashorn script engine.
     */
    public static boolean testCreateNashornEngine() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createNashornScriptEngine();

            boolean isNotNull = engine != null;
            boolean isCorrectType = engine instanceof NashornScriptEngine;
            boolean implementsInterface = engine instanceof ScriptEngineInterface;

            TestLogger.logInfo("script", "Nashorn engine creation test: notNull=%s, correctType=%s, implementsInterface=%s",
                    isNotNull, isCorrectType, implementsInterface);

            return isNotNull && isCorrectType && implementsInterface;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Nashorn engine creation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests creation of Rhino script engine.
     */
    public static boolean testCreateRhinoEngine() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

            boolean isNotNull = engine != null;
            boolean isCorrectType = engine instanceof RhinoScriptEngine;
            boolean implementsInterface = engine instanceof ScriptEngineInterface;

            TestLogger.logInfo("script", "Rhino engine creation test: notNull=%s, correctType=%s, implementsInterface=%s",
                    isNotNull, isCorrectType, implementsInterface);

            return isNotNull && isCorrectType && implementsInterface;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Rhino engine creation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests creation of V8 script engine.
     */
    public static boolean testCreateV8Engine() {
        try {
            ScriptEngineInterface engine = ScriptEngineFactory.createV8Engine();

            boolean isNotNull = engine != null;
            boolean isCorrectType = engine instanceof V8ScriptEngine;
            boolean implementsInterface = engine instanceof ScriptEngineInterface;

            TestLogger.logInfo("script", "V8 engine creation test: notNull=%s, correctType=%s, implementsInterface=%s",
                    isNotNull, isCorrectType, implementsInterface);

            return isNotNull && isCorrectType && implementsInterface;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "V8 engine creation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests multiple engine creation for isolation.
     */
    public static boolean testMultipleEngineCreation() {
        try {
            // Create multiple instances of each engine type
            ScriptEngineInterface nashorn1 = ScriptEngineFactory.createNashornScriptEngine();
            ScriptEngineInterface nashorn2 = ScriptEngineFactory.createNashornScriptEngine();

            ScriptEngineInterface rhino1 = ScriptEngineFactory.createRhinoScriptEngine();
            ScriptEngineInterface rhino2 = ScriptEngineFactory.createRhinoScriptEngine();

            ScriptEngineInterface v8_1 = ScriptEngineFactory.createV8Engine();
            ScriptEngineInterface v8_2 = ScriptEngineFactory.createV8Engine();

            // Verify all are different instances
            boolean nashornDifferent = !nashorn1.equals(nashorn2);
            boolean rhinoDifferent = !rhino1.equals(rhino2);
            boolean v8Different = !v8_1.equals(v8_2);

            TestLogger.logInfo("script", "Multiple engine creation test: nashornDifferent=%s, rhinoDifferent=%s, v8Different=%s",
                    nashornDifferent, rhinoDifferent, v8Different);

            return nashornDifferent && rhinoDifferent && v8Different;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Multiple engine creation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests factory performance with multiple creation calls.
     */
    public static boolean testFactoryPerformance() {
        try {
            long startTime = System.currentTimeMillis();

            // Create multiple engines
            for (int i = 0; i < 100; i++) {
                ScriptEngineFactory.createNashornScriptEngine();
                ScriptEngineFactory.createRhinoScriptEngine();
                ScriptEngineFactory.createV8Engine();
            }

            long duration = System.currentTimeMillis() - startTime;

            TestLogger.logInfo("script", "Factory performance test: 300 engines created in %dms", duration);

            // Should complete within reasonable time (3 seconds)
            return duration < 3000;
        } catch (Exception exception) {
            TestLogger.logFailure("script", "Factory performance test failed: %s", exception.getMessage());
            return false;
        }
    }
}