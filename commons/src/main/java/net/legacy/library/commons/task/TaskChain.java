package net.legacy.library.commons.task;

import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

/**
 * Provides a fluent API for chaining and executing tasks with comprehensive result management.
 *
 * <p>Supports multiple execution modes including virtual threads, scheduled tasks, and asynchronous operations
 * while maintaining both indexed and named access to results for each step in the chain.
 *
 * <p>Tasks execute immediately upon calling {@link ExecutionBuilder#execute(Object)} or {@link ExecutionBuilder#run(Object)}, not during
 * {@link TaskChainBuilder#build()}. This ensures concurrent execution rather than sequential processing.
 *
 * @author qwq-dev
 * @since 2025-05-26 13:47
 */
@Getter
public class TaskChain {
    private final List<Object> results;
    private final TaskInterface<?> taskInterface;
    private final List<CompletableFuture<Object>> futures;
    private final List<Object> modeResults;
    private final Map<String, Integer> nameToIndexMap;

    public TaskChain(List<CompletableFuture<Object>> futures, List<Object> modeResults,
                     Map<String, Integer> nameToIndexMap, TaskInterface<?> taskInterface) {
        this.futures = new ArrayList<>(futures);
        this.modeResults = new ArrayList<>(modeResults);
        this.nameToIndexMap = new HashMap<>(nameToIndexMap);
        this.taskInterface = taskInterface;
        this.results = new ArrayList<>();
    }

    /**
     * Creates a new TaskChain builder with default TaskInterface.
     *
     * <p>The default TaskInterface uses {@link io.fairyproject.mc.scheduler.MCSchedulers#getAsyncScheduler()} for task execution.
     *
     * @return new {@link TaskChainBuilder} instance
     */
    public static TaskChainBuilder builder() {
        return new TaskChainBuilder();
    }

    /**
     * Creates a new TaskChain builder with the specified TaskInterface.
     *
     * <p>Allows customization of task execution behavior within the chain.
     *
     * @param taskInterface the TaskInterface to use for task execution,
     *                      or null to use the default implementation
     * @return new {@link TaskChainBuilder} instance
     */
    public static TaskChainBuilder builder(TaskInterface<?> taskInterface) {
        return new TaskChainBuilder(taskInterface);
    }

    /**
     * Waits for all tasks in the chain to complete.
     *
     * <p>Since tasks execute immediately when {@link ExecutionBuilder#execute(Object)} or
     * {@link ExecutionBuilder#run(Object)} is called, this method waits for all previously
     * started tasks to finish.
     *
     * @return {@link CompletableFuture} that completes when all tasks finish
     */
    public CompletableFuture<Void> join() {
        CompletableFuture<?>[] futureArray = futures.toArray(new CompletableFuture[0]);
        return CompletableFuture.allOf(futureArray);
    }

    /**
     * Retrieves the final execution result of the task at the specified index.
     *
     * <p>Blocks until the task completes and returns the actual result value,
     * not wrapper objects ({@link Future}, {@link CompletableFuture}, etc.).
     *
     * @param index zero-based index of the task in execution order
     * @param <T>   expected result type
     * @return final result of task execution
     * @throws IndexOutOfBoundsException if index is negative or >= chain size
     * @throws RuntimeException          if task failed, was interrupted, or completed exceptionally
     */
    @SuppressWarnings("unchecked")
    public <T> T getResult(int index) {
        if (index < 0 || index >= futures.size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index + ", size: " + futures.size());
        }

