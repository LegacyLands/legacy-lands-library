package net.legacy.library.annotation.test;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import net.legacy.library.foundation.annotation.ModuleTest;

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
        testName = "complex-test"
)
@ModuleTest(
        testName = "complex-test",
        description = "Complex test class for validating advanced annotation processing functionality",
        tags = {"annotation", "complex", "integration"},
        priority = 2,
        timeout = 4000,
        expectedResult = "SUCCESS"
)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class ComplexTestClass {

    private String complexData = "default-complex-data";

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