package net.legacy.library.annotation.test;

/**
 * Simple test class for validating basic annotation processing functionality.
 *
 * <p>This class is annotated with {@link TestableAnnotation} to test that the annotation
 * processing framework correctly identifies and processes annotated classes during debug mode.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@TestableAnnotation(
        testName = "simple-test",
        expectedResult = "processed",
        validateOrder = true
)
public class SimpleTestClass {
    /**
     * A simple method for testing purposes.
     *
     * @return a test message
     */
    public String getTestMessage() {
        return "This is a simple test class";
    }
}