package net.legacy.library.commons.task;

import lombok.RequiredArgsConstructor;

/**
 * Builder for continuing the task chain or finalizing TaskChain construction.
 *
 * <p>Returned after task execution to enable adding more tasks or building the final TaskChain.
 *
 * @author qwq-dev
 * @since 2025-05-26 14:41
 */
@RequiredArgsConstructor
public class ChainContinuation {
    private final TaskChainBuilder builder;

    /**
     * Continues the chain to add another task.
     *
     * <p>Since all previous tasks have already been executed immediately when
     * {@link ExecutionBuilder#execute(Object)} was called, this returns the builder
     * to configure the next task's execution mode.
     *
     * @return {@link TaskChainBuilder} for defining the next task's execution mode
     */
    public TaskChainBuilder andThen() {
        return builder;
    }

    /**
     * Continues the chain to add another task.
     *
     * <p>Alias for {@link #andThen()} to maintain familiar fluent API style.
     *
     * @return {@link TaskChainBuilder} for defining the next task's execution mode
     */
    public TaskChainBuilder then() {
        return builder;
    }

    /**
     * Builds the final TaskChain with all configured and executed tasks.
     *
     * <p>All tasks will have been started immediately when their respective
     * {@link ExecutionBuilder#execute(Object)} calls were made.
     *
     * @return constructed {@link TaskChain} instance
     * @throws IllegalStateException if no tasks have been added
     */
    public TaskChain build() {
        return builder.build();
    }

    /**
     * Finalizes and builds the TaskChain.
     *
     * <p>Alias for {@link #build()} with more descriptive naming.
     *
     * @return constructed {@link TaskChain} instance
     */
    public TaskChain complete() {
        return build();
    }
} 