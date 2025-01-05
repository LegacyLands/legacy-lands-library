package net.legacy.library.player.task;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyPlayerDataService;

import java.time.Duration;

/**
 * @author qwq-dev
 * @since 2025-01-04 12:53
 */
@RequiredArgsConstructor
public class PlayerDataPersistenceTimerTask implements TaskInterface {
    private final Duration delay;
    private final Duration interval;
    private final LockSettings lockSettings;
    private final LegacyPlayerDataService legacyPlayerDataService;

    public static PlayerDataPersistenceTimerTask of(Duration delay, Duration interval, LockSettings lockSettings, LegacyPlayerDataService legacyPlayerDataService) {
        return new PlayerDataPersistenceTimerTask(delay, interval, lockSettings, legacyPlayerDataService);
    }

    @Override
    public ScheduledTask<?> start() {
        return scheduleAtFixedRate(() -> PlayerDataPersistenceTask.of(lockSettings, legacyPlayerDataService).start(), delay, interval);
    }
}
