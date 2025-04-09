### Bukkit gRPC Client Module

This module provides a gRPC client. It allows Minecraft server plugins to communicate with a remote gRPC task scheduler
service,
enabling the offloading of potentially long-running or resource-intensive tasks from the main server thread and
facilitating communication with external systems.

The client handles connection management (including optional TLS), task submission (synchronous and asynchronous),
serialization of parameters into the Protobuf `Any` format, basic retry logic for network issues, and triggering
Bukkit events upon task completion.

### Usage

```kotlin
dependencies {
    // commons module
    compileOnly(project(":commons"))

    // bukkit-grpc-client module
    compileOnly(project(":experimental:third-party-schedulers:bukkit-grpc-client"))
}
```

### Core Concepts

The module operates based on the following key concepts:

1. **gRPC Client-Server Architecture:**
    * This module acts as the **gRPC client**.
    * It requires a separate **gRPC server** to be running. This server must implement the `TaskScheduler` service.

2. **TaskScheduler Service (`task_scheduler.proto`):**
    * The communication contract is defined in the Protocol Buffers file located at
      `experimental/third-party-schedulers/proto/task_scheduler.proto`.
    * The service exposes two main RPC methods:
        * `rpc SubmitTask(TaskRequest) returns (TaskResponse)`: Used by the client to send tasks to the server.
          `TaskRequest` contains a unique
          `task_id`, the `method` name to execute on the server, `args` (as `Any`), and an `is_async` flag.
          `TaskResponse`
          indicates the initial status (PENDING, SUCCESS, FAILED) and a possible immediate result.
        * `rpc GetResult(ResultRequest) returns (ResultResponse)`: Used internally by the client (primarily for async
          tasks not completed immediately) to poll for the final status and result using the task's `task_id`.
    * **`TaskRequest` Details:**
        * `task_id` (string): A unique identifier for the task.
        * `method` (string): The name of the registered task function to execute on the server.
        * `args` (repeated google.protobuf.Any): A list of arguments to pass to the task function, each serialized and
          packed into an `Any` message.
        * `deps` (repeated string): An optional list of strings containing the `task_id`s of other tasks that this task
          depends on.
          Before executing this task, the server checks if all dependent tasks have completed successfully and their
          results are cached.
          **If any dependency has not completed successfully (i.e., its success record is not found in the result
          cache), the current task will fail immediately.**
        * `is_async` (bool): Indicates whether the task should be executed asynchronously.

3. **`GRPCTaskSchedulerClient` Class:**
    * The main Java class used to interact with the remote task scheduler service.
    * Manages the underlying gRPC `ManagedChannel`.
    * Provides task submission methods (`submitTaskBlocking`, `submitTaskAsync`).
    * Handles channel shutdown (`shutdown()` method).

4. **Parameter Conversion (`ProtoConversionUtil`):**
    * Java parameters passed to submission methods are automatically converted to `google.protobuf.Any` messages before
      being sent via gRPC.
    * **Supported Types:**
        * Primitive wrapper classes: `Integer`, `Long`, `Boolean`, `Float`, `Double`.
        * `String`.
        * `byte[]`.
        * `java.util.List<?>`: Recursively converted to a custom `taskscheduler.ListValue` message, then packed into
          `Any`.
        * `java.util.Map<String, ?>`: Recursively converted to a custom `taskscheduler.MapValue` message (keys *must* be
          `String`), then packed into `Any`.
    * **Unsupported Types:** Logs a warning and attempts conversion using `toString()`, packed as `StringValue`. This is
      unlikely to work unless specifically handled by the server-side.

5. **Task Submission Modes:**
    * **Synchronous (`submitTaskBlocking`):** Blocks the calling thread until the `SubmitTask` RPC completes *and* the
      server indicates a final result (SUCCESS or FAILED). Throws a `TaskSchedulerException` on failure or timeout.
    * **Asynchronous (`submitTaskAsync`):** Submits the task via `SubmitTask` and returns immediately with a
      `CompletableFuture<String>`. The gRPC
      call and potential result polling occur on the `ExecutorService` configured for the client. The future completes
      with the final result or fails with an exception (often wrapped in `CompletionException`).

6. **Asynchronous Result Handling (`TaskResultEvent`):**
    * Regardless of whether a task is submitted synchronously or asynchronously, a Bukkit `Event` (
      `net.legacy.library.grpcclient.event.TaskResultEvent`) is triggered whenever a task reaches a terminal state (
      SUCCESS or FAILED).
    * **Important:** This event is triggered on the gRPC client's `ExecutorService` thread, **not** on the main Bukkit
      server thread. Any interaction with the Bukkit API within the event handler (e.g., modifying player state, sending
      messages) *must* be scheduled back to the main thread using `BukkitRunnable().runTask(plugin)`.

7. **Retry Mechanism:**
    * The client automatically retries gRPC calls that fail due to potentially transient network errors (`UNAVAILABLE`
      or `RESOURCE_EXHAUSTED` status codes).
    * Retries occur up to the configured `maxRetries` limit with an exponential backoff strategy.
    * Other gRPC errors are considered non-retryable and fail immediately.

8. **TLS Support:**
    * The client can be configured to use TLS encryption for the connection to the gRPC server for secure communication.
    * If TLS is enabled, a path to the trusted CA certificate file (`ca.crt`) needs to be provided during
      initialization.

