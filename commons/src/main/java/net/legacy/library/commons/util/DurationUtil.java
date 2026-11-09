package net.legacy.library.commons.util;

import lombok.experimental.UtilityClass;

import java.time.Duration;
import java.time.format.DateTimeParseException;
import java.util.Locale;
import java.util.Optional;

/**
 * Utility class for reading durations written by people, such as {@code 30s} or {@code 1h 30m} in a configuration
 * file or a command, and for writing durations the same way.
 *
 * <p>{@link #parse(String)} reads one or more amounts, each followed by a unit: {@code ms}, {@code s}, {@code m},
 * {@code h}, {@code d} or {@code w} (and the words {@code sec}, {@code min}, {@code hour}, {@code day}, {@code week}
 * with their plurals), with or without a space before the unit. Amounts may be separated by spaces, commas or
 * underscores, and a bare {@code 0} is zero. A text starting with {@code P} is read as ISO-8601, such as
 * {@code PT1H30M}. {@link #format(Duration)} writes what {@link #parse(String)} reads, so a value copied out of a
 * message can be pasted into a command.
 *
 * <p>All methods are thread-safe.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class DurationUtil {

    private static final long MILLIS_PER_SECOND = 1000L;
    private static final long MILLIS_PER_MINUTE = 60L * MILLIS_PER_SECOND;
    private static final long MILLIS_PER_HOUR = 60L * MILLIS_PER_MINUTE;
    private static final long MILLIS_PER_DAY = 24L * MILLIS_PER_HOUR;
    private static final long MILLIS_PER_WEEK = 7L * MILLIS_PER_DAY;

    private static final long[] FORMAT_UNIT_MILLIS = {MILLIS_PER_DAY, MILLIS_PER_HOUR, MILLIS_PER_MINUTE, MILLIS_PER_SECOND};
    private static final String[] FORMAT_UNIT_NAMES = {"d", "h", "m", "s"};

    /**
     * Parses a duration.
     *
     * @param text the text, such as {@code 1h 30m}, {@code 250ms} or {@code PT10S}; case and surrounding spaces are
     *             ignored
     * @return the duration, never negative
     * @throws IllegalArgumentException if the text is {@code null}, empty, has an amount without a unit, an unknown
     *                                  unit, or a total too large for a {@link Duration} in milliseconds; the message
     *                                  says which, and is fit to show to the person who wrote the text
     */
    public static Duration parse(String text) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Duration is empty; expected a value such as 30s, 5m, 1h 30m or 2d");
        }

        String trimmed = text.strip().toLowerCase(Locale.ROOT);
        if ("0".equals(trimmed)) {
            return Duration.ZERO;
        }

        if (trimmed.startsWith("p")) {
            return parseIso(text, trimmed);
        }

        long totalMillis = 0L;
        int index = 0;
        while (index < trimmed.length()) {
            char current = trimmed.charAt(index);
            if (current == ' ' || current == ',' || current == '_') {
                index++;
                continue;
            }

            if (!Character.isDigit(current)) {
                throw new IllegalArgumentException("Could not read duration '" + text + "': unexpected '" + current
                        + "'; expected a value such as 30s, 5m, 1h 30m or 2d");
            }

            int numberStart = index;
            while (index < trimmed.length() && Character.isDigit(trimmed.charAt(index))) {
                index++;
            }
            String digits = trimmed.substring(numberStart, index);

            // A unit may be written apart from its amount, as in "2 minutes"
            while (index < trimmed.length() && trimmed.charAt(index) == ' ') {
                index++;
            }

            int unitStart = index;
            while (index < trimmed.length() && Character.isLetter(trimmed.charAt(index))) {
                index++;
            }
            String unit = trimmed.substring(unitStart, index);

            if (unit.isEmpty()) {
                throw new IllegalArgumentException("Could not read duration '" + text + "': " + digits
                        + " has no unit; add one of ms, s, m, h, d or w, such as " + digits + "s");
            }

            try {
                totalMillis = Math.addExact(totalMillis, Math.multiplyExact(Long.parseLong(digits), unitMillis(text, unit)));
            } catch (ArithmeticException | NumberFormatException exception) {
                throw new IllegalArgumentException("Could not read duration '" + text + "': it is too long", exception);
            }
        }

        return Duration.ofMillis(totalMillis);
    }

    /**
     * Parses a duration, giving nothing instead of an exception for a text that is not one.
     *
     * @param text the text, which may be {@code null}
     * @return the duration, or empty when the text cannot be read
     */
    public static Optional<Duration> tryParse(String text) {
        try {
            return Optional.of(parse(text));
        } catch (IllegalArgumentException exception) {
            return Optional.empty();
        }
    }

    /**
     * Formats a duration in its two largest units, such as {@code 45s}, {@code 12m 30s}, {@code 5h 20m} or
     * {@code 2d 3h}.
     *
     * @param duration the duration, which may be {@code null}
     * @return the text
     * @see #format(Duration, int)
     */
    public static String format(Duration duration) {
        return format(duration, 2);
    }

    /**
     * Formats a duration in at most a number of units, the largest first, among days, hours, minutes and seconds.
     *
     * <p>Smaller units are cut off rather than rounded, so the text never promises more time than is left, and a unit
     * that is zero is left out, so a round duration reads {@code 2d} and not {@code 2d 0h}. A duration that is
     * {@code null}, zero or negative reads {@code 0s}; a positive duration under a second reads {@code 1s}, because
     * {@code 0s} for something still running reads as a fault.
     *
     * @param duration the duration, which may be {@code null}
     * @param units    the largest number of units to write, at least one
     * @return the text, which {@link #parse(String)} reads back
     * @throws IllegalArgumentException if {@code units} is less than one
     */
    public static String format(Duration duration, int units) {
        if (units < 1) {
            throw new IllegalArgumentException("units must be at least 1, was " + units);
        }

        if (duration == null || duration.isZero() || duration.isNegative()) {
            return "0s";
        }

        long remainingMillis = duration.toMillis();
        if (remainingMillis < MILLIS_PER_SECOND) {
            return "1s";
        }

        StringBuilder text = new StringBuilder();
        int written = 0;
        boolean started = false;
        for (int index = 0; index < FORMAT_UNIT_MILLIS.length && written < units; index++) {
            long amount = remainingMillis / FORMAT_UNIT_MILLIS[index];
            remainingMillis %= FORMAT_UNIT_MILLIS[index];
            if (amount == 0) {
                // A zero inside the window still uses a place: 2d 0h 5m in two units is 2d, not 2d 5m
                written += started ? 1 : 0;
                continue;
            }

            if (!text.isEmpty()) {
                text.append(' ');
            }
            text.append(amount).append(FORMAT_UNIT_NAMES[index]);
            started = true;
            written++;
        }

        return text.toString();
    }

    private static Duration parseIso(String text, String trimmed) {
        try {
            Duration duration = Duration.parse(trimmed.toUpperCase(Locale.ROOT));
            if (duration.isNegative()) {
                throw new IllegalArgumentException("Could not read duration '" + text + "': it is negative");
            }
            return duration;
        } catch (DateTimeParseException exception) {
            throw new IllegalArgumentException("Could not read duration '" + text + "' as ISO-8601, such as PT1H30M",
                    exception);
        }
    }

    private static long unitMillis(String text, String unit) {
        return switch (unit) {
            case "ms", "millis" -> 1L;
            case "s", "sec", "secs", "second", "seconds" -> MILLIS_PER_SECOND;
            case "m", "min", "mins", "minute", "minutes" -> MILLIS_PER_MINUTE;
            case "h", "hr", "hrs", "hour", "hours" -> MILLIS_PER_HOUR;
            case "d", "day", "days" -> MILLIS_PER_DAY;
            case "w", "week", "weeks" -> MILLIS_PER_WEEK;
            default -> throw new IllegalArgumentException("Could not read duration '" + text + "': '" + unit
                    + "' is not a unit; use ms, s, m, h, d or w");
        };
    }

}
