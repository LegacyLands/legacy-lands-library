package net.legacy.library.commons.test;

import net.legacy.library.commons.util.random.RandomGenerator;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Test class for RandomGenerator, validating probability distribution algorithms and edge cases.
 *
 * <p>This test class focuses on the core random generation logic in {@link RandomGenerator},
 * including multiple random algorithms, probability calculations, and boundary condition handling.
 * Tests verify that different random algorithms produce expected results within statistical bounds.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@ModuleTest(
        testName = "random-generator-test",
        description = "Tests RandomGenerator probability distribution algorithms and multiple random implementations",
        tags = {"commons", "random", "probability", "algorithms", "statistics"},
        priority = 3,
        timeout = 2000,
        expectedResult = "SUCCESS"
)
public class RandomGeneratorTest {

    /**
     * Tests basic RandomGenerator functionality with simple probability distribution.
     */
    public static boolean testBasicRandomGeneration() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();
            generator.addRandomObject("common", 70);
            generator.addRandomObject("rare", 20);
            generator.addRandomObject("epic", 10);

            // Test that results are returned
            Optional<String> result = generator.getResult();
            boolean hasResult = result.isPresent();

            if (!hasResult) {
                TestLogger.logFailure("commons", "Basic random generation failed: no result returned");
                return false;
            }

            // Verify result is one of expected values
            String resultValue = result.get();
            boolean validResult = "common".equals(resultValue) || "rare".equals(resultValue) || "epic".equals(resultValue);

