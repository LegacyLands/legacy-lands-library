package net.legacy.library.grpcclient.task;

import lombok.Builder;
import lombok.Singular;
import lombok.Value;

import java.util.Arrays;
import java.util.List;

/**
 * Immutable description of a task to be submitted to a scheduler node.
 *
 * <p>Dependencies are referenced by task id and may be executed on any node of the cluster.
 * The executing node locates them on its peers and waits until they finish, so a dependency
 * may even be submitted after the task that depends on it, as long as it arrives within
 * {@link #getDependencyTimeoutMs()}.
 *
 * <p>Task ids must be unique across the cluster. Submitting a known id again is idempotent:
 * the scheduler returns the existing task instead of executing it a second time.
 *
 * @author qwq-dev
 * @since 2026-10-04 01:17
 */
@Value
@Builder(toBuilder = true)
public class TaskSubmission {

    /**
     * The cluster-wide unique id of the task.
     */
    String taskId;

    /**
     * The name of the task function registered on the scheduler.
     */
    String method;

    /**
     * The arguments passed to the task function, converted by {@link ProtoConversionUtil}.
     */
    @Singular
    List<Object> args;

    /**
     * The ids of the tasks that must succeed before this task starts.
     */
    @Singular
    List<String> dependencies;

    /**
     * The maximum time to wait for all dependencies in milliseconds, {@code 0} uses the server default.
     */
    long dependencyTimeoutMs;

    /**
     * Whether to prepend the dependency results (as strings, in dependency order) to {@link #getArgs()}.
     */
    boolean injectDependencyResults;

    /**
     * The maximum execution time of the task itself in milliseconds, {@code 0} means unlimited.
     */
    long executionTimeoutMs;

    /**
     * Whether the receiving node must execute the task itself instead of forwarding it to a peer
     * when the method is not registered locally.
     */
    boolean disableForwarding;

    /**
     * Creates a submission without dependencies.
     *
     * @param taskId the cluster-wide unique id of the task
     * @param method the name of the task function registered on the scheduler
     * @param args   the arguments passed to the task function, {@code null} elements are allowed
     * @return the created submission
     */
    public static TaskSubmission of(String taskId, String method, Object... args) {
        return TaskSubmission.builder()
                .taskId(taskId)
                .method(method)
                .args(Arrays.asList(args))
                .build();
    }

}