### Client Instance Creation

You need to create an instance of `GRPCTaskSchedulerClient` when your plugin starts (e.g., in `onEnable`) and call its
`shutdown()` method when the plugin is disabled (`onDisable`). The `ExecutorService` is automatically shut down during
`shutdown()`.

```java
public class SimpleClientInitExample {
    private GRPCTaskSchedulerClient grpcClient;

    public void initialize() throws TaskSchedulerException {
        String serverHost = "localhost";
        int serverPort = 50051;
        long callTimeoutMs = 10000; // 10 seconds
        int maxRetries = 3;

        // Uses an internal CachedThreadPool
        grpcClient = new GRPCTaskSchedulerClient(
                serverHost, serverPort, callTimeoutMs, maxRetries
        );
    }
}
```

```java
public class ExternalExecutorClientInitExample {
    private GRPCTaskSchedulerClient grpcClient;
    private ExecutorService myExecutor;

    public void initialize() throws TaskSchedulerException {
        String serverHost = "localhost";
        int serverPort = 50051;
        long callTimeoutMs = 10000;
        int maxRetries = 3;

        // Create and manage your own ExecutorService
        myExecutor = Executors.newFixedThreadPool(4);

        grpcClient = new GRPCTaskSchedulerClient(
                serverHost, serverPort, callTimeoutMs, maxRetries,
                myExecutor // Pass the external ExecutorService
        );
    }
}
```

```java
public class TlsClientInitExample {
    private GRPCTaskSchedulerClient grpcClient;
    private ExecutorService myTlsExecutor;

    public void initialize() throws TaskSchedulerException {
        String serverHost = "your.grpc.server.com";
        int serverPort = 50051;
        long callTimeoutMs = 15000;
        int maxRetries = 2;
        String caCertPath = "plugins/MyPlugin/ca.crt"; // Path to CA certificate

        myTlsExecutor = Executors.newCachedThreadPool();

        grpcClient = new GRPCTaskSchedulerClient(
                serverHost, serverPort, callTimeoutMs, maxRetries,
                myTlsExecutor, // Pass the Executor
                true,          // Enable TLS
                caCertPath     // Path to CA certificate
        );
    }
}
```

### Submitting Synchronous Tasks

Blocks the current thread until completion or failure. Avoid using this on the main thread for long-running tasks.

```java
public class SyncTaskExample {
    public void submitSync(GRPCTaskSchedulerClient client) {
        String taskId = "sync-task-" + UUID.randomUUID();

        try {
            // Assume server has a "calculate" method
            String result = client.submitTaskBlocking(taskId, "calculate", 100, 200);
            System.out.println("Sync task result: " + result);
        } catch (TaskSchedulerException exception) {
            System.err.println("Sync task failed: " + exception.getMessage());
        }
    }

    public void submitComplexSync(GRPCTaskSchedulerClient client) {
        String taskId = "sync-complex-" + UUID.randomUUID();

        try {
            Map<String, Object> dataMap = Map.of("user", "Player789", "level", 10);

            // Assume server has a "process" method
            String result = client.submitTaskBlocking(taskId, "process", dataMap, "item1", "item2");
            System.out.println("Complex sync task result: " + result);
        } catch (TaskSchedulerException exception) {
            System.err.println("Complex sync task failed: " + exception.getMessage());
        }
    }
}
```

### Submitting Asynchronous Tasks

Returns a `CompletableFuture` immediately, does not block the current thread.

```java
public class AsyncTaskExample {
    public void submitAsync(GRPCTaskSchedulerClient client) {
        String taskId = "async-task-" + UUID.randomUUID();
        try {
            // Assume server has a "long_operation" method
            CompletableFuture<String> future = client.submitTaskAsync(taskId, "long_operation", 5000);

            // Handle the result using CompletableFuture (on the ExecutorService, not Bukkit thread)
            future.whenComplete((result, exception) -> {
                if (exception != null) {
                    System.err.println("[WorkerThread] Async task failed: " + exception.getMessage());
                } else {
                    System.out.println("[WorkerThread] Async task successful: " + result);
                }
            });

            System.out.println("Async task submitted, continuing execution...");
        } catch (Exception exception) {
            System.err.println("Error submitting async task: " + exception.getMessage());
        }
    }
}
```

### Handling Results via Events

Register a Bukkit `Listener` to respond to `TaskResultEvent`.

Note that this event is triggered on the gRPC worker thread, not the Bukkit thread.
If you need to use the Bukkit API, it is recommended to use `TaskInterface` from the `commons` module for task
scheduling, or directly use the Bukkit scheduler.

```java
public class ResultEventListener implements Listener {
    @EventHandler
    public void onTaskResult(TaskResultEvent event) {
        // --- Runs on gRPC worker thread ---
        String taskId = event.getTaskId();
        String method = event.getMethod();

        if (event.isSuccess()) {
            String result = event.getResult();
            System.out.printf("[WorkerThread-Event] Task successful: ID=%s, Method=%s, Result=%s%n", taskId, method, result);
        } else {
            Throwable exception = event.getException();
            System.err.printf("[WorkerThread-Event] Task failed: ID=%s, Method=%s, Error: %s%n", taskId, method, exception.getMessage());
        }
    }
}
```
