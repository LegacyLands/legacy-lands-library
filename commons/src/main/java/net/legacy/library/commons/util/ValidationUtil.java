package net.legacy.library.commons.util;

import lombok.experimental.UtilityClass;

import java.util.Collection;
import java.util.Map;
import java.util.Objects;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.regex.Pattern;

/**
 * General validation utility class, providing various validation methods.
 *
 * <p>Supports returning boolean values, throwing standard exceptions, or custom exceptions.
 *
 * @author qwq-dev
 * @since 2025-04-27 14:26
 */
@UtilityClass
public class ValidationUtil {

    /**
     * Checks if the object is null.
     *
     * @param value object to check
     * @return true if the object is null, false otherwise
     */
    public static boolean isNull(Object value) {
        return value == null;
    }

    /**
     * Checks if the object is not null.
     *
     * @param value object to check
     * @return true if the object is not null, false otherwise
     */
    public static boolean notNull(Object value) {
        return value != null;
    }

    /**
     * Ensures that the object is not null, throws {@link NullPointerException} if it is null.
     *
     * @param value   object to check
     * @param message exception message
     * @throws NullPointerException if the object is null
     */
    public static void requireNonNull(Object value, String message) {
        if (value == null) {
            throw new NullPointerException(message);
        }
    }

    /**
     * Ensures that the object is not null, throws a custom exception if it is null.
     *
     * @param value             object to check
     * @param exceptionSupplier exception supplier
     * @param <T>               object type
     * @param <X>               exception type
     * @return non-null object
     * @throws X if the object is null
     */
    public static <T, X extends Throwable> T requireNonNull(T value, Supplier<? extends X> exceptionSupplier) throws X {
        if (value == null) {
            throw exceptionSupplier.get();
        }
        return value;
    }

    /**
     * Checks if two objects are equal using {@link Objects#equals(Object, Object)}.
     *
     * @param object1 the first object
     * @param object2 the second object
     * @return true if the objects are equal, false otherwise
     */
    public static boolean equals(Object object1, Object object2) {
        return Objects.equals(object1, object2);
    }

    /**
     * Checks if two objects are not equal using {@link Objects#equals(Object, Object)}.
     *
     * @param object1 the first object
     * @param object2 the second object
     * @return true if the objects are not equal, false otherwise
     */
    public static boolean notEquals(Object object1, Object object2) {
        return !Objects.equals(object1, object2);
    }

