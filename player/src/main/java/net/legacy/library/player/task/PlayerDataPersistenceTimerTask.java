package net.legacy.library.player.task;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;

import java.time.Duration;

/**
 * Timer task that schedules periodic execution of {@link PlayerDataPersistenceTask}
 * to ensure that player data is regularly persisted from the cache to the database.
 *
 * <p>This task is responsible for initiating the persistence process at fixed intervals,
 * based on the configured delay and interval durations.
 *
 * @author qwq-dev
 * @since 2025-01-04 12:53
 */
@RequiredArgsConstructor
public class PlayerDataPersistenceTimerTask implements TaskInterface {
    private final Duration delay;
    private final Duration interval;
    private final LockSettings lockSettings;
    private final LegacyPlayerDataService legacyPlayerDataService;

    /**
     * Factory method to create a new {@link PlayerDataPersistenceTimerTask}.
     *
     * @param delay                   the initial delay before the task starts
     * @param interval                the interval between successive executions of the task
     * @param lockSettings            the settings for lock acquisition during persistence
     * @param legacyPlayerDataService the {@link LegacyPlayerDataService} instance to use
     * @return a new instance of {@link PlayerDataPersistenceTimerTask}
     */
    public static PlayerDataPersistenceTimerTask of(Duration delay, Duration interval, LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataPersistenceTimerTask(delay, interval, lockSettings, legacyPlayerDataService);
    }

    /**
     * Starts the scheduled timer task that periodically invokes {@link PlayerDataPersistenceTask}.
     *
     * @return a {@link ScheduledTask} representing the running timer task
     */
    @Override
    public ScheduledTask<?> start() {
        return scheduleAtFixedRate(() -> PlayerDataPersistenceTask.of(lockSettings, legacyPlayerDataService).start(), delay, interval);
    }
}