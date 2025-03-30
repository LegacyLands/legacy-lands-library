package net.legacy.library.player.task;

import io.fairyproject.scheduler.ScheduledTask;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.TaskInterface;
import net.legacy.library.player.service.LegacyEntityDataService;

import java.time.Duration;

/**
 * Timer task for periodically persisting all entity data to the L2 cache and database.
 *
 * <p>This task runs at regular intervals to ensure all entity data in the L1 cache
 * is properly synchronized to the L2 cache and the underlying database. It helps
 * maintain data consistency across the system and provides a safety net against
 * data loss in case of unexpected failures.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@RequiredArgsConstructor
public class EntityDataPersistenceTimerTask implements TaskInterface {
    private final Duration delay;
    private final Duration period;
    private final LockSettings lockSettings;
    private final LegacyEntityDataService service;
    private final int limit;

    /**
     * Factory method to create a new {@link EntityDataPersistenceTimerTask}.
     *
     * @param delay        the initial delay before the task starts
     * @param period       the period between successive executions of the task
     * @param lockSettings the settings for lock acquisition during persistence
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @return a new instance of {@link EntityDataPersistenceTimerTask}
     */
    public static EntityDataPersistenceTimerTask of(Duration delay, Duration period, LockSettings lockSettings, LegacyEntityDataService service) {
        return new EntityDataPersistenceTimerTask(delay, period, lockSettings, service, 1000);
    }

    /**
     * Factory method to create a new {@link EntityDataPersistenceTimerTask} with a custom limit.
     *
     * @param delay        the initial delay before the task starts
     * @param period       the period between successive executions of the task
     * @param lockSettings the settings for lock acquisition during persistence
     * @param service      the {@link LegacyEntityDataService} instance to use
     * @param limit        the maximum number of entity data entries to process per execution
     * @return a new instance of {@link EntityDataPersistenceTimerTask}
     */
    public static EntityDataPersistenceTimerTask of(Duration delay, Duration period, LockSettings lockSettings, LegacyEntityDataService service, int limit) {
        return new EntityDataPersistenceTimerTask(delay, period, lockSettings, service, limit);
    }

    /**
     * Starts the scheduled timer task that periodically persists all entity data.
     *
     * @return a {@link ScheduledTask} representing the running timer task
     */
    @Override
    public ScheduledTask<?> start() {
        return scheduleAtFixedRate(() -> {
            // First, sync L1 cache entities
            service.getL1Cache().getResource().asMap().forEach((uuid, data) -> EntityDataPersistenceTask.of(lockSettings, service, uuid).start());

            // Then, perform bulk persistence operation for all entities in L2 cache
            EntityDataPersistenceTask.of(lockSettings, service, limit).start();
        }, delay, period);
    }
} 