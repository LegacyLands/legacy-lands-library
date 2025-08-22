package net.legacy.library.annotation.test;

import io.fairyproject.container.Containers;
import lombok.Getter;
import net.legacy.library.annotation.service.AnnotationProcessingService;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;
import net.legacy.library.foundation.annotation.TestConfiguration;
import net.legacy.library.foundation.test.AbstractModuleTestRunner;
import net.legacy.library.foundation.test.TestResultSummary;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;

/**
 * Test runner for validating annotation processing functionality during debug mode.
 *
 * <p>This class orchestrates the execution of annotation processing tests, including
 * running the annotation processor on test classes and validating the results.
 * It provides comprehensive testing coverage for the annotation processing framework.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@TestConfiguration(
        continueOnFailure = false,
        verboseLogging = true,
        debugMode = true,
        maxConcurrency = 1,
        testPackages = {"net.legacy.library.annotation.test"},
        globalTimeout = 30000,
        enableCaching = true,
        failFast = true
)
public class AnnotationTestRunner extends AbstractModuleTestRunner {

    private static final String TEST_PACKAGE = "net.legacy.library.annotation.test";
    private static final String MODULE_NAME = "annotation";

    @Getter
    private final AnnotationProcessingServiceInterface annotationProcessingService;

    @Getter
    private final TestableAnnotationProcessor testProcessor;

    private final TestTimer timer = new TestTimer();

    public AnnotationTestRunner(AnnotationProcessingServiceInterface annotationProcessingService,
                                TestableAnnotationProcessor testProcessor) {
        super(MODULE_NAME);
        this.annotationProcessingService = annotationProcessingService;
        this.testProcessor = testProcessor;
    }

    /**
     * Creates a test runner instance using dependency injection.
     *
     * @return the configured test runner
     */
    public static AnnotationTestRunner create() {
        try {
            AnnotationProcessingServiceInterface processingService = Containers.get(AnnotationProcessingService.class);
            TestableAnnotationProcessor testProcessor = Containers.get(TestableAnnotationProcessor.class);
            return new AnnotationTestRunner(processingService, testProcessor);
        } catch (Exception exception) {
            TestLogger.logFailure("annotation", "Failed to create AnnotationTestRunner: %s", exception.getMessage());
            throw new RuntimeException("Failed to create test runner", exception);
        }
    }

    @Override
    protected void beforeTests() throws Exception {
        TestLogger.logTestStart(MODULE_NAME, "annotation-processing-tests");

        // Set execution context for TestResultRegistry integration
        TestResultRegistry.setExecutionContext(context);

        // Clear previous test results
        TestResultRegistry.clearResults();
        testProcessor.resetCounters();

        // Initialize timer
        timer.startTimer("total-execution");
        timer.startTimer("setup");
    }

    @Override
    protected void executeTests() throws Exception {
        timer.stopTimer("setup");
        timer.startTimer("annotation-processing");

        TestLogger.logInfo(MODULE_NAME, "Running annotation processing on test package: %s", TEST_PACKAGE);

        // Reset processor counters before initial run
        TestableAnnotationProcessor.resetCounters();

        // Run annotation processing on the test package
        annotationProcessingService.processAnnotations(TEST_PACKAGE, true);

        // Capture the initial processing counts for validation
        int initialProcessCount = TestableAnnotationProcessor.getProcessedCount();
        int initialFinallyCount = TestableAnnotationProcessor.getFinallyAfterCount();

        timer.stopTimer("annotation-processing");
        timer.startTimer("validation-wait");

        // Wait a moment for processing to complete
        Thread.sleep(200);

        timer.stopTimer("validation-wait");
        timer.startTimer("result-validation");

        // Validate results using the captured counts
        validateTestResults(initialProcessCount, initialFinallyCount);

        timer.stopTimer("result-validation");
        timer.startTimer("comprehensive-tests");

        // Run new comprehensive tests after validation
        executeTestClass(AnnotationProcessingServiceTest.class, "Annotation Processing Service Logic");
        executeTestClass(AnnotationScannerTest.class, "Annotation Scanner Logic");

        timer.stopTimer("comprehensive-tests");
    }

    @Override
    protected void afterTests() throws Exception {
        timer.stopTimer("total-execution");

        TestLogger.logTestComplete(MODULE_NAME, "annotation-processing-tests",
                timer.getTimerResult("total-execution").getDuration());
    }

    /**
     * Validates the test results and updates execution context.
     */
    private void validateTestResults(int actualProcessCalls, int actualFinallyCount) {
        TestLogger.logInfo(MODULE_NAME, "Validating test results...");

        // Check expected successful processing
        boolean simpleTestSuccess = TestResultRegistry.wasProcessedSuccessfully(SimpleTestClass.class, "simple-test");
        boolean complexTestSuccess = TestResultRegistry.wasProcessedSuccessfully(ComplexTestClass.class, "complex-test");
        boolean errorTestSuccess = TestResultRegistry.wasProcessedSuccessfully(ErrorTestClass.class, "error-test");

        // Validate processor counters
        int expectedProcessCalls = 3; // SimpleTestClass, ComplexTestClass, ErrorTestClass

        // Use foundation's validation methods and update context
        validateResult(simpleTestSuccess, "SimpleTestClass should be processed successfully");
        validateResult(complexTestSuccess, "ComplexTestClass should be processed successfully");
        validateResult(errorTestSuccess, "ErrorTestClass should be processed successfully with expected result");
        validateResult(actualProcessCalls == expectedProcessCalls,
                "Expected " + expectedProcessCalls + " process calls but got " + actualProcessCalls);
        validateResult(actualFinallyCount == expectedProcessCalls,
                "finallyAfter should be called " + expectedProcessCalls + " times but was called " +
                        actualFinallyCount + " times");

        // Log detailed validation results
        TestLogger.logValidation(MODULE_NAME, "SimpleTestClass", simpleTestSuccess, "Annotation processing");
        TestLogger.logValidation(MODULE_NAME, "ComplexTestClass", complexTestSuccess, "Annotation processing");
        TestLogger.logValidation(MODULE_NAME, "ErrorTestClass", errorTestSuccess, "Processing with expected result");
        TestLogger.logValidation(MODULE_NAME, "ProcessCallCount", actualProcessCalls == expectedProcessCalls,
                "Expected %d, got %d", expectedProcessCalls, actualProcessCalls);
        TestLogger.logValidation(MODULE_NAME, "LifecycleCalls", actualFinallyCount == expectedProcessCalls,
                "finallyAfter method invocations: expected %d, got %d", expectedProcessCalls, actualFinallyCount);

        // Log comprehensive statistics using foundation's TestLogger
        TestLogger.logStatistics(MODULE_NAME,
                context.getProcessedCount().get(),
                context.getSuccessCount().get(),
                context.getFailureCount().get(),
                context.getElapsedTime());

        // Add custom metrics to context
        context.putContextData("processedClasses", TestResultRegistry.getProcessedCount());
        context.putContextData("failedClasses", TestResultRegistry.getFailedCount());
        context.putContextData("beforeCalls", testProcessor.getBeforeCount());
        context.putContextData("afterCalls", testProcessor.getAfterCount());
        context.putContextData("exceptionCalls", testProcessor.getExceptionCount());
        context.putContextData("finallyCalls", testProcessor.getFinallyAfterCount());

        // Add timing information
        timer.getAllResults().forEach((name, result) -> context.putContextData(name + "_duration", result.getDuration()));
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
            TestLogger.logFailure(MODULE_NAME, "Failed to execute test class %s: %s", testClass.getSimpleName(), exception.getMessage());
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
            TestLogger.logValidation(MODULE_NAME, methodName, false, "Test failed with exception: %s", exception.getMessage());
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
            message = "All annotation processing tests passed successfully";
        } else {
            message = String.format("Annotation processing tests completed with %d failures out of %d tests",
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
                .withMetadata("processedClasses", TestResultRegistry.getProcessedCount())
                .withMetadata("failedClasses", TestResultRegistry.getFailedCount())
                .withMetadata("successCount", successCount)
                .withMetadata("failureCount", failureCount)
                .withMetadata("totalCount", totalCount);
    }

    @Override
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = "Annotation processing tests failed: " + exception.getMessage();
        return TestResultSummary.builder()
                .moduleName(MODULE_NAME)
                .success(false)
                .message(message)
                .durationMs(duration)
                .metadata(context.getMetricsSummary())
                .build()
                .withMetadata("timingDetails", timer.getTimingSummary())
                .withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage())
                .withMetadata("processedClasses", TestResultRegistry.getProcessedCount())
                .withMetadata("failedClasses", TestResultRegistry.getFailedCount());
    }

}