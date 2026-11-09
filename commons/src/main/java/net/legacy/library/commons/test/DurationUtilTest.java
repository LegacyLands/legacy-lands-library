package net.legacy.library.commons.test;

import net.legacy.library.commons.util.DurationUtil;
import net.legacy.library.commons.util.PluralUtil;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.time.Duration;
import java.util.Arrays;
import java.util.List;

/**
 * Test class for {@link DurationUtil} and {@link PluralUtil}, validating how durations and counts are read and
 * written for people.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "duration-util-test",
        description = "Tests DurationUtil parsing and formatting and PluralUtil count agreement",
        tags = {"commons", "duration", "format", "plural"},
        priority = 4,
        timeout = 2000,
        expectedResult = "SUCCESS"
)
public class DurationUtilTest {

    private static final String MODULE_NAME = "commons";

    /**
     * Tests parsing single and compound durations, unit words, separators, a bare zero and ISO-8601.
     */
    public static boolean testDurationParse() {
        try {
            boolean parsed = DurationUtil.parse("30s").equals(Duration.ofSeconds(30))
                    && DurationUtil.parse("1h 30m").equals(Duration.ofMinutes(90))
                    && DurationUtil.parse("1h30m").equals(Duration.ofMinutes(90))
                    && DurationUtil.parse(" 2D, 3H ").equals(Duration.ofHours(51))
                    && DurationUtil.parse("250ms").equals(Duration.ofMillis(250))
                    && DurationUtil.parse("1h_30m").equals(Duration.ofMinutes(90))
                    && DurationUtil.parse("2 minutes").equals(Duration.ofMinutes(2))
                    && DurationUtil.parse("1w").equals(Duration.ofDays(7))
                    && DurationUtil.parse("0").equals(Duration.ZERO)
                    && DurationUtil.parse("PT1H30M").equals(Duration.ofMinutes(90));
            return check("parse", parsed);
        } catch (Exception exception) {
            return failure("testDurationParse", exception);
        }
    }

    /**
     * Tests that every malformed text is refused with an {@link IllegalArgumentException}, and that
     * {@link DurationUtil#tryParse(String)} turns it into an empty result.
     */
    public static boolean testDurationParseRejectsMalformed() {
        try {
            List<String> malformed = Arrays.asList(null, "", "  ", "30", "1 2s", "5x", "-5s", "s", "1h-", "PT-1H",
                    "99999999999999999999d", "106751991168d");
            boolean allRefused = malformed.stream().allMatch(text -> {
                try {
                    DurationUtil.parse(text);
                    TestLogger.logFailure(MODULE_NAME, "Accepted malformed duration '%s'", text);
                    return false;
                } catch (IllegalArgumentException expected) {
                    return DurationUtil.tryParse(text).isEmpty();
                }
            });
            return check("malformed refused", allRefused);
        } catch (Exception exception) {
            return failure("testDurationParseRejectsMalformed", exception);
        }
    }

    /**
     * Tests formatting: two largest units, zero units dropped, smaller units cut off, edge values.
     */
    public static boolean testDurationFormat() {
        try {
            boolean formatted = "45s".equals(DurationUtil.format(Duration.ofSeconds(45)))
                    && "12m 30s".equals(DurationUtil.format(Duration.ofSeconds(750)))
                    && "5h 20m".equals(DurationUtil.format(Duration.ofSeconds(5 * 3600 + 20 * 60 + 59)))
                    && "2d 3h".equals(DurationUtil.format(Duration.ofHours(51).plusMinutes(59)))
                    && "2d".equals(DurationUtil.format(Duration.ofDays(2).plusMinutes(5)))
                    && "1h".equals(DurationUtil.format(Duration.ofHours(1).plusSeconds(30)))
                    && "1h 30s".equals(DurationUtil.format(Duration.ofHours(1).plusSeconds(30), 3))
                    && "15m".equals(DurationUtil.format(Duration.ofMinutes(15)))
                    && "0s".equals(DurationUtil.format(Duration.ZERO))
                    && "0s".equals(DurationUtil.format(Duration.ofSeconds(-5)))
                    && "0s".equals(DurationUtil.format(null))
                    && "1s".equals(DurationUtil.format(Duration.ofMillis(200)));
            return check("format", formatted);
        } catch (Exception exception) {
            return failure("testDurationFormat", exception);
        }
    }

    /**
     * Tests that what is formatted parses back to the same duration, cut to the units written.
     */
    public static boolean testDurationFormatParsesBack() {
        try {
            List<Duration> durations = List.of(Duration.ofSeconds(1), Duration.ofSeconds(59), Duration.ofSeconds(61),
                    Duration.ofMinutes(90), Duration.ofHours(23).plusMinutes(26), Duration.ofDays(400));
            boolean roundTrip = durations.stream()
                    .allMatch(duration -> DurationUtil.parse(DurationUtil.format(duration, 4)).equals(duration));
            return check("round trip", roundTrip);
        } catch (Exception exception) {
            return failure("testDurationFormatParsesBack", exception);
        }
    }

    /**
     * Tests count agreement: one takes the singular, zero, negatives and every other number the plural.
     */
    public static boolean testPluralCountAgreement() {
        try {
            boolean agreed = "1 entry".equals(PluralUtil.count(1, "entry", "entries"))
                    && "0 entries".equals(PluralUtil.count(0, "entry", "entries"))
                    && "2 entries".equals(PluralUtil.count(2, "entry", "entries"))
                    && "-1 entries".equals(PluralUtil.count(-1, "entry", "entries"))
                    && "entry".equals(PluralUtil.noun(1, "entry", "entries"))
                    && "entries".equals(PluralUtil.noun(11, "entry", "entries"));
            return check("count agreement", agreed);
        } catch (Exception exception) {
            return failure("testPluralCountAgreement", exception);
        }
    }

    private static boolean check(String what, boolean condition) {
        if (!condition) {
            TestLogger.logFailure(MODULE_NAME, "Check failed: %s", what);
        }
        return condition;
    }

    private static boolean failure(String testName, Exception exception) {
        TestLogger.logFailure(MODULE_NAME, "%s failed: %s", testName, exception.getMessage());
        return false;
    }

}
