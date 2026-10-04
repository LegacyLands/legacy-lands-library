package net.legacy.library.grpcclient.task;

import com.google.protobuf.Any;
import io.fairyproject.log.Log;
import io.grpc.ManagedChannel;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.netty.shaded.io.grpc.netty.NettyChannelBuilder;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContext;
import io.grpc.netty.shaded.io.netty.handler.ssl.SslContextBuilder;
import lombok.Getter;
import net.legacy.library.commons.task.VirtualThreadExecutors;
import net.legacy.library.grpcclient.event.TaskResultEvent;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import taskscheduler.TaskSchedulerGrpc;
import taskscheduler.TaskSchedulerOuterClass.CancelRequest;
import taskscheduler.TaskSchedulerOuterClass.CancelResponse;
import taskscheduler.TaskSchedulerOuterClass.NodeInfoRequest;
import taskscheduler.TaskSchedulerOuterClass.ResultRequest;
import taskscheduler.TaskSchedulerOuterClass.TaskRequest;
import taskscheduler.TaskSchedulerOuterClass.TaskResponse;
import taskscheduler.TaskSchedulerOuterClass.WaitResultRequest;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * A client for interacting with a single Task Scheduler node via gRPC.
 *
 * <p>This client submits tasks either blocking ({@link #submit(TaskSubmission)}) or asynchronously
 * ({@link #submitAsync(TaskSubmission)}), queries and cancels tasks, and describes the node.
 * Scheduler nodes form a cluster: a task submitted here may depend on tasks executed on other nodes,
 * and tasks whose method is not available on this node are forwarded to a capable peer automatically.
 * Use {@link ClusterTaskSchedulerClient} to address several nodes at once.
 *
 * <p>Transient gRPC failures ({@code UNAVAILABLE}, {@code RESOURCE_EXHAUSTED}) are retried with exponential
 * backoff. Retrying submissions is safe because the scheduler treats task ids idempotently.
 * Every finished submission fires a {@link TaskResultEvent}.
 *
 * <p>This class is thread-safe. Remember to call {@link #shutdown()} when the client is no longer needed.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@Getter
public class GRPCTaskSchedulerClient {

    /**
     * Default dependency timeout of the scheduler, used to size deadlines when a submission does not specify one.
     */
    public static final long DEFAULT_SERVER_DEPENDENCY_TIMEOUT_MS = 30000;

    /**
     * Maximum duration of a single {@code WaitResult} long-poll issued by {@link #submitAsync(TaskSubmission)}.
     */
    private static final long WAIT_WINDOW_MS = 10000;

    private final String host;
    private final int port;
    private final long timeoutMs;
    private final int maxRetries;
    private final boolean useTls;
    private final String caCertPath;

    private final ManagedChannel channel;
    private final ExecutorService grpcExecutor;
    private final TaskSchedulerGrpc.TaskSchedulerBlockingStub blockingStub;

    /**
     * Constructs a new {@code GRPCTaskSchedulerClient} with explicit TLS configuration.
     *
     * @param host         the hostname or IP address of the remote task scheduler server
     * @param port         the port number of the remote task scheduler server
     * @param timeoutMs    timeout in milliseconds for individual gRPC calls, blocking submissions additionally
     *                     receive the dependency and execution timeouts of the submission
     * @param maxRetries   maximum number of retries for potentially transient gRPC errors
     * @param grpcExecutor the {@link ExecutorService} to use for asynchronous operations, shut down by {@link #shutdown()}
     * @param useTls       whether to use TLS for the connection
     * @param caCertPath   the path to the CA certificate file (e.g., ca.crt). Required if {@code useTls} is true
     * @throws TaskSchedulerException if TLS is enabled but configuring the SSL context fails
     */
    public GRPCTaskSchedulerClient(String host, int port, long timeoutMs, int maxRetries, ExecutorService grpcExecutor, boolean useTls, String caCertPath) throws TaskSchedulerException {
        Validate.notBlank(host, "Host cannot be blank.");
        Validate.isTrue(port > 0 && port <= 65535, "Port must be between 1 and 65535: %d.", port);
        Validate.isTrue(timeoutMs > 0, "Timeout must be positive: %d.", timeoutMs);
        Validate.isTrue(maxRetries >= 0, "Max retries must be non-negative: %d.", maxRetries);
        Validate.notNull(grpcExecutor, "ExecutorService cannot be null.");
        if (useTls) {
            Validate.notBlank(caCertPath, "CA certificate path cannot be blank when TLS is enabled.");
        }

        this.host = host;
        this.port = port;
        this.timeoutMs = timeoutMs;
        this.maxRetries = maxRetries;
        this.grpcExecutor = grpcExecutor;
        this.useTls = useTls;
        this.caCertPath = caCertPath;

        NettyChannelBuilder channelBuilder = NettyChannelBuilder.forAddress(this.host, this.port);

        if (useTls) {
            try {
                File caCertFile = new File(caCertPath);
                if (!caCertFile.exists() || !caCertFile.isFile()) {
                    throw new TaskSchedulerException("CA certificate file not found or is not a file: " + caCertPath);
                }
                SslContext sslContext = SslContextBuilder.forClient()
                        .trustManager(caCertFile)
                        .build();
                channelBuilder.sslContext(sslContext);
            } catch (SSLException exception) {
                Log.error("Failed to create SSL context for gRPC TLS", exception);
                throw new TaskSchedulerException("Failed to configure TLS for gRPC channel", exception);
            }
        } else {
            Log.warn("gRPC channel to %s:%d is configured to use plaintext (no TLS).", host, port);
            channelBuilder.usePlaintext();
        }

        this.channel = channelBuilder.build();
        this.blockingStub = TaskSchedulerGrpc.newBlockingStub(channel);
    }

    /**
     * Constructs a new {@code GRPCTaskSchedulerClient} without TLS (insecure).
     *
     * @param host         the hostname or IP address of the remote task scheduler server
     * @param port         the port number of the remote task scheduler server
     * @param timeoutMs    timeout in milliseconds for individual gRPC calls
     * @param maxRetries   maximum number of retries for potentially transient gRPC errors
     * @param grpcExecutor the {@link ExecutorService} to use for asynchronous operations
     * @throws TaskSchedulerException never thrown without TLS, declared for constructor compatibility
     */
    public GRPCTaskSchedulerClient(String host, int port, long timeoutMs, int maxRetries, ExecutorService grpcExecutor) throws TaskSchedulerException {
        this(host, port, timeoutMs, maxRetries, grpcExecutor, false, null);
    }

    /**
     * Constructs a new {@code GRPCTaskSchedulerClient} without TLS (insecure),
     * using a dedicated virtual thread per task executor.
     *
     * @param host       the hostname or IP address of the remote task scheduler server
     * @param port       the port number of the remote task scheduler server
     * @param timeoutMs  timeout in milliseconds for individual gRPC calls
     * @param maxRetries maximum number of retries for potentially transient gRPC errors
     * @throws TaskSchedulerException never thrown without TLS, declared for constructor compatibility
     */
    public GRPCTaskSchedulerClient(String host, int port, long timeoutMs, int maxRetries) throws TaskSchedulerException {
        this(host, port, timeoutMs, maxRetries, VirtualThreadExecutors.createEphemeralExecutor());
    }

    /**
     * Shuts down the gRPC channel and the {@link ExecutorService} used by this client.
     *
     * <p>This method should be called when the client instance is no longer needed to release
     * network and thread resources gracefully. It attempts a graceful shutdown with a timeout
     * before forcing termination.
     */
    public void shutdown() {
        if (!grpcExecutor.isShutdown()) {
            grpcExecutor.shutdown();
            try {
                if (!grpcExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.warn("ExecutorService did not terminate within 5 seconds, forcing shutdown...");
                    grpcExecutor.shutdownNow();
                }
            } catch (InterruptedException exception) {
                Log.warn("Interrupted while waiting for executor shutdown, forcing now.", exception);
                grpcExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (!channel.isShutdown()) {
            try {
                channel.shutdown().awaitTermination(5, TimeUnit.SECONDS);
            } catch (InterruptedException exception) {
                Log.warn("Interrupted while waiting for gRPC channel shutdown, forcing now.", exception);
                channel.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
    }

    /**
     * Submits a task without dependencies and blocks until it finishes.
     *
     * @param taskId a cluster-wide unique identifier for this task. Must not be {@code null} or empty
     * @param method the name of the task function to execute on the server side
     * @param args   variable arguments to pass to the remote task function
     * @return the result returned by the remote task function
     * @throws TaskSchedulerException if the submission fails after retries or the task fails on the server
     * @see #submit(TaskSubmission)
     */
    public String submitTaskBlocking(String taskId, String method, Object... args) throws TaskSchedulerException {
        return submit(TaskSubmission.of(taskId, method, args)).getResult();
    }

    /**
     * Submits a task without dependencies asynchronously.
     *
     * @param taskId a cluster-wide unique identifier for this task. Must not be {@code null} or empty
     * @param method the name of the task function to execute on the server side
     * @param args   variable arguments to pass to the remote task function
     * @return a {@link CompletableFuture} completed with the task result, or exceptionally with a
     * {@link TaskSchedulerException} if the task fails
     * @see #submitAsync(TaskSubmission)
     */
    public CompletableFuture<String> submitTaskAsync(String taskId, String method, Object... args) {
        return submitAsync(TaskSubmission.of(taskId, method, args)).thenApply(TaskResult::getResult);
    }

    /**
     * Submits a task and blocks until it finishes, including the time spent waiting for its dependencies.
     *
     * <p>The call deadline is {@link #getTimeoutMs()} plus the dependency timeout (the server default
     * {@value #DEFAULT_SERVER_DEPENDENCY_TIMEOUT_MS}ms if unspecified and dependencies exist) plus the execution timeout.
     * Fires a {@link TaskResultEvent} once the outcome is known.
     *
     * @param submission the task to submit
     * @return the successful result of the task
     * @throws TaskSchedulerException   if the submission fails after retries or the task fails, is cancelled or times out;
     *                                  {@link TaskSchedulerException#getStatusCode()} carries the server status code
     * @throws NullPointerException     if {@code submission}, its task id or method is {@code null}
     * @throws IllegalArgumentException if the task id or method is empty
     */
    public TaskResult submit(TaskSubmission submission) throws TaskSchedulerException {
        TaskRequest request = buildRequest(submission, false);
        long deadlineMs = timeoutMs + resolveDependencyTimeout(submission) + submission.getExecutionTimeoutMs();

        try {
            TaskResponse response = executeWithRetry(() -> blockingStub
                    .withDeadlineAfter(deadlineMs, TimeUnit.MILLISECONDS)
                    .submitTask(request), "SubmitTask(taskId=" + submission.getTaskId() + ")");
            TaskResult result = TaskResult.from(response);
            fireResultEvent(submission, result.getResult(), null);
            return result;
        } catch (TaskSchedulerException exception) {
            fireResultEvent(submission, null, exception);
            throw exception;
        }
    }

    /**
     * Submits a task without waiting for it, returning the state accepted by the server (usually {@code PENDING}).
     *
     * <p>Use {@link #getResult(String)} or {@link #waitResult(String, long)} to follow the task afterward.
     * No {@link TaskResultEvent} is fired by this method.
     *
     * @param submission the task to submit
     * @return the state of the task right after submission
     * @throws TaskSchedulerException if the submission is rejected, e.g. the method exists on no node
     */
    public TaskResult submitDetached(TaskSubmission submission) throws TaskSchedulerException {
        TaskRequest request = buildRequest(submission, true);
        TaskResponse response = executeWithRetry(() -> blockingStub
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .submitTask(request), "SubmitTask(detached, taskId=" + submission.getTaskId() + ")");
        return TaskResult.from(response);
    }

    /**
     * Submits a task asynchronously on the configured executor.
     *
     * <p>The task is submitted detached and then followed with {@code WaitResult} long-polls, so no single
     * gRPC call has to outlive slow dependencies or executions. Fires a {@link TaskResultEvent} on completion.
     *
     * @param submission the task to submit
     * @return a {@link CompletableFuture} completed with the successful result, or exceptionally with a
     * {@link TaskSchedulerException} (wrapped in {@link CompletionException}) if the task does not succeed
     */
    public CompletableFuture<TaskResult> submitAsync(TaskSubmission submission) {
        try {
            buildRequest(submission, true);
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                TaskResult accepted = submitDetached(submission);
                return accepted.isTerminal() ? requireSuccess(accepted) : awaitTerminal(submission.getTaskId());
            } catch (TaskSchedulerException exception) {
                throw new CompletionException(exception);
            }
        }, grpcExecutor).whenComplete((result, throwable) -> {
            Throwable cause = throwable instanceof CompletionException && throwable.getCause() != null
                    ? throwable.getCause() : throwable;
            fireResultEvent(submission, result == null ? null : result.getResult(), cause);
        });
    }

    /**
     * Queries the current state of a task known by this node without waiting.
     *
     * @param taskId the id of the task
     * @return the task state, with status {@code NOT_FOUND} if this node does not know the task
     * @throws TaskSchedulerException if the query fails after retries
     */
    public TaskResult getResult(String taskId) throws TaskSchedulerException {
        Validate.notEmpty(taskId, "Task ID cannot be null or empty.");
        ResultRequest request = ResultRequest.newBuilder().setTaskId(taskId).build();
        return TaskResult.from(taskId, executeWithRetry(() -> blockingStub
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getResult(request), "GetResult(taskId=" + taskId + ")"));
    }

    /**
     * Waits until a task known by this node finishes or the wait times out.
     *
     * @param taskId     the id of the task
     * @param waitTimeMs the maximum time to wait in milliseconds, capped by the server
     * @return the task state, which is not terminal if the wait timed out, or {@code NOT_FOUND} if unknown
     * @throws TaskSchedulerException if the query fails after retries
     */
    public TaskResult waitResult(String taskId, long waitTimeMs) throws TaskSchedulerException {
        Validate.notEmpty(taskId, "Task ID cannot be null or empty.");
        Validate.isTrue(waitTimeMs > 0, "Wait time must be positive: %d.", waitTimeMs);
        WaitResultRequest request = WaitResultRequest.newBuilder()
                .setTaskId(taskId)
                .setTimeoutMs(waitTimeMs)
                .build();
        return TaskResult.from(taskId, executeWithRetry(() -> blockingStub
                .withDeadlineAfter(waitTimeMs + timeoutMs, TimeUnit.MILLISECONDS)
                .waitResult(request), "WaitResult(taskId=" + taskId + ")"));
    }

    /**
     * Cancels a task that has not finished yet. The task may live on any node of the cluster.
     *
     * <p>Tasks waiting for dependencies or async tasks stop immediately; running sync tasks
     * finish in the background but their result is discarded.
     *
     * @param taskId the id of the task
     * @return {@code true} if this call cancelled the task, {@code false} if it was unknown or already finished
     * @throws TaskSchedulerException if the request fails after retries
     */
    public boolean cancelTask(String taskId) throws TaskSchedulerException {
        Validate.notEmpty(taskId, "Task ID cannot be null or empty.");
        CancelRequest request = CancelRequest.newBuilder().setTaskId(taskId).build();
        CancelResponse response = executeWithRetry(() -> blockingStub
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .cancelTask(request), "CancelTask(taskId=" + taskId + ")");
        return response.getCancelled();
    }

    /**
     * Describes the connected node: its id, registered methods, load and peers.
     *
     * @return the node information
     * @throws TaskSchedulerException if the request fails after retries
     */
    public SchedulerNodeInfo getNodeInfo() throws TaskSchedulerException {
        return SchedulerNodeInfo.fromProto(executeWithRetry(() -> blockingStub
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .getNodeInfo(NodeInfoRequest.getDefaultInstance()), "GetNodeInfo"));
    }

    private TaskRequest buildRequest(TaskSubmission submission, boolean detached) throws TaskSchedulerException {
        Validate.notNull(submission, "Submission cannot be null.");
        Validate.notEmpty(submission.getTaskId(), "Task ID cannot be null or empty.");
        Validate.notEmpty(submission.getMethod(), "Method name cannot be null or empty.");
        Validate.isTrue(submission.getDependencyTimeoutMs() >= 0, "Dependency timeout must be non-negative.");
        Validate.isTrue(submission.getExecutionTimeoutMs() >= 0, "Execution timeout must be non-negative.");

        List<Any> protoArgs;
        try {
            protoArgs = submission.getArgs().stream()
                    .map(ProtoConversionUtil::convertToProtoAny)
                    .toList();
        } catch (Exception exception) {
            throw new TaskSchedulerException("Failed to convert arguments for task: " + submission.getTaskId(), exception);
        }

        return TaskRequest.newBuilder()
                .setTaskId(submission.getTaskId())
                .setMethod(submission.getMethod())
                .addAllArgs(protoArgs)
                .addAllDeps(submission.getDependencies())
                .setDetached(detached)
                .setDependencyTimeoutMs(submission.getDependencyTimeoutMs())
                .setInjectDependencyResults(submission.isInjectDependencyResults())
                .setExecutionTimeoutMs(submission.getExecutionTimeoutMs())
                .setDisableForwarding(submission.isDisableForwarding())
                .build();
    }

    private long resolveDependencyTimeout(TaskSubmission submission) {
        if (submission.getDependencies().isEmpty()) {
            return 0;
        }
        return submission.getDependencyTimeoutMs() > 0
                ? submission.getDependencyTimeoutMs()
                : DEFAULT_SERVER_DEPENDENCY_TIMEOUT_MS;
    }

    private TaskResult awaitTerminal(String taskId) throws TaskSchedulerException {
        while (true) {
            TaskResult result = waitResult(taskId, WAIT_WINDOW_MS);
            if (result.isNotFound()) {
                throw new TaskSchedulerException("Task disappeared from the scheduler: " + taskId,
                        null, TaskStatus.NOT_FOUND, null);
            }
            if (result.isTerminal()) {
                return requireSuccess(result);
            }
        }
    }

    private TaskResult requireSuccess(TaskResult result) throws TaskSchedulerException {
        if (result.isSuccess()) {
            return result;
        }
        throw new TaskSchedulerException(
                "Task " + result.getTaskId() + " ended with " + result.getStatus() + " on node " + result.getNodeId() + ": " + result.getResult(),
                null, result.getStatus(), null);
    }

    private void fireResultEvent(TaskSubmission submission, String result, Throwable throwable) {
        TaskResultEvent event = throwable == null
                ? new TaskResultEvent(submission.getTaskId(), submission.getMethod(), result == null ? "" : result)
                : new TaskResultEvent(submission.getTaskId(), submission.getMethod(), throwable);
        Bukkit.getServer().getPluginManager().callEvent(event);
    }

    /**
     * Executes a gRPC call with retry logic for transient errors.
     *
     * @param <T>             the return type of the gRPC call
     * @param grpcCall        a {@link Callable} representing the gRPC call to execute
     * @param callDescription a description of the call for logging purposes
     * @return the result of the successful gRPC call
     * @throws TaskSchedulerException if the call fails after retries or encounters a non-retryable error,
     *                                carrying the gRPC status code of the last failure
     */
    private <T> T executeWithRetry(Callable<T> grpcCall, String callDescription) throws TaskSchedulerException {
        long backoffMillis = 50;

        for (int attempt = 0; ; attempt++) {
            try {
                return grpcCall.call();
            } catch (StatusRuntimeException exception) {
                Status status = exception.getStatus();
                if (!isRetryable(status) || attempt >= maxRetries) {
                    Log.warn("%s failed with status %s after %d attempt(s): %s",
                            callDescription, status.getCode(), attempt + 1, status.getDescription());
                    throw new TaskSchedulerException(callDescription + " failed: " + status.getDescription(),
                            status.getCode(), null, exception);
                }

                Log.warn("%s failed with retryable status %s (attempt %d/%d), retrying in %dms",
                        callDescription, status.getCode(), attempt + 1, maxRetries + 1, backoffMillis);
                try {
                    TimeUnit.MILLISECONDS.sleep(backoffMillis);
                } catch (InterruptedException interruptedException) {
                    Thread.currentThread().interrupt();
                    throw new TaskSchedulerException("Interrupted during retry backoff for " + callDescription, interruptedException);
                }
                backoffMillis = Math.min(backoffMillis * 2, 2000);
            } catch (Exception exception) {
                Log.error("Unexpected exception during %s", callDescription, exception);
                throw new TaskSchedulerException("Unexpected error during " + callDescription, exception);
            }
        }
    }

    /**
     * Determines if a gRPC error status is potentially transient and thus retryable.
     *
     * @param status the gRPC {@link Status} to check
     * @return {@code true} if the status code suggests a retry might succeed, {@code false} otherwise
     */
    private boolean isRetryable(Status status) {
        return status.getCode() == Status.Code.UNAVAILABLE || status.getCode() == Status.Code.RESOURCE_EXHAUSTED;
    }

}
