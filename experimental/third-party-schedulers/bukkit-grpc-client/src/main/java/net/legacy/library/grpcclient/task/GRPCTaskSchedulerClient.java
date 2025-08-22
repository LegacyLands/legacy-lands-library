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
import net.legacy.library.grpcclient.event.TaskResultEvent;
import org.apache.commons.lang3.Validate;
import org.bukkit.Bukkit;
import org.bukkit.plugin.PluginManager;
import taskscheduler.TaskSchedulerGrpc;
import taskscheduler.TaskSchedulerOuterClass.ResultRequest;
import taskscheduler.TaskSchedulerOuterClass.ResultResponse;
import taskscheduler.TaskSchedulerOuterClass.TaskRequest;
import taskscheduler.TaskSchedulerOuterClass.TaskResponse;

import javax.net.ssl.SSLException;
import java.io.File;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

/**
 * A client for interacting with the Task Scheduler via gRPC.
 *
 * <p>This client provides methods for submitting tasks synchronously (blocking) and
 * asynchronously (returning a {@link CompletableFuture}). It handles connection management,
 * argument serialization to Protobuf {@link Any} format (including Lists and Maps via custom messages),
 * and basic retry logic for gRPC calls. It also supports optional TLS encryption.
 *
 * <p>Usage typically involves creating an instance using one of the constructors (specifying TLS options if needed)
 * and then calling {@link #submitTaskBlocking(String, String, Object...)} or {@link #submitTaskAsync(String, String, Object...)}.
 * Remember to call {@link #shutdown()} when the client is no longer needed to release resources.
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@Getter
public class GRPCTaskSchedulerClient {

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
     * Constructs a new {@code GrpcTaskSchedulerClient} with explicit TLS configuration.
     *
     * @param host         the hostname or IP address of the remote task scheduler server
     * @param port         the port number of the remote task scheduler server
     * @param timeoutMs    timeout in milliseconds for individual gRPC calls
     * @param maxRetries   maximum number of retries for potentially transient gRPC errors
     * @param grpcExecutor the {@link ExecutorService} to use for asynchronous operations
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
            Log.warn("gRPC channel is configured to use plaintext (no TLS).");
            channelBuilder.usePlaintext();
        }

        this.channel = channelBuilder.build();
        this.blockingStub = TaskSchedulerGrpc.newBlockingStub(channel);
    }

    /**
     * Constructs a new {@code GrpcTaskSchedulerClient} without TLS (insecure).
     * Uses the provided ExecutorService.
     *
     * @param host         the hostname or IP address of the remote task scheduler server
     * @param port         the port number of the remote task scheduler server
     * @param timeoutMs    timeout in milliseconds for individual gRPC calls
     * @param maxRetries   maximum number of retries for potentially transient gRPC errors
     * @param grpcExecutor the {@link ExecutorService} to use for asynchronous operations
     * @throws TaskSchedulerException if TLS is enabled but configuring the SSL context fails
     */
    public GRPCTaskSchedulerClient(String host, int port, long timeoutMs, int maxRetries, ExecutorService grpcExecutor) throws TaskSchedulerException {
        this(host, port, timeoutMs, maxRetries, grpcExecutor, false, null);
    }

    /**
     * Constructs a new {@code GrpcTaskSchedulerClient} without TLS (insecure).
     * Uses a default cached thread pool executor.
     *
     * @param host       the hostname or IP address of the remote task scheduler server
     * @param port       the port number of the remote task scheduler server
     * @param timeoutMs  timeout in milliseconds for individual gRPC calls
     * @param maxRetries maximum number of retries for potentially transient gRPC errors
     * @throws TaskSchedulerException if TLS is enabled but configuring the SSL context fails
     */
    public GRPCTaskSchedulerClient(String host, int port, long timeoutMs, int maxRetries) throws TaskSchedulerException {
        this(host, port, timeoutMs, maxRetries, Executors.newCachedThreadPool());
    }

    /**
     * Shuts down the gRPC channel and the {@link ExecutorService} used by this client.
     *
     * <p>This method should be called when the client instance is no longer needed to release
     * network and thread resources gracefully. It attempts a graceful shutdown with a timeout
     * before forcing termination.
     */
    public void shutdown() {
        if (grpcExecutor != null && !grpcExecutor.isShutdown()) {
            grpcExecutor.shutdown();
            try {
                if (!grpcExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                    Log.warn("ExecutorService did not terminate within 5 seconds, forcing shutdown...");
                    grpcExecutor.shutdownNow();
                    if (!grpcExecutor.awaitTermination(5, TimeUnit.SECONDS)) {
                        Log.error("ExecutorService did not terminate even after forcing shutdown.");
                    }
                }
            } catch (InterruptedException exception) {
                Log.warn("Interrupted while waiting for executor shutdown, forcing now.", exception);
                grpcExecutor.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }

        if (channel != null && !channel.isShutdown()) {
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
     * Submits a task to the scheduler synchronously (blocking).
     *
     * @param taskId a unique identifier for this task submission. Must not be {@code null} or empty
     * @param method the name of the task function to execute on the server side
     * @param args   variable arguments to pass to the remote task function
     * @return the result returned by the remote task function as a String
     * @throws TaskSchedulerException   if the task submission fails after retries, if the task execution fails on the server,
     *                                  or if the provided {@code taskId} is invalid
     * @throws NullPointerException     if {@code taskId} or {@code method} is null
     * @throws IllegalArgumentException if {@code taskId} is empty
     */
    public String submitTaskBlocking(String taskId, String method, Object... args) throws TaskSchedulerException {
        Validate.notEmpty(taskId, "Task ID cannot be null or empty.");
        Validate.notNull(method, "Method name cannot be null.");
        return submitTaskInternal(taskId, method, false, args);
    }

    /**
     * Submits a task to the scheduler asynchronously.
     *
     * <p>This method returns immediately with a {@link CompletableFuture} which will be completed
     * with the task result when the remote server responds. The actual gRPC call is made using the configured {@code grpcExecutor}.
     *
     * @param taskId a unique identifier for this task submission. Must not be {@code null} or empty
     * @param method the name of the task function to execute on the server side
     * @param args   variable arguments to pass to the remote task function
     * @return a {@link CompletableFuture<String>} that will eventually contain the task result or an exception
     * @throws NullPointerException     if {@code taskId} or {@code method} is null
     * @throws IllegalArgumentException if {@code taskId} is empty
     */
    public CompletableFuture<String> submitTaskAsync(String taskId, String method, Object... args) {
        try {
            Validate.notEmpty(taskId, "Task ID cannot be null or empty.");
            Validate.notNull(method, "Method name cannot be null.");
            Validate.notNull(blockingStub, "gRPC client not initialized.");
        } catch (Exception exception) {
            return CompletableFuture.failedFuture(exception);
        }

        final List<Any> protoArgs;
        try {
            protoArgs = new ArrayList<>(args.length);
            for (Object arg : args) {
                protoArgs.add(ProtoConversionUtil.convertToProtoAny(arg));
            }
        } catch (Exception exception) {
            Log.error("Failed to convert arguments for async task [TaskID: %s, Method: %s]", exception, taskId, method);
            return CompletableFuture.failedFuture(
                    new TaskSchedulerException("Failed to convert arguments for async task: " + taskId, exception)
            );
        }

        return CompletableFuture.supplyAsync(() -> {
            try {
                return executeSubmitTaskRpc(taskId, method, true, protoArgs);
            } catch (Exception exception) {
                Log.error("Exception during async task execution [TaskID: %s, Method: %s]", exception, taskId, method);
                if (exception instanceof CompletionException) {
                    throw (CompletionException) exception;
                } else if (exception instanceof TaskSchedulerException) {
                    throw new CompletionException("Async task execution failed for [TaskID: " + taskId + ", Method: " + method + "]", exception);
                } else {
                    throw new CompletionException("Unexpected exception during async task execution for [TaskID: " + taskId + ", Method: " + method + "]", exception);
                }
            }
        }, grpcExecutor).whenComplete((result, throwable) -> {
            final boolean success = throwable == null;

            Throwable finalThrowable = (throwable instanceof CompletionException && throwable.getCause() != null)
                    ? throwable.getCause() : throwable;

            TaskResultEvent event = success
                    ? new TaskResultEvent(taskId, method, result)
                    : new TaskResultEvent(taskId, method, finalThrowable);

            Bukkit.getServer().getPluginManager().callEvent(event);
        });
    }

    /**
     * Internal method to prepare and execute the gRPC {@code SubmitTask} call with retry logic.
     *
     * @param taskId  the task identifier
     * @param method  the method name
     * @param isAsync whether the call originated from an async request (for logging/error context)
     * @param args    the arguments for the task
     * @return the final result string after potential polling
     * @throws TaskSchedulerException if the operation fails definitively
     */
    private String submitTaskInternal(String taskId, String method, boolean isAsync, Object... args) throws TaskSchedulerException {
        Validate.notNull(blockingStub, "gRPC client stub is null, cannot submit task.");

        List<Any> protoArgs = new ArrayList<>();
        try {
            for (Object arg : args) {
                protoArgs.add(ProtoConversionUtil.convertToProtoAny(arg));
            }
        } catch (Exception exception) {
            Log.error("Failed to convert arguments for task [TaskID: %s, Method: %s]", exception, taskId, method);
            throw new TaskSchedulerException("Failed to convert arguments for task: " + taskId, exception);
        }

        return executeSubmitTaskRpc(taskId, method, isAsync, protoArgs);
    }

    /**
     * Internal method to perform the actual gRPC {@code SubmitTask} call.
     *
     * @param taskId    the task identifier
     * @param method    the method name
     * @param isAsync   whether the call originated from an async request
     * @param protoArgs the pre-converted Protobuf arguments
     * @return the result string, potentially after polling via {@link #getResultWhenReady(String)}
     * @throws TaskSchedulerException if the gRPC call fails or the server reports an error
     */
    private String executeSubmitTaskRpc(String taskId, String method, boolean isAsync, List<Any> protoArgs) throws TaskSchedulerException {
        TaskRequest request = TaskRequest.newBuilder()
                .setTaskId(taskId)
                .setMethod(method)
                .addAllArgs(protoArgs)
                .setIsAsync(isAsync)
                .build();

        TaskResponse response = executeWithRetry(() -> blockingStub
                .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                .submitTask(request));

        String result = response.getResult();
        PluginManager pluginManager = Bukkit.getServer().getPluginManager();

        final boolean success = response.getStatus() == TaskResponse.Status.SUCCESS;

        if (response.getStatus() == TaskResponse.Status.FAILED) {
            Log.error("Task execution failed on server: id=%s, method=%s, error=%s", taskId, method, result);

            TaskSchedulerException taskSchedulerException =
                    new TaskSchedulerException("Task execution failed on server: " + result);

            if (!isAsync) {
                pluginManager.callEvent(new TaskResultEvent(taskId, method, taskSchedulerException));
            }

            throw taskSchedulerException;
        } else if (success && !isAsync) {
            pluginManager.callEvent(new TaskResultEvent(taskId, method, result));
        }

        return result;
    }

    /**
     * Polls the gRPC {@code GetResult} endpoint until the task result is ready or an error occurs.
     *
     * @param taskId the identifier of the task whose result is needed
     * @return the result string once available
     * @throws TaskSchedulerException if polling fails (e.g., timeout, definitive server error)
     */
    private String getResultWhenReady(String taskId) throws TaskSchedulerException {
        Validate.notEmpty(taskId, "Task ID cannot be null or empty for getResult.");
        Validate.notNull(blockingStub, "gRPC client stub is null, cannot get result.");

        ResultRequest request = ResultRequest.newBuilder().setTaskId(taskId).build();
        long backoffMillis = 100;
        int pollingAttempts = 0;

        while (pollingAttempts <= maxRetries) {
            try {
                ResultResponse response = executeGrpcCallWithRetryInternal(() -> blockingStub
                        .withDeadlineAfter(timeoutMs, TimeUnit.MILLISECONDS)
                        .getResult(request), "GetResult(taskId=" + taskId + ")");

                TaskResponse.Status status = response.getStatus();

                switch (status) {
                    case SUCCESS:
                        return response.getResult();
                    case PENDING:
                        pollingAttempts++;
                        if (pollingAttempts > maxRetries) {
                            Log.warn("Task %s still PENDING after %s polling attempts, stopping polling.", taskId, maxRetries);
                            throw new TaskSchedulerException("Task still PENDING after max retries: " + taskId);
                        }
                        try {
                            TimeUnit.MILLISECONDS.sleep(backoffMillis);
                        } catch (InterruptedException exception) {
                            Thread.currentThread().interrupt();
                            throw new TaskSchedulerException("Interrupted while waiting for task result: " + taskId, exception);
                        }
                        backoffMillis = Math.min(backoffMillis * 2, timeoutMs);
                        break;
                    case FAILED:
                        Log.error("Task %s failed on server according to GetResult: %s", taskId, response.getResult());
                        throw new TaskSchedulerException("Task failed on server (reported by GetResult): " + response.getResult());
                    case UNRECOGNIZED:
                    default:
                        Log.error("GetResult received unrecognized status for task %s: %s", taskId, status);
                        throw new TaskSchedulerException("GetResult received unrecognized status from server: " + status);
                }
            } catch (Exception exception) {
                if (exception instanceof TaskSchedulerException) {
                    throw (TaskSchedulerException) exception;
                } else {
                    Log.error("Unexpected exception during GetResult polling for task %s.", taskId, exception);
                    throw new TaskSchedulerException("Unexpected error during GetResult polling for task " + taskId, exception);
                }
            }
        }

        throw new TaskSchedulerException("Failed to get final task result for " + taskId + " within retry limits.");
    }

    /**
     * Executes a gRPC call that returns a {@link TaskResponse} with retry logic for transient errors.
     * <p>This is a specific wrapper around {@link #executeGrpcCallWithRetryInternal(Callable, String)}.
     *
     * @param grpcCall a {@link Callable} representing the gRPC call to execute
     * @return the {@link TaskResponse} from the successful gRPC call
     * @throws TaskSchedulerException if the call fails after retries or encounters a non-retryable error
     */
    private TaskResponse executeWithRetry(Callable<TaskResponse> grpcCall) throws TaskSchedulerException {
        return executeGrpcCallWithRetryInternal(grpcCall, "executeWithRetry");
    }

    /**
     * Executes a generic gRPC call with retry logic for transient errors.
     *
     * @param <T>             the return type of the gRPC call
     * @param grpcCall        a {@link Callable} representing the gRPC call to execute
     * @param callDescription a description of the call for logging purposes
     * @return the result of the successful gRPC call
     * @throws TaskSchedulerException if the call fails after retries or encounters a non-retryable error
     */
    private <T> T executeGrpcCallWithRetryInternal(Callable<T> grpcCall, String callDescription) throws TaskSchedulerException {
        int attempts = 0;
        long backoffMillis = 50;

        while (attempts <= maxRetries) {
            try {
                return grpcCall.call();
            } catch (StatusRuntimeException exception) {
                Status status = exception.getStatus();
                if (isRetryable(status) && attempts < maxRetries) {
                    attempts++;
                    Log.warn("%s gRPC call failed with retryable status: %s (Attempt %s/%s). Retrying in %sms...",
                            callDescription, status, attempts, maxRetries + 1, backoffMillis, exception);
                    try {
                        TimeUnit.MILLISECONDS.sleep(backoffMillis);
                    } catch (InterruptedException interruptedException) {
                        Thread.currentThread().interrupt();
                        throw new TaskSchedulerException("Interrupted during retry backoff for " + callDescription, interruptedException);
                    }
                    backoffMillis = Math.min(backoffMillis * 2, 2000);
                } else {
                    Log.error("%s gRPC call failed with non-retryable status: %s or max retries (%s) reached.", callDescription, status, maxRetries + 1, exception);
                    throw new TaskSchedulerException(callDescription + " gRPC call failed: " + status, exception);
                }
            } catch (Exception exception) {
                Log.error("Unexpected exception during %s gRPC call execution.", callDescription, exception);
                throw new TaskSchedulerException("Unexpected error during " + callDescription + " gRPC call", exception);
            }
        }

        throw new TaskSchedulerException(callDescription + " gRPC call failed after " + (maxRetries + 1) + " attempts.");
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