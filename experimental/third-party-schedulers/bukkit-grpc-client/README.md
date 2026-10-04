### Bukkit gRPC Client Module

This module provides a gRPC client that allows Minecraft server plugins to communicate with remote
[task scheduler](../task-scheduler-rust/README.md) nodes, offloading long-running or resource-intensive tasks from the
server and facilitating communication with external systems.

The client handles connection management (including optional TLS), blocking, asynchronous and detached task
submission, cross-node task dependencies, cluster-aware routing, cancellation, serialization of parameters into the
Protobuf `Any` format, retries for transient network issues, and Bukkit events upon task completion.

### Usage

```kotlin
dependencies {
    // commons module
    compileOnly(project(":commons"))

    // bukkit-grpc-client module
    compileOnly(project(":bukkit-grpc-client"))
}
```

At runtime the plugin depends on `fairy-lib-plugin`, `commons` and `foundation`.

### Core Concepts

1. **Scheduler Nodes and Clusters:**
    * Each `GRPCTaskSchedulerClient` talks to one scheduler node.
    * Nodes can form a cluster. A task submitted to one node may depend on tasks executed on other nodes, the nodes
      resolve such dependencies on their own. Tasks for methods that a node does not provide are forwarded to a peer
      that does.
    * `ClusterTaskSchedulerClient` manages several node clients and adds addressing by node id and load-based routing.

2. **Task Submission (`TaskSubmission`):**
    * Describes a task: `taskId`, `method`, `args`, `dependencies`, `dependencyTimeoutMs`, `injectDependencyResults`,
      `executionTimeoutMs` and `disableForwarding`.
    * Task ids must be unique across the cluster. Submitting a known id again is idempotent: the existing task is
      returned instead of executing it again, which makes retries safe.
    * With `injectDependencyResults`, the results of all dependencies are prepended to the arguments as strings, in
      dependency order.

3. **Task Results (`TaskResult`, `TaskStatus`):**
    * `TaskResult` contains the `taskId`, the `status`, the `result` (output on success, error message on failure) and
      the `nodeId` of the node that executed the task.
    * `TaskStatus` is one of `PENDING`, `RUNNING`, `SUCCESS`, `FAILED`, `CANCELLED` and `NOT_FOUND`.

4. **Submission Modes:**
    * **Blocking (`submit`):** blocks until the task is finished, including the time spent waiting for dependencies.
      The call deadline is `timeoutMs` plus the dependency timeout (the server default of 30 seconds if unspecified and
      dependencies exist) plus the execution timeout.
    * **Asynchronous (`submitAsync`):** returns a `CompletableFuture<TaskResult>`. The task is submitted detached and
      followed with `WaitResult` long polls on the client's executor, so slow dependencies never hit a single call
      deadline.
    * **Detached (`submitDetached`):** returns right after the server accepted the task, usually with `PENDING`. Follow
      the task with `getResult` or `waitResult`.

5. **Failures (`TaskSchedulerException`):**
    * Blocking submissions and queries throw `TaskSchedulerException`. `getStatusCode()` exposes the gRPC status code:
      `FAILED_PRECONDITION` for failed dependencies, `DEADLINE_EXCEEDED` for dependency or execution timeouts,
      `NOT_FOUND` for methods available on no node, `INVALID_ARGUMENT` for rejected arguments.
    * Asynchronous submissions complete exceptionally with a `TaskSchedulerException` (wrapped in
      `CompletionException`) whose `getTaskStatus()` is the final task status.

6. **Parameter Conversion:**
    * Supported types: `Integer`, `Long`, `Boolean`, `Float`, `Double`, `String`, `byte[]`, `java.util.List<?>` and
      `java.util.Map<String, ?>` (converted recursively).
    * Other types are converted with `toString()` and a warning is logged, which is unlikely to work unless the task
      expects a string.

7. **Result Events (`TaskResultEvent`):**
    * `submit` and `submitAsync` (and the legacy `submitTaskBlocking` / `submitTaskAsync`) fire a `TaskResultEvent` when
      the task succeeds or fails. `submitDetached` does not fire events.
    * The event is usually fired from the client's executor and is then marked asynchronous. Listeners must switch back
      to the primary thread before using the Bukkit API, e.g. with `TaskInterface` from the `commons` module.

8. **Retry Mechanism:**
    * Calls failing with `UNAVAILABLE` or `RESOURCE_EXHAUSTED` are retried up to `maxRetries` times with exponential
      backoff. Other errors fail immediately.

9. **TLS Support:**
    * The client can use TLS. A trusted CA certificate file (`ca.crt`) has to be provided when TLS is enabled.

