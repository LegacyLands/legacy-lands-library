package net.legacy.library.annotation.test;

/**
 * Complex test class for validating advanced annotation processing functionality.
 *
 * <p>This class demonstrates more complex scenarios for annotation processing,
 * including custom test names and expected results validation.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 21:30
 */
@TestableAnnotation(
        testName = "complex-test",
        expectedResult = "processed",
        validateOrder = false
)
public class ComplexTestClass {
    private String complexData;

    /**
     * Constructor for complex test class.
     *
     * @param complexData the complex data to initialize
     */
    public ComplexTestClass(String complexData) {
        this.complexData = complexData;
    }

    /**
     * Default constructor.
     */
    public ComplexTestClass() {
        this("default-complex-data");
    }

    /**
     * Gets the complex data.
     *
     * @return the complex data
     */
    public String getComplexData() {
        return complexData;
    }

    /**
     * Sets the complex data.
     *
     * @param complexData the complex data to set
     */
    public void setComplexData(String complexData) {
        this.complexData = complexData;
    }

    /**
     * Performs a complex operation for testing.
     *
     * @param input the input parameter
     * @return the processed result
     */
    public String performComplexOperation(String input) {
        return "Complex processing of: " + input + " with data: " + complexData;
    }
}