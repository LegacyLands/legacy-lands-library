package net.legacy.library.foundation.util;

import lombok.Getter;
import lombok.Value;

import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Utility class for timing test operations and collecting performance metrics.
 *
 * <p>This class provides convenient methods for timing individual operations,
 * tracking multiple timers simultaneously, and calculating performance metrics
 * for test analysis and reporting.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 22:30
 */
public class TestTimer {
    /**
     * Map of active timers by name.
     */
    private final Map<String, Long> activeTimers = new ConcurrentHashMap<>();

    /**
     * Map of completed timer results by name.
     */
    private final Map<String, TimerResult> completedTimers = new ConcurrentHashMap<>();

    /**
     * Overall timer start time.
     */
    @Getter
    private final long startTime;

    /**
     * Creates a new test timer instance.
     */
    public TestTimer() {
        this.startTime = System.currentTimeMillis();
    }

    /**
     * Utility method to time a runnable operation.
     *
     * @param operation the operation to time
     * @return the duration in milliseconds
     */
    public static long timeOperation(Runnable operation) {
        long startTime = System.currentTimeMillis();
        operation.run();
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Utility method to time a supplier operation and return both the result and duration.
     *
     * @param operation the operation to time
     * @param <T>       the result type
     * @return a TimedResult containing both the operation result and duration
     */
    public static <T> TimedResult<T> timeOperation(java.util.function.Supplier<T> operation) {
        long startTime = System.currentTimeMillis();
        T result = operation.get();
        long duration = System.currentTimeMillis() - startTime;
        return new TimedResult<>(result, duration);
    }

    /**
     * Starts a named timer.
     *
     * @param timerName the name of the timer
     * @return the timer start time in milliseconds
     */
    public long startTimer(String timerName) {
        long startTime = System.currentTimeMillis();
        activeTimers.put(timerName, startTime);
        return startTime;
    }

    /**
     * Stops a named timer and records the result.
     *
     * @param timerName the name of the timer to stop
     * @return the elapsed time in milliseconds, or -1 if timer was not found
     */
    public long stopTimer(String timerName) {
        Long startTime = activeTimers.remove(timerName);
        if (startTime == null) {
            return -1;
        }

        long endTime = System.currentTimeMillis();
        long duration = endTime - startTime;

        completedTimers.put(timerName, new TimerResult(timerName, startTime, endTime, duration));
        return duration;
    }

    /**
     * Gets the elapsed time for an active timer without stopping it.
     *
     * @param timerName the name of the timer
     * @return the elapsed time in milliseconds, or -1 if timer was not found
     */
    public long getElapsedTime(String timerName) {
        Long startTime = activeTimers.get(timerName);
        if (startTime == null) {
            return -1;
        }

        return System.currentTimeMillis() - startTime;
    }

    /**
     * Gets the total elapsed time since this TestTimer was created.
     *
     * @return the total elapsed time in milliseconds
     */
    public long getTotalElapsedTime() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Checks if a timer is currently active.
     *
     * @param timerName the name of the timer
     * @return true if the timer is active
     */
    public boolean isTimerActive(String timerName) {
        return activeTimers.containsKey(timerName);
    }

    /**
     * Gets the result of a completed timer.
     *
     * @param timerName the name of the timer
     * @return the timer result, or null if not found
     */
    public TimerResult getTimerResult(String timerName) {
        return completedTimers.get(timerName);
    }

    /**
     * Gets all completed timer results.
     *
     * @return a map of timer results by name
     */
    public Map<String, TimerResult> getAllResults() {
        return new HashMap<>(completedTimers);
    }

    /**
     * Gets all currently active timer names.
     *
     * @return a set of active timer names
     */
    public Set<String> getActiveTimerNames() {
        return activeTimers.keySet();
    }

    /**
     * Stops all active timers and records their results.
     *
     * @return a map of timer names to their durations
     */
    public Map<String, Long> stopAllTimers() {
        Map<String, Long> results = new HashMap<>();

        for (String timerName : activeTimers.keySet()) {
            long duration = stopTimer(timerName);
            results.put(timerName, duration);
        }

        return results;
    }

    /**
     * Clears all timer data (both active and completed).
     */
    public void clear() {
        activeTimers.clear();
        completedTimers.clear();
    }

    /**
     * Calculates the average duration of all completed timers.
     *
     * @return the average duration in milliseconds, or 0 if no timers completed
     */
    public double getAverageDuration() {
        if (completedTimers.isEmpty()) {
            return 0.0;
        }

        long totalDuration = completedTimers.values().stream()
                .mapToLong(TimerResult::getDuration)
                .sum();

        return (double) totalDuration / completedTimers.size();
    }

    /**
     * Gets the minimum duration among all completed timers.
     *
     * @return the minimum duration in milliseconds, or 0 if no timers completed
     */
    public long getMinDuration() {
        return completedTimers.values().stream()
                .mapToLong(TimerResult::getDuration)
                .min()
                .orElse(0L);
    }

    /**
     * Gets the maximum duration among all completed timers.
     *
     * @return the maximum duration in milliseconds, or 0 if no timers completed
     */
    public long getMaxDuration() {
        return completedTimers.values().stream()
                .mapToLong(TimerResult::getDuration)
                .max()
                .orElse(0L);
    }

    /**
     * Creates a summary of all timing information.
     *
     * @return a formatted timing summary
     */
    public String getTimingSummary() {
        StringBuilder summary = new StringBuilder();
        summary.append("Total Elapsed Time: ").append(getTotalElapsedTime()).append("ms\n");
        summary.append("Completed Timers: ").append(completedTimers.size()).append("\n");
        summary.append("Active Timers: ").append(activeTimers.size()).append("\n");

        if (!completedTimers.isEmpty()) {
            summary.append("Average Duration: ").append(getAverageDuration()).append("ms\n");
            summary.append("Min Duration: ").append(getMinDuration()).append("ms\n");
            summary.append("Max Duration: ").append(getMaxDuration()).append("ms\n");

            summary.append("\nCompleted Timers:\n");
            completedTimers.values().stream()
                    .sorted((a, b) -> Long.compare(b.getDuration(), a.getDuration()))
                    .forEach(result -> summary.append("  ")
                            .append(result.getName())
                            .append(": ")
                            .append(result.getDuration())
                            .append("ms\n"));
        }

        if (!activeTimers.isEmpty()) {
            summary.append("\nActive Timers:\n");
            activeTimers.forEach((name, startTime) -> {
                long elapsed = System.currentTimeMillis() - startTime;
                summary.append("  ")
                        .append(name)
                        .append(": ")
                        .append(elapsed)
                        .append("ms (running)\n");
            });
        }

        return summary.toString();
    }

    /**
     * Represents the result of a completed timer operation.
     */
    @Value
    public static class TimerResult {
        String name;
        long startTime;
        long endTime;
        long duration;

        @Override
        public String toString() {
            return new StringBuilder()
                    .append("TimerResult{name='")
                    .append(name)
                    .append("', duration=")
                    .append(duration)
                    .append("ms}")
                    .toString();
        }
    }

    /**
     * Container for the result of a timed operation.
     */
    @Value
    public static class TimedResult<T> {
        T result;
        long duration;
    }
}