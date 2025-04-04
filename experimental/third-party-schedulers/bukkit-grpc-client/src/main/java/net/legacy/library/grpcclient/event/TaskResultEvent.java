package net.legacy.library.grpcclient.event;

import lombok.Getter;
import net.legacy.library.grpcclient.task.GRPCTaskSchedulerClient;
import org.apache.commons.lang3.Validate;
import org.bukkit.event.Event;
import org.bukkit.event.HandlerList;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Represents the result of a task processed via the GRPCTaskSchedulerClient.
 *
 * <p>This event is fired for both synchronous and asynchronous tasks upon completion or definitive failure.
 * Use {@link #isSuccess()} to check the outcome.
 *
 * <p>The triggering of this event does not happen on the {@code Bukkit} thread,
 * but rather on the thread specified by {@link GRPCTaskSchedulerClient}. Please be aware of thread safety.
 *
 * @author qwq-dev
 * @since 2025-4-4 20:42
 */
@Getter
public class TaskResultEvent extends Event {

    private static final HandlerList handlers = new HandlerList();

    private final String taskId;
    private final String method;
    private final boolean success;
    private final @Nullable String result;
    private final @Nullable Throwable exception;

    /**
     * Constructs a new TaskResultEvent.
     *
     * @param taskId    the unique ID of the task
     * @param method    the method name called on the server
     * @param success   true if the task completed successfully, false otherwise
     * @param result    the result string from the server (potentially null on failure)
     * @param exception the exception that occurred if the task failed (successfully null on failure)
     */
    public TaskResultEvent(@NotNull String taskId, @NotNull String method, boolean success, @Nullable String result, @Nullable Throwable exception) {
        Validate.notEmpty(taskId, "Task ID cannot be empty.");
        Validate.notEmpty(method, "Method name cannot be empty.");
        Validate.isTrue(!success == (exception != null), "Exception must be present if and only if success is false.");

        this.taskId = taskId;
        this.method = method;
        this.success = success;
        this.result = result;
        this.exception = exception;
    }

    public TaskResultEvent(@NotNull String taskId, @NotNull String method, @NotNull String result) {
        this(taskId, method, true, result, null);
    }

    public TaskResultEvent(@NotNull String taskId, @NotNull String method, @NotNull Throwable exception) {
        this(taskId, method, false, null, exception);
    }

    @NotNull
    @Override
    public HandlerList getHandlers() {
        return handlers;
    }

    @NotNull
    public static HandlerList getHandlerList() {
        return handlers;
    }
}