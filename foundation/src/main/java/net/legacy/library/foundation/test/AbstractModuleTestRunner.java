package net.legacy.library.foundation.test;

import lombok.Getter;

/**
 * Abstract base class for module-level test runners.
 *
 * <p>This class provides a standardized framework for implementing module-specific
 * test runners. It handles common test lifecycle management, timing, exception
 * handling, and result generation.
 *
 * <p>Subclasses should implement the {@link #executeTests()} method to define
 * module-specific test logic while leveraging the common infrastructure provided
 * by this base class.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
public abstract class AbstractModuleTestRunner {
    /**
     * The execution context for this test run.
     */
    @Getter
    protected final TestExecutionContext context;

    /**
     * The module name being tested.
     */
    protected final String moduleName;

    /**
     * Creates a new module test runner.
     *
     * @param moduleName the name of the module being tested
     */
    protected AbstractModuleTestRunner(String moduleName) {
        this.moduleName = moduleName;
        this.context = new TestExecutionContext(moduleName);
    }

    /**
     * Creates a new module test runner with a custom execution context.
     *
     * @param context the test execution context
     */
    protected AbstractModuleTestRunner(TestExecutionContext context) {
        this.context = context;
        this.moduleName = context.getModuleName();
    }

    /**
     * Runs all tests for the module and returns a result summary.
     *
     * <p>This method orchestrates the complete test lifecycle:
     * <ol>
     *   <li>Calls {@link #beforeTests()} for setup</li>
     *   <li>Executes {@link #executeTests()} for main test logic</li>
     *   <li>Calls {@link #afterTests()} for cleanup</li>
     *   <li>Generates and returns a {@link TestResultSummary}</li>
     * </ol>
     *
     * @return the test result summary
     */
    public final TestResultSummary runTests() {
        long startTime = System.currentTimeMillis();

        try {
            beforeTests();
            executeTests();
            afterTests();

            long duration = System.currentTimeMillis() - startTime;
            return generateSuccessResult(duration);

        } catch (Exception exception) {
            long duration = System.currentTimeMillis() - startTime;
            context.incrementException();
            return generateFailureResult(duration, exception);
        } finally {
            finalizeTests();
        }
    }

    /**
     * Executes the module-specific tests.
     *
     * <p>This is the main method that subclasses must implement to define
     * their test logic. It will be called within the test lifecycle managed
     * by {@link #runTests()}.
     *
     * @throws Exception if any test fails or an error occurs
     */
    protected abstract void executeTests() throws Exception;

    /**
     * Performs setup operations before test execution.
     *
     * <p>Subclasses can override this method to implement module-specific
     * setup logic such as initializing test data, configuring services,
     * or preparing the test environment.
     *
     * @throws Exception if setup fails
     */
    protected void beforeTests() throws Exception {
        // Default implementation does nothing
        // Subclasses can override for custom setup
    }

    /**
     * Performs cleanup operations after test execution.
     *
     * <p>Subclasses can override this method to implement module-specific
     * cleanup logic such as releasing resources, resetting state, or
     * cleaning up test data.
     *
     * @throws Exception if cleanup fails
     */
    protected void afterTests() throws Exception {
        // Default implementation does nothing
        // Subclasses can override for custom cleanup
    }

    /**
     * Performs final cleanup operations that always execute.
     *
     * <p>This method is called in the finally block of {@link #runTests()}
     * and is guaranteed to execute regardless of test success or failure.
     * Subclasses can override this for critical cleanup operations.
     */
    protected void finalizeTests() {
        // Default implementation does nothing
        // Subclasses can override for critical cleanup
    }

    /**
     * Generates a success result summary.
     *
     * <p>Subclasses can override this method to customize the success
     * message or include additional metadata in the result summary.
     *
     * @param duration the test execution duration in milliseconds
     * @return the success result summary
     */
    protected TestResultSummary generateSuccessResult(long duration) {
        String message = new StringBuilder()
                .append("All ")
                .append(moduleName)
                .append(" module tests passed successfully")
                .toString();
        return TestResultSummary.withMetadata(
                moduleName,
                true,
                message,
                duration,
                context.getMetricsSummary()
        );
    }

    /**
     * Generates a failure result summary.
     *
     * <p>Subclasses can override this method to customize the failure
     * message or include additional error information in the result summary.
     *
     * @param duration  the test execution duration in milliseconds
     * @param exception the exception that caused the failure
     * @return the failure result summary
     */
    protected TestResultSummary generateFailureResult(long duration, Exception exception) {
        String message = new StringBuilder()
                .append(moduleName)
                .append(" module tests failed: ")
                .append(exception.getMessage())
                .toString();

        TestResultSummary result = TestResultSummary.withMetadata(
                moduleName,
                false,
                message,
                duration,
                context.getMetricsSummary()
        );

        return result.withMetadata("exception", exception.getClass().getSimpleName())
                .withMetadata("exceptionMessage", exception.getMessage());
    }

    /**
     * Validates test results and updates context metrics.
     *
     * <p>This helper method can be used by subclasses to validate test
     * outcomes and update the execution context with appropriate metrics.
     *
     * @param condition the condition to validate
     * @param message   the validation message
     * @throws AssertionError if the condition is false
     */
    protected void validateResult(boolean condition, String message) throws AssertionError {
        context.incrementProcessed();

        if (condition) {
            context.incrementSuccess();
        } else {
            context.incrementFailure();
            throw new AssertionError(message);
        }
    }

    /**
     * Records the execution time for a specific operation.
     *
     * <p>This helper method allows subclasses to track timing metrics
     * for individual operations within their tests.
     *
     * @param operationName the name of the operation
     * @param startTime     the operation start time in milliseconds
     */
    protected void recordOperationTime(String operationName, long startTime) {
        long duration = System.currentTimeMillis() - startTime;
        context.recordProcessingTime(duration);
        context.putContextData(operationName + "_duration", duration);
    }
}