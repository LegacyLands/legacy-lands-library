package net.legacy.library.grpcclient.task;

import lombok.Value;
import taskscheduler.TaskSchedulerOuterClass.ResultResponse;
import taskscheduler.TaskSchedulerOuterClass.TaskResponse;

/**
 * Snapshot of a task state reported by a scheduler node.
 *
 * @author qwq-dev
 * @since 2026-10-04 01:17
 */
@Value
public class TaskResult {

    /**
     * The id of the task.
     */
    String taskId;

    /**
     * The status of the task at the time of the query.
     */
    TaskStatus status;

    /**
     * The task output on success, the error message on failure, otherwise empty.
     */
    String result;

    /**
     * The id of the node that owns (executes) the task, empty if unknown.
     */
    String nodeId;

    static TaskResult from(TaskResponse response) {
        return new TaskResult(response.getTaskId(), TaskStatus.fromProto(response.getStatus()),
                response.getResult(), response.getNodeId());
    }

    static TaskResult from(String taskId, ResultResponse response) {
        return new TaskResult(taskId, TaskStatus.fromProto(response.getStatus()),
                response.getResult(), response.getNodeId());
    }

    /**
     * Checks whether the task finished successfully.
     *
     * @return {@code true} if the status is {@code SUCCESS}
     */
    public boolean isSuccess() {
        return status == TaskStatus.SUCCESS;
    }

    /**
     * Checks whether the task reached a final state.
     *
     * @return {@code true} if the status is {@code SUCCESS}, {@code FAILED} or {@code CANCELLED}
     */
    public boolean isTerminal() {
        return status.isTerminal();
    }

    /**
     * Checks whether the queried node does not know the task.
     *
     * @return {@code true} if the status is {@code NOT_FOUND}
     */
    public boolean isNotFound() {
        return status == TaskStatus.NOT_FOUND;
    }

}
