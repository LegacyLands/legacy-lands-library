package net.legacy.library.grpcclient.task;

import taskscheduler.TaskSchedulerOuterClass.TaskResponse;

/**
 * Lifecycle status of a task as reported by a scheduler node.
 *
 * <p>This type decouples the public API from the generated Protobuf classes, whose Protobuf runtime
 * is relocated inside the plugin jar.
 *
 * @author qwq-dev
 * @since 2026-10-04 12:51
 */
public enum TaskStatus {

    /**
     * Accepted, waiting for dependencies or a free execution slot.
     */
    PENDING,

    /**
     * Currently executing.
     */
    RUNNING,

    /**
     * Finished successfully.
     */
    SUCCESS,

    /**
     * Finished with an error, including failed dependencies and timeouts.
     */
    FAILED,

    /**
     * Cancelled before it finished.
     */
    CANCELLED,

    /**
     * Unknown to the queried node, never submitted there or already evicted.
     */
    NOT_FOUND;

    /**
     * Converts a Protobuf status, mapping unrecognized values to {@link #NOT_FOUND}.
     *
     * @param status the status received from the server
     * @return the corresponding task status
     */
    static TaskStatus fromProto(TaskResponse.Status status) {
        return switch (status) {
            case PENDING -> PENDING;
            case RUNNING -> RUNNING;
            case SUCCESS -> SUCCESS;
            case FAILED -> FAILED;
            case CANCELLED -> CANCELLED;
            case NOT_FOUND, UNRECOGNIZED -> NOT_FOUND;
        };
    }

    /**
     * Checks whether this status is final.
     *
     * @return {@code true} for {@link #SUCCESS}, {@link #FAILED} and {@link #CANCELLED}
     */
    public boolean isTerminal() {
        return this == SUCCESS || this == FAILED || this == CANCELLED;
    }

}
