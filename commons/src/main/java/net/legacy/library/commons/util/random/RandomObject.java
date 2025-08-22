package net.legacy.library.commons.util.random;

import lombok.AllArgsConstructor;
import lombok.Getter;

/**
 * Represents a random object with its associated probability.
 *
 * @param <T> The type of the random object
 * @author 2000000
 * @since 2023/2/8
 */
@Getter
@AllArgsConstructor
public class RandomObject<T> {

    /**
     * The random object.
     */
    private final T object;

    /**
     * The probability weight of the random object.
     */
    private final int probability;

}
