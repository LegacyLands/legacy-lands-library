package net.legacy.library.commons.util;

import io.fairyproject.log.ILogger;
import io.fairyproject.log.Log;
import lombok.Builder;
import lombok.Getter;

import java.time.Duration;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;

/**
 * A logger that prints at most one line per topic per window, carrying the number of lines it held back on the next
 * line it prints.
 *
 * <p>A warning that fires on every packet, tick or event can write thousands of identical lines a second exactly when
 * the server is already in trouble, turning a fault into a disk and console outage. Here each topic speaks once per
 * {@link #getWindow()}; the lines in between are counted, not printed, and the next printed line says how many there
 * were, so nothing is hidden, only compressed.
 *
 * <p>Topics should be constants of the calling code. The number of topics is bounded by {@link #getMaxTopics()}: a
 * new topic beyond it is dropped and counted in {@link #getDroppedTopics()}, so a value that slipped into a topic by
 * mistake cannot grow the map without bound.
 *
 * <p>Instances are thread-safe and lock-free, and so can be used from virtual threads without pinning. Lines go to
 * {@link Log} unless another {@link ILogger} is given, with the same {@code %s} placeholders.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@Builder
public class RateLimitedLogger {

    /**
     * Default window between two lines of one topic (1 minute).
     */
    public static final Duration DEFAULT_WINDOW = Duration.ofMinutes(1);

    /**
     * Default largest number of topics.
     */
    public static final int DEFAULT_MAX_TOPICS = 256;

    /**
     * How long a topic stays quiet after it printed a line.
     */
    @Getter
    @Builder.Default
    private final Duration window = DEFAULT_WINDOW;

    /**
     * The largest number of topics tracked at once.
     */
    @Getter
    @Builder.Default
    private final int maxTopics = DEFAULT_MAX_TOPICS;

    /**
     * Where lines go, or {@code null} for {@link Log}.
     */
    private final ILogger logger;

    /**
     * The time source in nanoseconds; {@link System#nanoTime()} unless a test supplies its own.
     */
    @Builder.Default
    private final LongSupplier nanoClock = System::nanoTime;

    private final Map<String, Topic> topics = new ConcurrentHashMap<>();
    private final AtomicLong droppedTopics = new AtomicLong();
    private final AtomicLong suppressedTotal = new AtomicLong();

    /**
     * Creates a logger with the default window and topic limit, printing to {@link Log}.
     *
     * @return the logger
     */
    public static RateLimitedLogger create() {
        return builder().build();
    }

    /**
     * Creates a logger with a window, printing to {@link Log}.
     *
     * @param window how long a topic stays quiet after it printed a line
     * @return the logger
     */
    public static RateLimitedLogger of(Duration window) {
        return builder().window(window).build();
    }

    /**
     * Logs a warning, unless this topic printed a line within the window.
     *
     * @param topic   the topic, a constant naming what happened; never printed
     * @param message the line, with {@code %s} placeholders
     * @param args    the placeholder values
     * @return {@code true} if the line was printed
     */
    public boolean warn(String topic, String message, Object... args) {
        return emit(topic, Level.WARN, message, null, args);
    }

    /**
     * Logs a warning with the stack trace of a throwable, unless this topic printed a line within the window.
     *
     * @param topic     the topic
     * @param message   the line, with {@code %s} placeholders
     * @param throwable the throwable whose stack trace is printed
     * @param args      the placeholder values
     * @return {@code true} if the line was printed
     */
    public boolean warn(String topic, String message, Throwable throwable, Object... args) {
        return emit(topic, Level.WARN, message, throwable, args);
    }

    /**
     * Logs an informational line, unless this topic printed a line within the window.
     *
     * @param topic   the topic
     * @param message the line, with {@code %s} placeholders
     * @param args    the placeholder values
     * @return {@code true} if the line was printed
     */
    public boolean info(String topic, String message, Object... args) {
        return emit(topic, Level.INFO, message, null, args);
    }

    /**
     * Logs an error with the stack trace of a throwable, unless this topic printed a line within the window.
     *
     * @param topic     the topic
     * @param message   the line, with {@code %s} placeholders
     * @param throwable the throwable whose stack trace is printed, or {@code null}
     * @param args      the placeholder values
     * @return {@code true} if the line was printed
     */
    public boolean error(String topic, String message, Throwable throwable, Object... args) {
        return emit(topic, Level.ERROR, message, throwable, args);
    }

    /**
     * Gets the number of topics refused because {@link #getMaxTopics()} was reached.
     *
     * @return the number of refused topics
     */
    public long getDroppedTopics() {
        return droppedTopics.get();
    }

    /**
     * Gets the number of lines held back across every topic since this logger was created.
     *
     * @return the number of suppressed lines
     */
    public long getSuppressedTotal() {
        return suppressedTotal.get();
    }

    /**
     * Gets the number of topics tracked.
     *
     * @return the number of topics
     */
    public int getTopicCount() {
        return topics.size();
    }

    /**
     * Forgets every topic, so the next line of each is printed at once.
     */
    public void reset() {
        topics.clear();
    }

    private boolean emit(String topic, Level level, String message, Throwable throwable, Object... args) {
        Topic state = topics.get(topic);
        if (state == null) {
            if (topics.size() >= maxTopics) {
                // Refusing a topic loses a line; growing without bound could lose the server
                droppedTopics.incrementAndGet();
                return false;
            }
            state = topics.computeIfAbsent(topic, key -> new Topic());
        }

        long suppressed = state.claim(nanoClock.getAsLong(), window.toNanos());
        if (suppressed < 0) {
            suppressedTotal.incrementAndGet();
            return false;
        }

        String line = message;
        Object[] lineArgs = args;
        if (suppressed > 0) {
            line = message + " (%s held back)";
            lineArgs = Arrays.copyOf(args, args.length + 1);
            lineArgs[args.length] = PluralUtil.count(suppressed, "similar line", "similar lines");
        }

        ILogger target = logger == null ? Log.get() : logger;
        switch (level) {
            case INFO -> target.info(line, lineArgs);
            case WARN -> {
                if (throwable == null) {
                    target.warn(line, lineArgs);
                } else {
                    target.warn(line, throwable, lineArgs);
                }
            }
            case ERROR -> {
                if (throwable == null) {
                    target.error(line, lineArgs);
                } else {
                    target.error(line, throwable, lineArgs);
                }
            }
        }
        return true;
    }

    private enum Level {

        INFO, WARN, ERROR

    }

    /**
     * The window of one topic. {@link #claim} is a compare-and-set, so that when several threads reach a quiet topic
     * at once exactly one of them prints.
     */
    private static final class Topic {

        private final AtomicLong nextAllowedNanos = new AtomicLong(Long.MIN_VALUE);
        private final AtomicLong suppressed = new AtomicLong();

        /**
         * Claims the right to print.
         *
         * @return the number of lines held back since the last printed one, or {@code -1} when the window is still
         * closed
         */
        long claim(long nowNanos, long windowNanos) {
            while (true) {
                long nextAllowed = nextAllowedNanos.get();
                // Long.MIN_VALUE until the first line, so a negative System.nanoTime cannot read as a closed window
                if (nowNanos < nextAllowed) {
                    suppressed.incrementAndGet();
                    return -1;
                }
                long reopensAt = nowNanos > Long.MAX_VALUE - windowNanos ? Long.MAX_VALUE : nowNanos + windowNanos;
                if (nextAllowedNanos.compareAndSet(nextAllowed, reopensAt)) {
                    return suppressed.getAndSet(0);
                }
            }
        }

    }

}
