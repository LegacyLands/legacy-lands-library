package net.legacy.library.annotation.test;

/**
 * Error test class for validating exception handling in annotation processing.
 *
 * <p>This class is designed to trigger an error during processing to test
 * the error handling capabilities of the annotation processing framework.
 * The expected result is intentionally set to something different from what
 * the processor will produce to trigger an assertion error.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@TestableAnnotation(
        testName = "error-test",
        expectedResult = "should-fail",  // This will trigger an error
        validateOrder = false
)
public class ErrorTestClass {
    /**
     * A method that represents error-prone functionality.
     *
     * @return an error message
     */
    public String getErrorMessage() {
        return "This class is designed to trigger processing errors";
    }

    /**
     * Simulates a method that might throw an exception.
     *
     * @throws RuntimeException always throws an exception
     */
    public void simulateError() throws RuntimeException {
        throw new RuntimeException("Simulated error for testing");
    }
}