        try {
            return (T) futures.get(index).join();
        } catch (Exception exception) {
            throw new RuntimeException("Failed to get result at index " + index, exception);
        }
    }

    /**
     * Retrieves the final execution result of the task at the specified index with timeout.
     *
     * <p>Blocks for up to the specified duration waiting for task completion.
     *
     * @param index   zero-based index of the task in execution order
     * @param timeout maximum time to wait for task completion
     * @param unit    time unit of the timeout argument
     * @param <T>     expected result type
     * @return final result of task execution
     * @throws IndexOutOfBoundsException if index is negative or >= chain size
     * @throws RuntimeException          if task failed, was interrupted, timed out,
     *                                   or completed exceptionally
     */
    @SuppressWarnings("unchecked")
    public <T> T getResult(int index, long timeout, TimeUnit unit) {
        if (index < 0 || index >= futures.size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index + ", size: " + futures.size());
        }

        try {
            return (T) futures.get(index).get(timeout, unit);
        } catch (Exception exception) {
            throw new RuntimeException("Failed to get result at index " + index, exception);
        }
    }

    /**
     * Retrieves the final execution result of the task with the specified name.
     *
     * <p>Blocks until the named task completes and returns the actual result value.
     *
     * @param name task name as specified when calling {@link ExecutionBuilder#execute(String, Object)}
     *             or {@link ExecutionBuilder#run(String, Object)}
     * @param <T>  expected result type
     * @return final result of task execution
     * @throws IllegalArgumentException if no task with the given name exists
     * @throws RuntimeException         if task failed, was interrupted, or completed exceptionally
     */
    public <T> T getResult(String name) {
        Integer index = nameToIndexMap.get(name);
        if (index == null) {
            throw new IllegalArgumentException("No task found with name: " + name);
        }
        return getResult(index);
    }

    /**
     * Retrieves the final execution result of the task with the specified name with timeout.
     *
     * <p>Blocks for up to the specified duration waiting for the named task to complete.
     *
     * @param name    task name as specified when calling {@link ExecutionBuilder#execute(String, Object)}
     *                or {@link ExecutionBuilder#run(String, Object)}
     * @param timeout maximum time to wait for task completion
     * @param unit    time unit of the timeout argument
     * @param <T>     expected result type
     * @return final result of task execution
     * @throws IllegalArgumentException if no task with the given name exists
     * @throws RuntimeException         if task failed, was interrupted, timed out,
     *                                  or completed exceptionally
     */
    public <T> T getResult(String name, long timeout, TimeUnit unit) {
        Integer index = nameToIndexMap.get(name);
        if (index == null) {
            throw new IllegalArgumentException("No task found with name: " + name);
        }
        return getResult(index, timeout, unit);
    }

    /**
     * Retrieves the original object returned by the mode function at the specified index.
     *
     * <p>Returns the raw object before any conversion (e.g., {@link Future}, {@link CompletableFuture},
     * VirtualThreadScheduledFuture, ScheduledTask). Enables advanced control over execution objects.
     *
     * @param index zero-based index of the task in execution order
     * @param <T>   expected mode result type
     * @return original object returned by the mode function
     * @throws IndexOutOfBoundsException if index is negative or >= chain size
     */
    @SuppressWarnings("unchecked")
    public <T> T getModeResult(int index) {
        if (index < 0 || index >= modeResults.size()) {
            throw new IndexOutOfBoundsException("Invalid index: " + index + ", size: " + modeResults.size());
        }
        return (T) modeResults.get(index);
    }

    /**
     * Retrieves the original object returned by the mode function for the specified named task.
     *
     * <p>Returns the raw object before any conversion.
     *
     * @param name task name as specified when calling {@link ExecutionBuilder#execute(String, Object)}
     *             or {@link ExecutionBuilder#run(String, Object)}
     * @param <T>  expected mode result type
     * @return original object returned by the mode function
     * @throws IllegalArgumentException if no task with the given name exists
     */
    public <T> T getModeResult(String name) {
        Integer index = nameToIndexMap.get(name);
        if (index == null) {
            throw new IllegalArgumentException("No task found with name: " + name);
        }
        return getModeResult(index);
    }

    /**
     * Retrieves all original objects returned by the mode functions.
     *
     * <p>Returns raw objects in execution order, which may include various types
     * like {@link Future}, {@link CompletableFuture}, ScheduledTask, etc.
     *
     * @return immutable copy of the list containing all original mode results
     */
    public List<Object> getAllModeResults() {
        return new ArrayList<>(modeResults);
    }

    /**
     * Retrieves all {@link CompletableFuture}s representing the tasks in the chain.
     *
     * <p>These futures are the normalized representation of all tasks, regardless of their
     * original return type from mode functions. Enables unified handling of task completion,
     * exception handling, and result retrieval.
     *
     * @return immutable copy of the list containing all task futures
     */
    public List<CompletableFuture<Object>> getAllFutures() {
        return new ArrayList<>(futures);
    }

    /**
     * Returns the total number of tasks in the chain.
     * Includes all tasks added via {@link ExecutionBuilder#execute(Object)}
     * or {@link ExecutionBuilder#run(Object)} calls.
     *
     * @return number of tasks in the chain
     */
    public int size() {
        return futures.size();
    }

    /**
     * Returns a map of task names to their corresponding indices.
     *
     * <p>Only includes tasks assigned names when calling {@link ExecutionBuilder#execute(String, Object)}
     * or {@link ExecutionBuilder#run(String, Object)}.
     *
     * @return immutable copy of the name-to-index mapping
     */
    public Map<String, Integer> getNameToIndexMap() {
        return new HashMap<>(nameToIndexMap);
    }

    /**
     * Checks if a task with the specified name exists in the chain.
     *
     * @param name task name to check for
     * @return true if a task with the given name exists, false otherwise
     */
    public boolean hasTask(String name) {
        return nameToIndexMap.containsKey(name);
    }
}
