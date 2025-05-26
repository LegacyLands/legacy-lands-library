package net.legacy.library.commons.task;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.function.BiFunction;

/**
 * Builder for constructing TaskChain instances with fluent API.
 * Provides methods to define execution modes and build task chains.
 *
 * <p>Tasks execute immediately when {@link ExecutionBuilder#execute(Object)} or
 * {@link ExecutionBuilder#run(Object)} is called, not when {@link #build()} is called.
 *
 * @author qwq-dev
 * @since 2025-05-26 14:41
 */
@Getter
public class TaskChainBuilder {
    private final List<CompletableFuture<Object>> futures = new ArrayList<>();
    private final List<Object> modeResults = new ArrayList<>();
    private final Map<String, Integer> nameToIndexMap = new HashMap<>();
    private TaskInterface<?> taskInterface;

    /**
     * Creates a new TaskChainBuilder with default TaskInterface.
     * The default TaskInterface uses the async scheduler for task execution.
     */
    public TaskChainBuilder() {
        this.taskInterface = new TaskInterface<>() {
        };
    }

    /**
     * Creates a new TaskChainBuilder with the specified TaskInterface.
     *
     * @param taskInterface TaskInterface to use for task execution,
     *                      or null to use the default implementation
     */
    public TaskChainBuilder(TaskInterface<?> taskInterface) {
        this.taskInterface = taskInterface != null ? taskInterface : new TaskInterface<>() {
        };
    }

    /**
     * Defines the execution mode for the next task.
     *
     * <p>The mode function determines how the task will be executed (sync, async, virtual thread, etc.).
     * This method only defines the execution strategy without executing anything.
     *
     * @param executionMode execution mode function that takes TaskInterface and task parameter,
     *                      then returns the execution result (value, {@link Future}, {@link CompletableFuture}, etc.)
     * @param <T>           task parameter type
     * @param <R>           result type returned by the mode function
     * @return {@link ExecutionBuilder} for running the actual task with the defined mode
     */
    public <T, R> ExecutionBuilder<T, R> withMode(BiFunction<TaskInterface<?>, T, R> executionMode) {
        return new ExecutionBuilder<>(this, executionMode);
    }

    /**
     * Sets a custom TaskInterface for this chain.
     *
     * <p>Allows customization of how tasks are scheduled and executed.
     *
     * @param taskInterface TaskInterface to use for task execution,
     *                      or null to use the default implementation
     * @return this TaskChainBuilder for method chaining
     */
    public TaskChainBuilder withTaskInterface(TaskInterface<?> taskInterface) {
        this.taskInterface = taskInterface != null ? taskInterface : new TaskInterface<>() {
        };
        return this;
    }

    /**
     * Builds the TaskChain with all configured tasks and execution modes.
     *
     * <p>All tasks will have been started immediately when {@link ExecutionBuilder#execute(Object)}
     * or {@link ExecutionBuilder#run(Object)} was called.
     *
     * @return constructed {@link TaskChain} instance
     * @throws IllegalStateException if no tasks have been added to the chain
     */
    public TaskChain build() {
        if (futures.isEmpty()) {
            throw new IllegalStateException("TaskChain must contain at least one task");
        }
        return new TaskChain(futures, modeResults, nameToIndexMap, taskInterface);
    }

    /**
     * Executes the task with the specified mode and adds the result to the chain.
     *
     * <p>This method is called immediately when {@link ExecutionBuilder#execute(Object)}
     * or {@link ExecutionBuilder#run(Object)} is invoked.
     *
     * @param mode execution mode function
     * @param task task parameter to execute
     * @param name optional name for the task (null if unnamed)
     * @param <T>  task parameter type
     * @param <R>  result type returned by the mode function
     */
    public <T, R> void executeAndAddFuture(BiFunction<TaskInterface<?>, T, R> mode, T task, String name) {
        try {
            R result = mode.apply(taskInterface, task);
            modeResults.add(result);
            CompletableFuture<Object> future = convertToCompletableFuture(result);
            futures.add(future);

            // Store name mapping if provided
            if (name != null && !name.trim().isEmpty()) {
                nameToIndexMap.put(name, futures.size() - 1);
            }
        } catch (Exception exception) {
            modeResults.add(null);
            CompletableFuture<Object> failedFuture = CompletableFuture.failedFuture(exception);
            futures.add(failedFuture);

            // Store name mapping even for failed tasks
            if (name != null && !name.trim().isEmpty()) {
                nameToIndexMap.put(name, futures.size() - 1);
            }
        }
    }

    /**
     * Converts various result types to CompletableFuture for unified handling.
     *
     * <p>Handles {@link CompletableFuture}, {@link Future}, and synchronous results.
     *
     * @param result result object to convert
     * @return {@link CompletableFuture} wrapping the result
     */
    @SuppressWarnings("unchecked")
    private CompletableFuture<Object> convertToCompletableFuture(Object result) {
        if (result instanceof CompletableFuture) {
            return (CompletableFuture<Object>) result;
        } else if (result instanceof Future<?> future) {
            return CompletableFuture.supplyAsync(() -> {
                try {
                    return future.get();
                } catch (Exception exception) {
                    throw new RuntimeException(exception);
                }
            });
        } else {
            return CompletableFuture.completedFuture(result);
        }
    }
} 