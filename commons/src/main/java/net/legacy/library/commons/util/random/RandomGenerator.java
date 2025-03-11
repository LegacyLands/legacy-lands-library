package net.legacy.library.commons.util.random;

import lombok.NoArgsConstructor;
import org.apache.commons.math3.random.MersenneTwister;

import java.security.SecureRandom;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A simple utility class to randomly return an object based on specified probabilities.
 *
 * <p><a href="https://github.com/QwQ-dev/AdvancedWish/blob/main/src/main/java/twomillions/plugin/advancedwish/utils/random/RandomGenerator.java"></a>
 * Original Source Code, from my previous project (AdvancedWish)
 *
 * @param <T> The type of the random object
 * @author 2000000
 * @since 2023/2/8
 */
@NoArgsConstructor
public class RandomGenerator<T> {
    /**
     * SecureRandom generator.
     */
    private final SecureRandom secureRandom = new SecureRandom();

    /**
     * ThreadLocalRandom generator.
     */
    private final ThreadLocalRandom threadLocalRandom = ThreadLocalRandom.current();
    
    /**
     * MersenneTwister generator.
     */
    private final MersenneTwister mersenneTwister = new MersenneTwister();

    /**
     * Stores all random objects with their corresponding probabilities.
     */
    private final ConcurrentLinkedQueue<RandomObject<T>> randomObjects = new ConcurrentLinkedQueue<>();

    /**
     * Total probability of all random objects.
     */
    private int totalProbability;

    /**
     * Creates a RandomGenerator instance with optional parameters.
     *
     * @param values Array of objects and their probability values, must not be empty and must have even length
     * @throws IllegalArgumentException if probability value is not an integer or if probability value is missing
     */
    @SafeVarargs
    public RandomGenerator(T... values) {
        for (int i = 0; i < values.length; i += 2) {
            if (i + 1 >= values.length) {
                throw new IllegalArgumentException("Missing probability value for object: " + values[i]);
            }

            T object = values[i];
            Object probabilityValue = values[i + 1];

            try {
                Integer.parseInt(probabilityValue.toString());
            } catch (Exception exception) {
                throw new IllegalArgumentException("Probability value for object " + values[i] + " is not an integer.");
            }

            addRandomObject(object, (int) Double.parseDouble(probabilityValue.toString()));
        }
    }
    
    /**
     * Creates a RandomGenerator instance with a map of objects and their probabilities.
     *
     * @param objectProbabilityMap Map of objects and their probabilities
     */
    public RandomGenerator(Map<T, Integer> objectProbabilityMap) {
        objectProbabilityMap.forEach(this::addRandomObject);
    }

    /**
     * Adds a random object with its corresponding probability to the utility class.
     *
     * @param object      The random object
     * @param probability The corresponding probability
     */
    public void addRandomObject(T object, int probability) {
        if (probability <= 0) {
            return;
        }

        RandomObject<T> randomObject = new RandomObject<>(object, probability);

        randomObjects.add(randomObject);
        totalProbability += probability;
    }
    
    /**
     * Adds multiple random objects with their corresponding probabilities to the utility class.
     *
     * @param objectProbabilityMap Map of objects and their probabilities
     */
    public void addRandomObjects(Map<T, Integer> objectProbabilityMap) {
        objectProbabilityMap.forEach(this::addRandomObject);
    }