10. **Packaging:**
    * Paper ships older Protobuf and Guava versions, so both are relocated inside the plugin jar. The public API only
      exposes the module's own types, never generated Protobuf classes.

### Client Instance Creation

Create the client when your plugin starts and call `shutdown()` when it is disabled. `shutdown()` also shuts down the
client's `ExecutorService`.

```java
// Uses a dedicated virtual thread per task executor
GRPCTaskSchedulerClient client = new GRPCTaskSchedulerClient("localhost", 50051, 10000, 3);

// Uses your own executor
GRPCTaskSchedulerClient clientWithExecutor = new GRPCTaskSchedulerClient("localhost", 50051, 10000, 3, executor);

// Uses TLS, trusting the given CA certificate
GRPCTaskSchedulerClient tlsClient = new GRPCTaskSchedulerClient(
        "scheduler.example.com", 50051, 15000, 2, executor, true, "plugins/MyPlugin/ca.crt"
);
```

### Submitting Tasks

```java
// Blocking, avoid calling it on the primary thread
TaskResult sum = client.submit(TaskSubmission.of("sum-" + UUID.randomUUID(), "add", 1, 2, 3));

// Asynchronous
client.submitAsync(TaskSubmission.of("fib-" + UUID.randomUUID(), "fibonacci", 30))
        .whenComplete((result, throwable) -> {
            // Runs on the client's executor, not on the primary thread
        });

// Legacy shortcuts returning the raw result string
String value = client.submitTaskBlocking("ping-" + UUID.randomUUID(), "ping", "hello");
CompletableFuture<String> future = client.submitTaskAsync("ping-" + UUID.randomUUID(), "ping", "hello");
```

### Cross-Node Dependencies

Task A on `node-1` consumes the result of task B on `node-2`. The dependency may be submitted before or after the
dependent task, as long as it finishes within the dependency timeout.

```java
ClusterTaskSchedulerClient cluster = new ClusterTaskSchedulerClient(List.of(node1Client, node2Client));

cluster.submitDetached("node-2", TaskSubmission.of("task-b", "load_statistics", playerId));

TaskResult taskA = cluster.submit("node-1", TaskSubmission.builder()
        .taskId("task-a")
        .method("analyze")
        .dependency("task-b")
        .injectDependencyResults(true)
        .dependencyTimeoutMs(10000)
        .build());
```

### Cluster Client

`ClusterTaskSchedulerClient` learns the topology (node ids, methods and load) through `GetNodeInfo`:

* `submit(nodeId, submission)`, `submitAsync(nodeId, submission)`, `submitDetached(nodeId, submission)` address a
  specific node.
* `submit(submission)` and `submitAsync(submission)` route to the least loaded node providing the method, falling back
  to any reachable node, which then forwards the task.
* `getResult(taskId)` finds a task on any node, `cancelTask(taskId)` cancels it wherever it runs.
* `refreshTopology()` reloads node information, load figures are only as fresh as the last refresh.
* `getNodeIds()`, `getNodeInfo(nodeId)` and `getClient(nodeId)` expose the known nodes.

Node ids must be unique, a warning is logged if two nodes report the same id.

### Querying, Waiting and Cancelling

```java
TaskResult state = client.getResult(taskId);           // NOT_FOUND if the node does not know the task
TaskResult finished = client.waitResult(taskId, 5000); // Not terminal if the wait timed out
boolean cancelled = client.cancelTask(taskId);         // The task may run on any node
SchedulerNodeInfo info = client.getNodeInfo();         // Node id, methods, load and peers
```

Tasks waiting for dependencies and async tasks stop immediately when cancelled. Running sync tasks finish in the
background, but their result is discarded.

### Handling Results via Events

```java
public class ResultEventListener implements Listener {

    @EventHandler
    public void onTaskResult(TaskResultEvent event) {
        // Usually runs on the client's executor, switch to the primary thread before using the Bukkit API
        if (event.isSuccess()) {
            String result = event.getResult();
        } else {
            Throwable exception = event.getException();
        }
    }

}
```

### Testing

`GRPCClientLauncher.DEBUG` enables the integration tests in `net.legacy.library.grpcclient.test`. They require two
scheduler nodes configured as peers of each other:

```bash
task-scheduler --addr 127.0.0.1:50051 --node-id node-a --peers http://127.0.0.1:50052
task-scheduler --addr 127.0.0.1:50052 --node-id node-b --peers http://127.0.0.1:50051
```

The addresses can be overridden with the system properties `grpcclient.test.node-a` and `grpcclient.test.node-b`
(`host:port`).