    /**
     * Ensures that two objects are equal, throws {@link IllegalArgumentException} if they are not.
     *
     * @param object1 the first object
     * @param object2 the second object
     * @param message exception message
     * @throws IllegalArgumentException if the objects are not equal
     */
    public static void requireEquals(Object object1, Object object2, String message) {
        if (!Objects.equals(object1, object2)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that two objects are equal, throws a custom exception if they are not.
     *
     * @param object1           the first object
     * @param object2           the second object
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the objects are not equal
     */
    public static <X extends Throwable> void requireEquals(Object object1, Object object2, Supplier<? extends X> exceptionSupplier) throws X {
        if (!Objects.equals(object1, object2)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the string is null or empty ("").
     *
     * @param inputString the string to check
     * @return true if the string is null or empty, false otherwise
     */
    public static boolean isEmpty(String inputString) {
        return inputString == null || inputString.isEmpty();
    }

    /**
     * Checks if the string is null, empty (""), or contains only whitespace characters.
     *
     * @param inputString the string to check
     * @return true if the string is null, empty, or contains only whitespace, false otherwise
     */
    public static boolean isBlank(String inputString) {
        return inputString == null || inputString.trim().isEmpty();
    }

    /**
     * Checks if the string is not null and not empty ("").
     *
     * @param inputString the string to check
     * @return true if the string is not null and not empty, false otherwise
     */
    public static boolean notEmpty(String inputString) {
        return inputString != null && !inputString.isEmpty();
    }

    /**
     * Checks if the string is not null, not empty (""), and contains characters other than whitespace.
     *
     * @param inputString the string to check
     * @return true if the string is not null, not empty, and contains non-whitespace characters, false otherwise
     */
    public static boolean notBlank(String inputString) {
        return inputString != null && !inputString.trim().isEmpty();
    }

    /**
     * Ensures that the string is not null and not empty (""), throws {@link IllegalArgumentException} otherwise.
     *
     * @param inputString the string to check
     * @param message     exception message
     * @throws IllegalArgumentException if the string is null or empty
     */
    public static void requireNotEmpty(String inputString, String message) {
        if (isEmpty(inputString)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the string is not null, not empty (""), and does not contain only whitespace, throws {@link IllegalArgumentException} otherwise.
     *
     * @param inputString the string to check
     * @param message     exception message
     * @throws IllegalArgumentException if the string is null, empty, or contains only whitespace
     */
    public static void requireNotBlank(String inputString, String message) {
        if (isBlank(inputString)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the string is not null and not empty (""), throws a custom exception otherwise.
     *
     * @param inputString       the string to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the string is null or empty
     */
    public static <X extends Throwable> void requireNotEmpty(String inputString, Supplier<? extends X> exceptionSupplier) throws X {
        if (isEmpty(inputString)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the string is not null, not empty (""), and does not contain only whitespace, throws a custom exception otherwise.
     *
     * @param inputString       the string to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the string is null, empty, or contains only whitespace
     */
    public static <X extends Throwable> void requireNotBlank(String inputString, Supplier<? extends X> exceptionSupplier) throws X {
        if (isBlank(inputString)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the string length is within the specified range (inclusive).
     *
     * @param inputString the string to check
     * @param minLength   minimum length (inclusive)
     * @param maxLength   maximum length (inclusive)
     * @return true if the string length is within the specified range, false otherwise
     */
    public static boolean lengthBetween(String inputString, int minLength, int maxLength) {
        if (inputString == null) {
            return false;
        }
        int length = inputString.length();
        return length >= minLength && length <= maxLength;
    }

    /**
     * Ensures that the string length is within the specified range (inclusive), throws {@link IllegalArgumentException} otherwise.
     *
     * @param inputString the string to check
     * @param minLength   minimum length (inclusive)
     * @param maxLength   maximum length (inclusive)
     * @param message     exception message
     * @throws IllegalArgumentException if the string length is not within the specified range
     */
    public static void requireLengthBetween(String inputString, int minLength, int maxLength, String message) {
        if (!lengthBetween(inputString, minLength, maxLength)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the string length is within the specified range (inclusive), throws a custom exception otherwise.
     *
     * @param inputString       the string to check
     * @param minLength         minimum length (inclusive)
     * @param maxLength         maximum length (inclusive)
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the string length is not within the specified range
     */
    public static <X extends Throwable> void requireLengthBetween(String inputString, int minLength, int maxLength, Supplier<? extends X> exceptionSupplier) throws X {
        if (!lengthBetween(inputString, minLength, maxLength)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the string matches the regular expression.
     *
     * @param inputString the string to check
     * @param regex       the regular expression
     * @return true if the string matches the regular expression, false otherwise
     */
    public static boolean matches(String inputString, String regex) {
        return inputString != null && inputString.matches(regex);
    }

    /**
     * Checks if the string matches the regular expression pattern.
     *
     * @param inputString the string to check
     * @param pattern     the regular expression pattern {@link Pattern}
     * @return true if the string matches the regular expression pattern, false otherwise
     */
    public static boolean matches(String inputString, Pattern pattern) {
        requireNonNull(pattern, "Pattern cannot be null");
        return inputString != null && pattern.matcher(inputString).matches();
    }

    /**
     * Ensures that the string matches the regular expression, throws {@link IllegalArgumentException} otherwise.
     *
     * @param inputString the string to check
     * @param regex       the regular expression
     * @param message     exception message
     * @throws IllegalArgumentException if the string does not match the regular expression
     */
    public static void requireMatches(String inputString, String regex, String message) {
        if (!matches(inputString, regex)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the string matches the regular expression pattern, throws {@link IllegalArgumentException} otherwise.
     *
     * @param inputString the string to check
     * @param pattern     the regular expression pattern {@link Pattern}
     * @param message     exception message
     * @throws IllegalArgumentException if the string does not match the regular expression pattern
     */
    public static void requireMatches(String inputString, Pattern pattern, String message) {
        if (!matches(inputString, pattern)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the string matches the regular expression, throws a custom exception otherwise.
     *
     * @param inputString       the string to check
     * @param regex             the regular expression
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the string does not match the regular expression
     */
    public static <X extends Throwable> void requireMatches(String inputString, String regex, Supplier<? extends X> exceptionSupplier) throws X {
        if (!matches(inputString, regex)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the string matches the regular expression pattern, throws a custom exception otherwise.
     *
     * @param inputString       the string to check
     * @param pattern           the regular expression pattern {@link Pattern}
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the string does not match the regular expression pattern
     */
    public static <X extends Throwable> void requireMatches(String inputString, Pattern pattern, Supplier<? extends X> exceptionSupplier) throws X {
        if (!matches(inputString, pattern)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the collection is null or empty.
     *
     * @param collection the collection to check
     * @return true if the collection is null or empty, false otherwise
     */
    public static boolean isEmpty(Collection<?> collection) {
        return collection == null || collection.isEmpty();
    }

    /**
     * Checks if the collection is not null and not empty.
     *
     * @param collection the collection to check
     * @return true if the collection is not null and not empty, false otherwise
     */
    public static boolean notEmpty(Collection<?> collection) {
        return collection != null && !collection.isEmpty();
    }

    /**
     * Ensures that the collection is not null and not empty, throws {@link IllegalArgumentException} otherwise.
     *
     * @param collection the collection to check
     * @param message    exception message
     * @throws IllegalArgumentException if the collection is null or empty
     */
    public static void requireNotEmpty(Collection<?> collection, String message) {
        if (isEmpty(collection)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the collection is not null and not empty, throws a custom exception otherwise.
     *
     * @param collection        the collection to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the collection is null or empty
     */
    public static <X extends Throwable> void requireNotEmpty(Collection<?> collection, Supplier<? extends X> exceptionSupplier) throws X {
        if (isEmpty(collection)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the map is null or empty.
     *
     * @param map the map to check
     * @return true if the map is null or empty, false otherwise
     */
    public static boolean isEmpty(Map<?, ?> map) {
        return map == null || map.isEmpty();
    }

    /**
     * Checks if the map is not null and not empty.
     *
     * @param map the map to check
     * @return true if the map is not null and not empty, false otherwise
     */
    public static boolean notEmpty(Map<?, ?> map) {
        return map != null && !map.isEmpty();
    }

    /**
     * Ensures that the map is not null and not empty, throws {@link IllegalArgumentException} otherwise.
     *
     * @param map     the map to check
     * @param message exception message
     * @throws IllegalArgumentException if the map is null or empty
     */
    public static void requireNotEmpty(Map<?, ?> map, String message) {
        if (isEmpty(map)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the map is not null and not empty, throws a custom exception otherwise.
     *
     * @param map               the map to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the map is null or empty
     */
    public static <X extends Throwable> void requireNotEmpty(Map<?, ?> map, Supplier<? extends X> exceptionSupplier) throws X {
        if (isEmpty(map)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the array is null or empty.
     *
     * @param array the array to check
     * @param <T>   the type of elements in the array
     * @return true if the array is null or empty, false otherwise
     */
    public static <T> boolean isEmpty(T[] array) {
        return array == null || array.length == 0;
    }

    /**
     * Checks if the array is not null and not empty.
     *
     * @param array the array to check
     * @param <T>   the type of elements in the array
     * @return true if the array is not null and not empty, false otherwise
     */
    public static <T> boolean notEmpty(T[] array) {
        return array != null && array.length > 0;
    }

    /**
     * Ensures that the array is not null and not empty, throws {@link IllegalArgumentException} otherwise.
     *
     * @param array   the array to check
     * @param message exception message
     * @param <T>     the type of elements in the array
     * @throws IllegalArgumentException if the array is null or empty
     */
    public static <T> void requireNotEmpty(T[] array, String message) {
        if (isEmpty(array)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the array is not null and not empty, throws a custom exception otherwise.
     *
     * @param array             the array to check
     * @param exceptionSupplier exception supplier
     * @param <T>               the type of elements in the array
     * @param <X>               exception type
     * @throws X if the array is null or empty
     */
    public static <T, X extends Throwable> void requireNotEmpty(T[] array, Supplier<? extends X> exceptionSupplier) throws X {
        if (isEmpty(array)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the int value is greater than the specified minimum (exclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (exclusive)
     * @return true if the value is greater than the minimum, false otherwise
     */
    public static boolean isGreaterThan(int value, int min) {
        return value > min;
    }

    /**
     * Checks if the long value is greater than the specified minimum (exclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (exclusive)
     * @return true if the value is greater than the minimum, false otherwise
     */
    public static boolean isGreaterThan(long value, long min) {
        return value > min;
    }

    /**
     * Checks if the int value is greater than or equal to the specified minimum (inclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (inclusive)
     * @return true if the value is greater than or equal to the minimum, false otherwise
     */
    public static boolean isGreaterThanOrEqual(int value, int min) {
        return value >= min;
    }

    /**
     * Checks if the long value is greater than or equal to the specified minimum (inclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (inclusive)
     * @return true if the value is greater than or equal to the minimum, false otherwise
     */
    public static boolean isGreaterThanOrEqual(long value, long min) {
        return value >= min;
    }

    /**
     * Checks if the int value is less than the specified maximum (exclusive).
     *
     * @param value the value to check
     * @param max   the maximum value (exclusive)
     * @return true if the value is less than the maximum, false otherwise
     */
    public static boolean isLessThan(int value, int max) {
        return value < max;
    }

    /**
     * Checks if the long value is less than the specified maximum (exclusive).
     *
     * @param value the value to check
     * @param max   the maximum value (exclusive)
     * @return true if the value is less than the maximum, false otherwise
     */
    public static boolean isLessThan(long value, long max) {
        return value < max;
    }

    /**
     * Checks if the int value is less than or equal to the specified maximum (inclusive).
     *
     * @param value the value to check
     * @param max   the maximum value (inclusive)
     * @return true if the value is less than or equal to the maximum, false otherwise
     */
    public static boolean isLessThanOrEqual(int value, int max) {
        return value <= max;
    }

    /**
     * Checks if the long value is less than or equal to the specified maximum (inclusive).
     *
     * @param value the value to check
     * @param max   the maximum value (inclusive)
     * @return true if the value is less than or equal to the maximum, false otherwise
     */
    public static boolean isLessThanOrEqual(long value, long max) {
        return value <= max;
    }

    /**
     * Checks if the int value is within the specified range (inclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (inclusive)
     * @param max   the maximum value (inclusive)
     * @return true if the value is within the specified range, false otherwise
     */
    public static boolean isBetween(int value, int min, int max) {
        return value >= min && value <= max;
    }

    /**
     * Checks if the long value is within the specified range (inclusive).
     *
     * @param value the value to check
     * @param min   the minimum value (inclusive)
     * @param max   the maximum value (inclusive)
     * @return true if the value is within the specified range, false otherwise
     */
    public static boolean isBetween(long value, long min, long max) {
        return value >= min && value <= max;
    }

    /**
     * Ensures that the int value is greater than the specified minimum (exclusive), throws {@link IllegalArgumentException} otherwise.
     *
     * @param value   the value to check
     * @param min     the minimum value (exclusive)
     * @param message exception message
     * @throws IllegalArgumentException if the value is not greater than the minimum
     */
    public static void requireGreaterThan(int value, int min, String message) {
        if (!isGreaterThan(value, min)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the long value is greater than the specified minimum (exclusive), throws {@link IllegalArgumentException} otherwise.
     *
     * @param value   the value to check
     * @param min     the minimum value (exclusive)
     * @param message exception message
     * @throws IllegalArgumentException if the value is not greater than the minimum
     */
    public static void requireGreaterThan(long value, long min, String message) {
        if (!isGreaterThan(value, min)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the int value is greater than the specified minimum (exclusive), throws a custom exception otherwise.
     *
     * @param value             the value to check
     * @param min               the minimum value (exclusive)
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the value is not greater than the minimum
     */
    public static <X extends Throwable> void requireGreaterThan(int value, int min, Supplier<? extends X> exceptionSupplier) throws X {
        if (!isGreaterThan(value, min)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the long value is greater than the specified minimum (exclusive), throws a custom exception otherwise.
     *
     * @param value             the value to check
     * @param min               the minimum value (exclusive)
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the value is not greater than the minimum
     */
    public static <X extends Throwable> void requireGreaterThan(long value, long min, Supplier<? extends X> exceptionSupplier) throws X {
        if (!isGreaterThan(value, min)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the int value is within the specified range (inclusive), throws {@link IllegalArgumentException} otherwise.
     *
     * @param value   the value to check
     * @param min     the minimum value (inclusive)
     * @param max     the maximum value (inclusive)
     * @param message exception message
     * @throws IllegalArgumentException if the value is not within the specified range
     */
    public static void requireBetween(int value, int min, int max, String message) {
        if (!isBetween(value, min, max)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the long value is within the specified range (inclusive), throws {@link IllegalArgumentException} otherwise.
     *
     * @param value   the value to check
     * @param min     the minimum value (inclusive)
     * @param max     the maximum value (inclusive)
     * @param message exception message
     * @throws IllegalArgumentException if the value is not within the specified range
     */
    public static void requireBetween(long value, long min, long max, String message) {
        if (!isBetween(value, min, max)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the int value is within the specified range (inclusive), throws a custom exception otherwise.
     *
     * @param value             the value to check
     * @param min               the minimum value (inclusive)
     * @param max               the maximum value (inclusive)
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the value is not within the specified range
     */
    public static <X extends Throwable> void requireBetween(int value, int min, int max, Supplier<? extends X> exceptionSupplier) throws X {
        if (!isBetween(value, min, max)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the long value is within the specified range (inclusive), throws a custom exception otherwise.
     *
     * @param value             the value to check
     * @param min               the minimum value (inclusive)
     * @param max               the maximum value (inclusive)
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the value is not within the specified range
     */
    public static <X extends Throwable> void requireBetween(long value, long min, long max, Supplier<? extends X> exceptionSupplier) throws X {
        if (!isBetween(value, min, max)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the index is within the bounds [0, size).
     *
     * @param index the index
     * @param size  the size
     * @return true if the index is valid, false otherwise
     */
    public static boolean isIndexValid(int index, int size) {
        return index >= 0 && index < size;
    }

    /**
     * Checks if the index is valid for accessing an element of an array/list/string.
     * The range is [0, size). Throws {@link IndexOutOfBoundsException} if invalid.
     *
     * @param index the index
     * @param size  the size
     * @throws IndexOutOfBoundsException if index &lt; 0 or index &gt;= size
     * @see Objects#checkIndex(int, int)
     */
    public static void checkElementIndex(int index, int size) {
        if (!isIndexValid(index, size)) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
        }
    }

    /**
     * Checks if the index is valid for accessing an element of an array/list/string.
     * The range is [0, size). Throws {@link IndexOutOfBoundsException} with a custom message if invalid.
     *
     * @param index   the index
     * @param size    the size
     * @param message exception message
     * @throws IndexOutOfBoundsException if index &lt; 0 or index &gt;= size
     */
    public static void checkElementIndex(int index, int size, String message) {
        if (!isIndexValid(index, size)) {
            throw new IndexOutOfBoundsException(message);
        }
    }

    /**
     * Checks if the index is valid for accessing an element of an array/list/string.
     * The range is [0, size). Throws a custom exception if invalid.
     *
     * @param index             the index
     * @param size              the size
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if index &lt; 0 or index &gt;= size
     */
    public static <X extends Throwable> void checkElementIndex(int index, int size, Supplier<? extends X> exceptionSupplier) throws X {
        if (!isIndexValid(index, size)) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Checks if the index is a valid position in an array/list/string.
     * The range is [0, size]. Throws {@link IndexOutOfBoundsException} if invalid.
     * This is typically used for iterators or adding elements at the end of a list.
     *
     * @param index the index
     * @param size  the size
     * @throws IndexOutOfBoundsException if index &lt; 0 or index &gt; size
     * @see Objects#checkFromIndexSize(int, int, int) (similar concept, but this checks a single position)
     */
    public static void checkPositionIndex(int index, int size) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException("Index " + index + " out of bounds for length " + size);
        }
    }

    /**
     * Checks if the index is a valid position in an array/list/string.
     * The range is [0, size]. Throws {@link IndexOutOfBoundsException} with a custom message if invalid.
     *
     * @param index   the index
     * @param size    the size
     * @param message exception message
     * @throws IndexOutOfBoundsException if index &lt; 0 or index &gt; size
     */
    public static void checkPositionIndex(int index, int size, String message) {
        if (index < 0 || index > size) {
            throw new IndexOutOfBoundsException(message);
        }
    }

    /**
     * Checks if the index is a valid position in an array/list/string.
     * The range is [0, size]. Throws a custom exception if invalid.
     *
     * @param index             the index
     * @param size              the size
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if index &lt; 0 or index &gt; size
     */
    public static <X extends Throwable> void checkPositionIndex(int index, int size, Supplier<? extends X> exceptionSupplier) throws X {
        if (index < 0 || index > size) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the condition is true, throws {@link IllegalArgumentException} otherwise.
     *
     * @param condition the condition to check
     * @param message   exception message
     * @throws IllegalArgumentException if the condition is false
     */
    public static void requireTrue(boolean condition, String message) {
        if (!condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the condition is true, throws a custom exception otherwise.
     *
     * @param condition         the condition to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the condition is false
     */
    public static <X extends Throwable> void requireTrue(boolean condition, Supplier<? extends X> exceptionSupplier) throws X {
        if (!condition) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Ensures that the condition is false, throws {@link IllegalArgumentException} otherwise.
     *
     * @param condition the condition to check
     * @param message   exception message
     * @throws IllegalArgumentException if the condition is true
     */
    public static void requireFalse(boolean condition, String message) {
        if (condition) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Ensures that the condition is false, throws a custom exception otherwise.
     *
     * @param condition         the condition to check
     * @param exceptionSupplier exception supplier
     * @param <X>               exception type
     * @throws X if the condition is true
     */
    public static <X extends Throwable> void requireFalse(boolean condition, Supplier<? extends X> exceptionSupplier) throws X {
        if (condition) {
            throw exceptionSupplier.get();
        }
    }

    /**
     * Generic validation method, throws {@link IllegalArgumentException} when the predicate returns false.
     *
     * @param value     the object to validate
     * @param predicate the predicate function
     * @param message   exception message
     * @param <T>       the type of the object
     * @throws IllegalArgumentException if the predicate returns false
     */
    public static <T> void validate(T value, Predicate<T> predicate, String message) {
        requireNonNull(predicate, "Predicate cannot be null");
        if (!predicate.test(value)) {
            throw new IllegalArgumentException(message);
        }
    }

    /**
     * Generic validation method, throws a custom exception when the predicate returns false.
     *
     * @param value             the object to validate
     * @param predicate         the predicate function
     * @param exceptionSupplier exception supplier
     * @param <T>               the type of the object
     * @param <X>               exception type
     * @throws X if the predicate returns false
     */
    public static <T, X extends Throwable> void validate(T value, Predicate<T> predicate, Supplier<? extends X> exceptionSupplier) throws X {
        requireNonNull(predicate, "Predicate cannot be null");
        if (!predicate.test(value)) {
            throw exceptionSupplier.get();
        }
    }

}