    /**
     * Performs security check for randomization.
     *
     * @throws IllegalArgumentException if there are no objects to randomize or if total probability is 0
     */
    private void doSecurityCheck() {
        if (randomObjects.isEmpty()) {
            throw new IllegalArgumentException("No objects to randomize!");
        }

        if (totalProbability <= 0) {
            throw new IllegalArgumentException("Random probability of error, totalProbability: " + totalProbability);
        }
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses a standard Pseudo-Random Number Generator (PRNG) to generate random numbers.
     * The numbers generated are unpredictable but not truly random. This is the most commonly used method.
     *
     * <p>Summary: Uses a standard PRNG, efficient with good randomness. Suitable for general use cases.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResult() {
        // noinspection DuplicatedCode
        doSecurityCheck();

        int randomNumber = threadLocalRandom.nextInt(totalProbability);
        int cumulativeProbability = 0;

        for (RandomObject<T> randomObject : randomObjects) {
            cumulativeProbability += randomObject.getProbability();
            if (randomNumber < cumulativeProbability) {
                return Optional.ofNullable(randomObject.getObject());
            }
        }

        return Optional.empty();
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses a more secure random number generator, providing higher randomness and security.
     * It uses more random sources (e.g., system noise, hardware events, etc.) to generate random numbers,
     * resulting in more randomized results.
     *
     * <p>Summary: Uses a secure random number generator that can generate high-quality random numbers.
     * Suitable for scenarios requiring high security and randomness.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithSecureRandom() {
        // noinspection DuplicatedCode
        doSecurityCheck();

        int randomNumber = secureRandom.nextInt(totalProbability);
        int cumulativeProbability = 0;

        for (RandomObject<T> randomObject : randomObjects) {
            cumulativeProbability += randomObject.getProbability();
            if (randomNumber < cumulativeProbability) {
                return Optional.ofNullable(randomObject.getObject());
            }
        }

        return Optional.empty();
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses the Monte Carlo method to generate random results, which provides more uniform
     * and fair randomness. The probability of each object is proportional to the number of times it appears
     * in the queue. This method can handle a large number of objects and probabilities, but has lower
     * performance while producing fairer results.
     *
     * <p>Summary: Uses the Monte Carlo method, can handle many objects and probabilities,
     * lower performance, fairer results. Suitable for scenarios requiring high fairness.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithMonteCarlo() {
        // noinspection DuplicatedCode
        doSecurityCheck();

        List<T> objects = new ArrayList<>();

        for (RandomObject<T> randomObject : randomObjects) {
            for (int i = 0; i < randomObject.getProbability(); i++) {
                objects.add(randomObject.getObject());
            }
        }

        int index = threadLocalRandom.nextInt(objects.size());
        return Optional.ofNullable(objects.get(index));
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses a random sorting method to shuffle the list of objects,
     * then selects the first object as the random result. This method is less fair because
     * it only returns the first object in the list, giving other objects lower probability.
     * Also, this method is not as efficient as it requires shuffling the entire list.
     *
     * <p>Summary: Shuffles the objects, can generate relatively uniform distribution,
     * lower efficiency, high randomness, less fair. Suitable for scenarios requiring high randomness.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithShuffle() {
        // noinspection DuplicatedCode
        doSecurityCheck();

        List<T> objects = new ArrayList<>();

        for (RandomObject<T> randomObject : randomObjects) {
            for (int i = 0; i < randomObject.getProbability(); i++) {
                objects.add(randomObject.getObject());
            }
        }

        Collections.shuffle(objects);
        return Optional.ofNullable(objects.getFirst());
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses a Gaussian distribution random number generator,
     * generating random numbers with a Gaussian distribution.
     *
     * <p>Summary: Uses Gaussian distribution, which biases random results toward the mean value,
     * can avoid, to some extent, the problem of random numbers concentrating in the middle.
     * Suitable for scenarios requiring normally distributed random numbers.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithGaussian() {
        doSecurityCheck();

        double mean = totalProbability / 2.0;
        double standardDeviation = totalProbability / 6.0;

        int randomNumber = (int) Math.round(threadLocalRandom.nextGaussian() * standardDeviation + mean);

        randomNumber = Math.max(0, randomNumber);
        randomNumber = Math.min(totalProbability - 1, randomNumber);

        @SuppressWarnings("DuplicatedCode")
        int cumulativeProbability = 0;

        for (RandomObject<T> randomObject : randomObjects) {
            cumulativeProbability += randomObject.getProbability();
            if (randomNumber < cumulativeProbability) {
                return Optional.ofNullable(randomObject.getObject());
            }
        }

        return Optional.empty();
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses the Mersenne Twister pseudo-random number generator to generate random numbers.
     * This algorithm has good randomness and periodicity. Among all pseudo-random number generation
     * algorithms, Mersenne Twister has the longest period. Random numbers generated by this method
     * have high randomness.
     *
     * <p>Summary: Uses the Mersenne Twister random number generator, with good randomness and speed.
     * Suitable for scenarios requiring high-quality random numbers.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithMersenneTwister() {
        doSecurityCheck();

        int cumulativeProbability = 0;
        int randomNumber = mersenneTwister.nextInt(totalProbability);

        for (RandomObject<T> randomObject : randomObjects) {
            cumulativeProbability += randomObject.getProbability();
            if (randomNumber < cumulativeProbability) {
                return Optional.ofNullable(randomObject.getObject());
            }
        }

        return Optional.empty();
    }
    
    /**
     * Randomly returns an object based on the probability of all current random objects,
     * using the Mersenne Twister with a given seed.
     *
     * @param seed The seed to use for the Mersenne Twister
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithMersenneTwister(long seed) {
        mersenneTwister.setSeed(seed);
        return getResultWithMersenneTwister();
    }
    
    /**
     * Randomly returns an object based on the probability of all current random objects,
     * using the Mersenne Twister with a given seed.
     *
     * @param seed The seed to use for the Mersenne Twister
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithMersenneTwister(int seed) {
        mersenneTwister.setSeed(seed);
        return getResultWithMersenneTwister();
    }
    
    /**
     * Randomly returns an object based on the probability of all current random objects,
     * using the Mersenne Twister with a given seed array.
     *
     * @param seed The seed array to use for the Mersenne Twister
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithMersenneTwister(int[] seed) {
        mersenneTwister.setSeed(seed);
        return getResultWithMersenneTwister();
    }

    /**
     * Randomly returns an object based on the probability of all current random objects.
     *
     * <p>This method uses the XORShift pseudo-random number generator to generate random numbers.
     * This algorithm has good randomness and speed. Among all pseudo-random number generation
     * algorithms, XORShift is one of the fastest, but its randomness is not as good as other algorithms.
     *
     * <p>Summary: Uses the XORShift random number generator, which is relatively fast.
     * Suitable for scenarios requiring high efficiency and lower randomness requirements.
     *
     * @return An Optional containing the random object, or empty if there are no random objects
     */
    public Optional<T> getResultWithXORShift() {
        doSecurityCheck();

        int y = (int) System.nanoTime();

        y ^= (y << 6);
        y ^= (y >>> 21);
        y ^= (y << 7);

        int cumulativeProbability = 0;
        int randomNumber = Math.abs(y) % totalProbability;

        for (RandomObject<T> randomObject : randomObjects) {
            cumulativeProbability += randomObject.getProbability();
            if (randomNumber < cumulativeProbability) {
                return Optional.ofNullable(randomObject.getObject());
            }
        }

        return Optional.empty();
    }
}