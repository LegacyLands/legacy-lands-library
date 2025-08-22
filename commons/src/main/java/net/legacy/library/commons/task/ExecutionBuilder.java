package net.legacy.library.commons.task;

import lombok.RequiredArgsConstructor;

import java.util.function.BiFunction;

/**
 * Builder for executing a task with a previously defined execution mode.
 *
 * <p>Returned by {@link TaskChainBuilder#withMode(BiFunction)} to specify
 * the actual task parameter and immediately execute it.
 *
 * @param <T> task parameter type
 * @param <R> result type returned by the mode function
 * @author qwq-dev
 * @since 2025-05-26 14:41
 */
@RequiredArgsConstructor
public class ExecutionBuilder<T, R> {

    private final TaskChainBuilder builder;
    private final BiFunction<TaskInterface<?>, T, R> executionMode;

    /**
     * Executes the task with the previously defined execution mode.
     * Task executes immediately when this method is called.
     * Creates an unnamed task accessible only by index.
     *
     * @param taskData task parameter to execute
     * @return {@link ChainContinuation} for continuing the chain or building the TaskChain
     */
    public ChainContinuation execute(T taskData) {
        builder.executeAndAddFuture(executionMode, taskData, null);
        return new ChainContinuation(builder);
    }

    /**
     * Executes the named task with the previously defined execution mode.
     * Task executes immediately when this method is called.
     * Named tasks are accessible both by index and by name.
     *
     * @param taskName name to assign to this task for later retrieval
     * @param taskData task parameter to execute
     * @return {@link ChainContinuation} for continuing the chain or building the TaskChain
     */
    public ChainContinuation execute(String taskName, T taskData) {
        builder.executeAndAddFuture(executionMode, taskData, taskName);
        return new ChainContinuation(builder);
    }

    /**
     * Runs the task with the previously defined execution mode.
     * Alias for {@link #execute(Object)} with more intuitive naming.
     *
     * @param taskData task parameter to run
     * @return {@link ChainContinuation} for continuing the chain or building the TaskChain
     */
    public ChainContinuation run(T taskData) {
        return execute(taskData);
    }

    /**
     * Runs the named task with the previously defined execution mode.
     * Alias for {@link #execute(String, Object)} with more intuitive naming.
     *
     * @param taskName name to assign to this task for later retrieval
     * @param taskData task parameter to run
     * @return {@link ChainContinuation} for continuing the chain or building the TaskChain
     */
    public ChainContinuation run(String taskName, T taskData) {
        return execute(taskName, taskData);
    }

}