package net.legacy.library.script.test;

import lombok.Getter;
import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;
import net.legacy.library.script.engine.ScriptEngineInterface;
import net.legacy.library.script.factory.ScriptEngineFactory;

/**
 * Test runner for validating script engine functionality.
 *
 * <p>This class orchestrates the execution of script engine tests, including
 * running JavaScript engine tests, scope management tests, and compilation tests.
 * It provides comprehensive testing coverage for the script execution framework.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-22
 */
@TestConfiguration(
        continueOnFailure = false,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.script.test"},
        globalTimeout = 30000,
        enableCaching = true,
        failFast = true
)
public class ScriptTestRunner extends AbstractModuleTestRunner {

    private static final String MODULE_NAME = "script";

    @Getter
    private final ScriptEngineInterface nashornEngine;

    @Getter
    private final ScriptEngineInterface rhinoEngine;

    @Getter
    private final ScriptEngineInterface v8Engine;

    private final TestTimer timer = new TestTimer();

    public ScriptTestRunner() {
        super(MODULE_NAME);
        this.nashornEngine = ScriptEngineFactory.createNashornScriptEngine();
        this.rhinoEngine = ScriptEngineFactory.createRhinoScriptEngine();
        this.v8Engine = ScriptEngineFactory.createV8Engine();
    }

    /**
     * Creates a test runner instance.
     *
     * @return the configured test runner
     */
    public static ScriptTestRunner create() {
        return new ScriptTestRunner();
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "script-engine-tests");

        // Initialize timer
        timer.startTimer("total-execution");
        timer.startTimer("setup");
    }

    @Override
    protected void executeTests() throws Exception {
        timer.stopTimer("setup");
        timer.startTimer("nashorn-tests");

        // Test Nashorn engine
        executeTestClass(NashornScriptEngineTest.class, "Nashorn Script Engine");

        timer.stopTimer("nashorn-tests");
        timer.startTimer("rhino-tests");

        // Test Rhino engine
        executeTestClass(RhinoScriptEngineTest.class, "Rhino Script Engine");

        timer.stopTimer("rhino-tests");
        timer.startTimer("v8-tests");

        // Test V8 engine
        executeTestClass(V8ScriptEngineTest.class, "V8 Script Engine");

        timer.stopTimer("v8-tests");
        timer.startTimer("factory-tests");

        // Test Script Engine Factory
        executeTestClass(ScriptEngineFactoryTest.class, "Script Engine Factory");

        timer.stopTimer("factory-tests");
        timer.startTimer("scope-tests");

        // Test Script Scopes
        executeTestClass(ScriptScopeTest.class, "Script Scope Management");

        timer.stopTimer("scope-tests");
    }

    @Override
    protected void afterTests() throws Exception {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "script-engine-tests",
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Execute all test methods in a test class using reflection.
     */
    private void executeTestClass(Class<?> testClass, String testDescription) {
        TestLogger.logInfo(MODULE_NAME, "Executing %s tests...", testDescription);

        try {
            java.lang.reflect.Method[] methods = testClass.getDeclaredMethods();
            for (java.lang.reflect.Method method : methods) {
                if (method.getName().startsWith("test") &&
                        method.getReturnType() == boolean.class &&
                        method.getParameterCount() == 0 &&
                        java.lang.reflect.Modifier.isStatic(method.getModifiers())) {

                    executeTestMethod(testClass, method.getName());
                }
            }
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Failed to execute test class %s: %s",
                    testClass.getSimpleName(), exception.getMessage());
        }
    }

    /**
     * Execute a single static test method.
     */
    private void executeTestMethod(Class<?> testClass, String methodName) {
        timer.startTimer(methodName);

        try {
            java.lang.reflect.Method method = testClass.getMethod(methodName);
            boolean result = (Boolean) method.invoke(null);

            if (result) {
                TestLogger.logValidation(MODULE_NAME, methodName, true, "Test completed successfully");
                context.incrementProcessed();
                context.incrementSuccess();
            } else {
                TestLogger.logValidation(MODULE_NAME, methodName, false, "Test failed - returned false");
                context.incrementProcessed();
                context.incrementFailure();
            }

        } catch (Exception exception) {
            TestLogger.logValidation(MODULE_NAME, methodName, false,
                    "Test failed with exception: %s", exception.getMessage());
            context.incrementProcessed();
            context.incrementFailure();

        } finally {
            timer.stopTimer(methodName);
        }
    }

    @Override
    protected TestResultSummary generateSuccessResult(long duration) {
        // Check if there were any test failures
        int failureCount = context.getFailureCount().get();
        int successCount = context.getSuccessCount().get();
        int totalCount = context.getProcessedCount().get();

        boolean actualSuccess = failureCount == 0;
        String message;

        if (actualSuccess) {
            message = "All script engine tests passed successfully";
        } else {
            message = String.format("Script engine tests completed with %d failures out of %d tests",
                    failureCount, totalCount);
        }

        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(actualSuccess)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("successCount", successCount)
                .withMetadata("failureCount", failureCount)
                .withMetadata("totalCount", totalCount);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = "Script engine tests failed: " + exception.getMessage();
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(false)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage());
    }

}