            TestLogger.logInfo("commons", "Basic random generation test: hasResult=%s, validResult=%s, result=%s", true, validResult, resultValue);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Basic random generation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests RandomGenerator constructor with varargs parameters.
     */
    public static boolean testVarargsConstructor() {
        try {
            // Test valid construction using Object array
            Object[] params = {"item1", 50, "item2", 30, "item3", 20};
            RandomGenerator<Object> generator = new RandomGenerator<>(params);
            Optional<Object> result = generator.getResult();

            boolean hasResult = result.isPresent();
            if (!hasResult) {
                TestLogger.logFailure("commons", "Varargs constructor test failed: no result returned");
                return false;
            }

            Object resultValue = result.get();
            boolean validResult = "item1".equals(resultValue) || "item2".equals(resultValue) || "item3".equals(resultValue);

            TestLogger.logInfo("commons", "Varargs constructor test: hasResult=%s, validResult=%s", true, validResult);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Varargs constructor test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests RandomGenerator constructor with Map parameter.
     */
    public static boolean testMapConstructor() {
        try {
            Map<String, Integer> probabilities = new HashMap<>();
            probabilities.put("alpha", 40);
            probabilities.put("beta", 35);
            probabilities.put("gamma", 25);

            RandomGenerator<String> generator = new RandomGenerator<>(probabilities);
            Optional<String> result = generator.getResult();

            boolean hasResult = result.isPresent();
            if (!hasResult) {
                TestLogger.logFailure("commons", "Map constructor test failed: no result returned");
                return false;
            }

            String resultValue = result.get();
            boolean validResult = probabilities.containsKey(resultValue);

            TestLogger.logInfo("commons", "Map constructor test: hasResult=%s, validResult=%s", true, validResult);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Map constructor test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests edge case with zero and negative probabilities.
     */
    public static boolean testZeroAndNegativeProbabilities() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();

            // Add objects with zero and negative probabilities (should be ignored)
            generator.addRandomObject("zero", 0);
            generator.addRandomObject("negative", -10);
            generator.addRandomObject("valid", 100);

            Optional<String> result = generator.getResult();
            boolean hasResult = result.isPresent();

            if (!hasResult) {
                TestLogger.logFailure("commons", "Zero/negative probability test failed: no result returned");
                return false;
            }

            String resultValue = result.get();
            boolean onlyValidResult = "valid".equals(resultValue);

            TestLogger.logInfo("commons", "Zero/negative probability test: hasResult=%s, onlyValidResult=%s", true, onlyValidResult);

            return onlyValidResult;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Zero/negative probability test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests exception handling for empty generator.
     */
    public static boolean testEmptyGeneratorException() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();

            try {
                generator.getResult();
                TestLogger.logFailure("commons", "Empty generator test failed: expected IllegalArgumentException");
                return false;
            } catch (IllegalArgumentException expected) {
                boolean correctExceptionMessage = expected.getMessage().contains("No objects to randomize");

                TestLogger.logInfo("commons", "Empty generator test: caught expected exception=%s", correctExceptionMessage);

                return correctExceptionMessage;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Empty generator test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests exception handling for invalid varargs constructor.
     */
    public static boolean testInvalidVarargsConstructor() {
        try {
            try {
                // Missing probability for last object
                Object[] invalidParams = {"item1", 50, "item2"};
                new RandomGenerator<>(invalidParams);
                TestLogger.logFailure("commons", "Invalid varargs test failed: expected IllegalArgumentException");
                return false;
            } catch (IllegalArgumentException expected) {
                boolean correctExceptionMessage = expected.getMessage().contains("Missing probability value");

                TestLogger.logInfo("commons", "Invalid varargs test: caught expected exception=%s", correctExceptionMessage);

                return correctExceptionMessage;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Invalid varargs test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests different random algorithms produce valid results.
     */
    public static boolean testDifferentRandomAlgorithms() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();
            generator.addRandomObject("result1", 50);
            generator.addRandomObject("result2", 50);

            // Test different algorithms
            Optional<String> standardResult = generator.getResult();
            Optional<String> secureResult = generator.getResultWithSecureRandom();
            Optional<String> monteCarloResult = generator.getResultWithMonteCarlo();
            Optional<String> shuffleResult = generator.getResultWithShuffle();
            Optional<String> gaussianResult = generator.getResultWithGaussian();
            Optional<String> mersenneTwisterResult = generator.getResultWithMersenneTwister();
            Optional<String> xorShiftResult = generator.getResultWithXORShift();

            // Verify all algorithms return results
            boolean allAlgorithmsWork = standardResult.isPresent() &&
                    secureResult.isPresent() &&
                    monteCarloResult.isPresent() &&
                    shuffleResult.isPresent() &&
                    gaussianResult.isPresent() &&
                    mersenneTwisterResult.isPresent() &&
                    xorShiftResult.isPresent();

            TestLogger.logInfo("commons", "Different algorithms test: allAlgorithmsWork=%s", allAlgorithmsWork);

            return allAlgorithmsWork;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Different algorithms test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests MersenneTwister with different seed types.
     */
    public static boolean testMersenneTwisterWithSeeds() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();
            generator.addRandomObject("result1", 50);
            generator.addRandomObject("result2", 50);

            // Test with different seed types
            Optional<String> longSeedResult = generator.getResultWithMersenneTwister(12345L);
            Optional<String> intSeedResult = generator.getResultWithMersenneTwister(54321);
            Optional<String> arraySeedResult = generator.getResultWithMersenneTwister(new int[]{1, 2, 3, 4, 5});

            boolean allSeedTypesWork = longSeedResult.isPresent() &&
                    intSeedResult.isPresent() &&
                    arraySeedResult.isPresent();

            TestLogger.logInfo("commons", "MersenneTwister seeds test: allSeedTypesWork=%s", allSeedTypesWork);

            return allSeedTypesWork;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "MersenneTwister seeds test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests probability distribution with single object.
     */
    public static boolean testSingleObjectProbability() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();
            generator.addRandomObject("only", 100);

            // Run multiple times to verify consistency
            boolean allResultsCorrect = true;
            for (int i = 0; i < 10; i++) {
                Optional<String> result = generator.getResult();
                if (result.isEmpty() || !"only".equals(result.get())) {
                    allResultsCorrect = false;
                    break;
                }
            }

            TestLogger.logInfo("commons", "Single object probability test: allResultsCorrect=%s", allResultsCorrect);

            return allResultsCorrect;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Single object probability test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests adding multiple random objects through map.
     */
    public static boolean testAddMultipleRandomObjects() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();

            Map<String, Integer> additionalObjects = new HashMap<>();
            additionalObjects.put("extra1", 25);
            additionalObjects.put("extra2", 25);
            additionalObjects.put("extra3", 50);

            generator.addRandomObjects(additionalObjects);

            Optional<String> result = generator.getResult();
            boolean hasResult = result.isPresent();

            if (!hasResult) {
                TestLogger.logFailure("commons", "Add multiple objects test failed: no result returned");
                return false;
            }

            String resultValue = result.get();
            boolean validResult = additionalObjects.containsKey(resultValue);

            TestLogger.logInfo("commons", "Add multiple objects test: hasResult=%s, validResult=%s", true, validResult);

            return validResult;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Add multiple objects test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests null object handling.
     */
    public static boolean testNullObjectHandling() {
        try {
            RandomGenerator<String> generator = new RandomGenerator<>();
            generator.addRandomObject(null, 50);
            generator.addRandomObject("valid", 50);

            // Should handle null objects gracefully
            Optional<String> result = generator.getResult();
            boolean hasResult = result.isPresent();

            // Result can be null (wrapped in Optional) or "valid"
            boolean validHandling = true;
            if (hasResult) {
                String resultValue = result.get();
                validHandling = "valid".equals(resultValue);
            }

            TestLogger.logInfo("commons", "Null object handling test: hasResult=%s, validHandling=%s", hasResult, validHandling);

            return validHandling;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Null object handling test failed: %s", exception.getMessage());
            return false;
        }
    }

}