package net.legacy.library.foundation.test;

/**
 * Functional interface for executing module tests.
 *
 * <p>This interface provides a standardized way to execute test logic
 * across different modules while maintaining flexibility in implementation.
 * It supports both direct test runner execution and custom test logic.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-13 14:30
 */
@FunctionalInterface
public interface TestExecution {
    /**
     * Executes the test logic and returns a result summary.
     *
     * @return the test result summary
     * @throws Exception if test execution fails
     */
    TestResultSummary execute() throws Exception;
}