package net.legacy.library.commons.util;

import lombok.experimental.UtilityClass;

/**
 * Utility class for putting an English noun in agreement with a count.
 *
 * <p>Exactly one takes the singular and every other number, zero and negatives included, takes the plural:
 * {@code 1 item}, {@code 0 items}, {@code 12 items}. A text such as {@code "item(s)"} is wrong at every number, and
 * {@code count + " items"} is wrong at one, the number a fresh server sees first.
 *
 * <p>Both nouns are passed in rather than derived, because pairs such as {@code entry/entries} defeat any simple rule.
 * All methods are thread-safe.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class PluralUtil {

    /**
     * Formats a count followed by its noun.
     *
     * @param count    how many there are
     * @param singular the noun for exactly one
     * @param plural   the noun for any other number
     * @return the count, a space and the noun, such as {@code "3 entries"}
     */
    public static String count(long count, String singular, String plural) {
        return count + " " + noun(count, singular, plural);
    }

    /**
     * Picks the noun for a count, for a text that prints the number somewhere else.
     *
     * @param count    how many there are
     * @param singular the noun for exactly one
     * @param plural   the noun for any other number
     * @return {@code singular} when {@code count} is one, otherwise {@code plural}
     */
    public static String noun(long count, String singular, String plural) {
        return count == 1 ? singular : plural;
    }

}
