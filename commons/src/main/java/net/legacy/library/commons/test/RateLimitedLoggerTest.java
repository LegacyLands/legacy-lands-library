package net.legacy.library.commons.test;

import io.fairyproject.log.ILogger;
import net.legacy.library.commons.util.RateLimitedLogger;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Test class for {@link RateLimitedLogger}, validating windows, the carried count of held-back lines, the topic limit
 * and concurrent use.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "rate-limited-logger-test",
        description = "Tests RateLimitedLogger windows, suppressed counts, topic limit and concurrency",
        tags = {"commons", "log", "rate-limit", "concurrency"},
        priority = 5,
        timeout = 5000,
        expectedResult = "SUCCESS"
)
public class RateLimitedLoggerTest {

    private static final String MODULE_NAME = "commons";

    /**
     * Tests that a topic prints once per window and that the next printed line carries the number held back.
     */
    public static boolean testRateLimitedWindowCarriesSuppressedCount() {
        try {
            RecordingLogger recording = new RecordingLogger();
            AtomicLong clock = new AtomicLong(-5_000_000_000L);
            RateLimitedLogger logger = RateLimitedLogger.builder()
                    .window(Duration.ofSeconds(10))
                    .logger(recording)
                    .nanoClock(clock::get)
                    .build();

            boolean first = logger.warn("flood", "Packet from %s rejected", "Alice");
            boolean second = logger.warn("flood", "Packet from %s rejected", "Bob");
            boolean third = logger.warn("flood", "Packet from %s rejected", "Carol");
            boolean otherTopic = logger.info("other", "Unrelated");
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
            boolean afterWindow = logger.warn("flood", "Packet from %s rejected", "Dave");
            clock.addAndGet(Duration.ofSeconds(1).toNanos());
            boolean closedAgain = logger.warn("flood", "Packet from %s rejected", "Eve");
            clock.addAndGet(Duration.ofSeconds(10).toNanos());
            logger.warn("flood", "Packet from %s rejected", "Frank");

            return check("window", first && !second && !third && otherTopic && afterWindow && !closedAgain
                    && recording.lines.equals(List.of(
                    "WARN Packet from Alice rejected",
                    "INFO Unrelated",
                    "WARN Packet from Dave rejected (2 similar lines held back)",
                    "WARN Packet from Frank rejected (1 similar line held back)"))
                    && logger.getSuppressedTotal() == 3);
        } catch (Exception exception) {
            return failure("testRateLimitedWindowCarriesSuppressedCount", exception);
        }
    }

    /**
     * Tests that topics beyond the limit are refused and counted, and that a reset reopens every window.
     */
    public static boolean testRateLimitedTopicLimitAndReset() {
        try {
            RecordingLogger recording = new RecordingLogger();
            RateLimitedLogger logger = RateLimitedLogger.builder()
                    .maxTopics(2)
                    .logger(recording)
                    .nanoClock(() -> 0L)
                    .build();

            boolean firstTopic = logger.info("a", "A");
            boolean secondTopic = logger.info("b", "B");
            boolean refused = !logger.info("c", "C");
            boolean closed = !logger.info("a", "A again");
            logger.reset();
            boolean reopened = logger.info("c", "C after reset");

            return check("topic limit", firstTopic && secondTopic && refused && closed && reopened
                    && logger.getDroppedTopics() == 1 && logger.getTopicCount() == 1
                    && recording.lines.equals(List.of("INFO A", "INFO B", "INFO C after reset")));
        } catch (Exception exception) {
            return failure("testRateLimitedTopicLimitAndReset", exception);
        }
    }

    /**
     * Tests that the throwable of a warning or error reaches the logger.
     */
    public static boolean testRateLimitedPassesThrowable() {
        try {
            RecordingLogger recording = new RecordingLogger();
            RateLimitedLogger logger = RateLimitedLogger.builder().logger(recording).build();
            IllegalStateException cause = new IllegalStateException("boom");

            logger.warn("w", "Warned %s", cause, "once");
            logger.error("e", "Failed", cause);

            return check("throwable", recording.throwables.size() == 2 && recording.throwables.stream().allMatch(cause::equals)
                    && recording.lines.equals(List.of("WARN Warned once", "ERROR Failed")));
        } catch (Exception exception) {
            return failure("testRateLimitedPassesThrowable", exception);
        }
    }

    /**
     * Tests that among many threads reaching a quiet topic at once, exactly one prints.
     */
    public static boolean testRateLimitedConcurrentClaim() {
        try {
            RecordingLogger recording = new RecordingLogger();
            RateLimitedLogger logger = RateLimitedLogger.builder().logger(recording).nanoClock(() -> 42L).build();
            int threads = 64;
            CountDownLatch start = new CountDownLatch(1);
            CountDownLatch done = new CountDownLatch(threads);
            AtomicInteger printed = new AtomicInteger();

            try (ExecutorService executor = Executors.newVirtualThreadPerTaskExecutor()) {
                for (int index = 0; index < threads; index++) {
                    executor.execute(() -> {
                        try {
                            start.await();
                            if (logger.warn("shared", "Shared line")) {
                                printed.incrementAndGet();
                            }
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                        } finally {
                            done.countDown();
                        }
                    });
                }
                start.countDown();
                if (!done.await(5, TimeUnit.SECONDS)) {
                    return check("threads finished", false);
                }
            }

            return check("one printer", printed.get() == 1 && recording.lines.size() == 1
                    && logger.getSuppressedTotal() == threads - 1);
        } catch (Exception exception) {
            return failure("testRateLimitedConcurrentClaim", exception);
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

    /**
     * An {@link ILogger} that records every line it is given, formatted, prefixed with its level.
     */
    private static final class RecordingLogger implements ILogger {

        private final List<String> lines = new CopyOnWriteArrayList<>();
        private final List<Throwable> throwables = new CopyOnWriteArrayList<>();

        private void record(String level, String message, Throwable throwable, Object... args) {
            lines.add(level + " " + String.format(message, args));
            if (throwable != null) {
                throwables.add(throwable);
            }
        }

        @Override
        public void info(String message, Object... args) {
            record("INFO", message, null, args);
        }

        @Override
        public void debug(String message, Object... args) {
            record("DEBUG", message, null, args);
        }

        @Override
        public void warn(String message, Object... args) {
            record("WARN", message, null, args);
        }

        @Override
        public void error(String message, Object... args) {
            record("ERROR", message, null, args);
        }

        @Override
        public void info(String message, Throwable throwable, Object... args) {
            record("INFO", message, throwable, args);
        }

        @Override
        public void debug(String message, Throwable throwable, Object... args) {
            record("DEBUG", message, throwable, args);
        }

        @Override
        public void warn(String message, Throwable throwable, Object... args) {
            record("WARN", message, throwable, args);
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
            record("ERROR", message, throwable, args);
        }

        @Override
        public void info(Throwable throwable) {
            record("INFO", "%s", throwable, throwable);
        }

        @Override
        public void debug(Throwable throwable) {
            record("DEBUG", "%s", throwable, throwable);
        }

        @Override
        public void warn(Throwable throwable) {
            record("WARN", "%s", throwable, throwable);
        }

        @Override
        public void error(Throwable throwable) {
            record("ERROR", "%s", throwable, throwable);
        }

    }

}
