### Bukkit gRPC 客户端模块

该模块提供了一个 gRPC 客户端。它允许 Minecraft 服务器插件与远程 gRPC 任务调度器服务进行通信，
从而能够将可能长时间运行或资源密集型的任务从主服务器线程中卸载，促进与外部系统的通信。

该客户端处理连接管理（包括可选的 TLS）、任务提交（同步和异步）、将参数序列化为 Protobuf `Any` 格式、针对网络问题的基本重试逻辑，以及在任务完成时触发
Bukkit 事件。

### 用法

```kotlin
dependencies {
    // commons module
    compileOnly(project(":commons"))

    // bukkit-grpc-client module
    compileOnly(project(":experimental:third-party-schedulers:bukkit-grpc-client"))
}
```

### 核心概念

该模块基于以下关键概念运行：

1. **gRPC 客户端-服务器架构:**
    * 本模块扮演 **gRPC 客户端** 的角色。
    * 它需要一个独立的 **gRPC 服务器** 正在运行。该服务器必须实现 `TaskScheduler` 服务。

2. **TaskScheduler 服务 (`task_scheduler.proto`):**
    * 通信契约在位于 `experimental/third-party-schedulers/proto/task_scheduler.proto` 的 Protocol Buffers 文件中定义。
    * 该服务公开了两个主要的 RPC 方法：
        * `rpc SubmitTask(TaskRequest) returns (TaskResponse)`: 客户端用于向服务器发送任务。`TaskRequest` 包含唯一的
          `task_id`、要在服务器上执行的 `method` 名称、`args` (作为 `Any`) 以及一个 `is_async` 标志。`TaskResponse`
          指示初始状态 (PENDING, SUCCESS, FAILED) 和可能的即时结果。
        * `rpc GetResult(ResultRequest) returns (ResultResponse)`: 客户端内部使用（主要用于未立即完成的异步任务），通过任务的
          `task_id` 轮询最终状态和结果。
    * **`TaskRequest` 详解:**
        * `task_id` (string): 任务的唯一标识符。
        * `method` (string): 需要在服务端执行的已注册任务的名称。
        * `args` (repeated google.protobuf.Any): 传递给任务函数的参数列表，每个参数都被序列化并包装在 `Any` 消息中。
        * `deps` (repeated string): 一个可选的字符串列表，包含此任务所依赖的其他任务的 `task_id`
          。服务器在执行此任务前，会检查所有依赖任务是否已成功完成并缓存了结果。
          **如果任何依赖项未成功完成（即在结果缓存中找不到其成功记录），则当前任务将立即失败。**
        * `is_async` (bool): 指示任务是否应异步执行。

3. **`GRPCTaskSchedulerClient` 类:**
    * 用于与远程任务调度器服务交互的主要 Java 类。
    * 管理底层的 gRPC `ManagedChannel`。
    * 提供任务提交方法 (`submitTaskBlocking`, `submitTaskAsync`)。
    * 处理通道关闭 (`shutdown()` 方法)。

4. **参数转换 (`ProtoConversionUtil`):**
    * 传递给提交方法的 Java 参数在通过 gRPC 发送之前会自动转换为 `google.protobuf.Any` 消息。
    * **支持的类型:**
        * 原始类型包装类: `Integer`, `Long`, `Boolean`, `Float`, `Double`.
        * `String`.
        * `byte[]`.
        * `java.util.List<?>`: 递归转换为自定义的 `taskscheduler.ListValue` 消息，然后打包到 `Any` 中。
        * `java.util.Map<String, ?>`: 递归转换为自定义的 `taskscheduler.MapValue` 消息（键 *必须* 是 `String`），然后打包到
          `Any` 中。
    * **不支持的类型:** 记录警告并尝试使用 `toString()` 进行转换，打包为 `StringValue`。除非服务器端特别处理，否则这很可能无法正常工作。

5. **任务提交模式:**
    * **同步 (`submitTaskBlocking`):** 阻塞调用线程，直到 `SubmitTask` RPC 完成 *并且* 服务器指示最终结果（SUCCESS 或
      FAILED）。在失败或超时时抛出 `TaskSchedulerException`。
    * **异步 (`submitTaskAsync`):** 通过 `SubmitTask` 提交任务并立即返回 `CompletableFuture<String>`。gRPC
      调用和可能的结果轮询在为客户端配置的 `ExecutorService` 上进行。该 future 以最终结果完成，或以异常失败（通常包装在
      `CompletionException` 中）。

6. **异步结果处理 (`TaskResultEvent`):**
    * 无论任务是同步还是异步提交的，只要任务达到确定状态（SUCCESS 或 FAILED），就会触发一个 Bukkit `Event` (
      `net.legacy.library.grpcclient.event.TaskResultEvent`)。
    * **重要:** 此事件在 gRPC 客户端的 `ExecutorService` 线程上触发，**不是** 在主 Bukkit 服务器线程上。事件处理程序中与
      Bukkit API 的任何交互（例如，修改玩家状态、发送消息）*必须* 使用 `BukkitRunnable().runTask(plugin)` 调度回主线程。

7. **重试机制:**
    * 客户端会自动重试因潜在的瞬时网络错误（`UNAVAILABLE` 或 `RESOURCE_EXHAUSTED` 状态码）而失败的 gRPC 调用。
    * 重试会在配置的 `maxRetries` 限制内进行，并采用指数退避策略。
    * 其他 gRPC 错误被视为不可重试并立即失败。

8. **TLS 支持:**
    * 可以将客户端配置为使用 TLS 加密连接到 gRPC 服务器，以实现安全通信。
    * 如果启用 TLS，则需要在初始化期间提供指向受信任的 CA 证书文件 (`ca.crt`) 的路径。

### 客户端实例创建

您需要在插件启动时（例如 `onEnable` 中）创建 `GRPCTaskSchedulerClient` 的实例，并在插件禁用时（`onDisable` 中）调用其
`shutdown()` 方法。`ExecutorService` 会自动在 `shutdown()` 时自动关闭。

```java
public class SimpleClientInitExample {
    private GRPCTaskSchedulerClient grpcClient;

    public void initialize() throws TaskSchedulerException {
        String serverHost = "localhost";
        int serverPort = 50051;
        long callTimeoutMs = 10000; // 10 秒
        int maxRetries = 3;

        // 使用内部 CachedThreadPool
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

        // 创建并管理您自己的 ExecutorService
        myExecutor = Executors.newFixedThreadPool(4);

        grpcClient = new GRPCTaskSchedulerClient(
                serverHost, serverPort, callTimeoutMs, maxRetries,
                myExecutor // 传入外部 ExecutorService
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
        String caCertPath = "plugins/MyPlugin/ca.crt"; // CA 证书路径

        myTlsExecutor = Executors.newCachedThreadPool();

        grpcClient = new GRPCTaskSchedulerClient(
                serverHost, serverPort, callTimeoutMs, maxRetries,
                myTlsExecutor, // 传入 Executor
                true,          // 启用 TLS
                caCertPath     // CA 证书路径
        );
    }
}
```

### 提交同步任务

阻塞当前线程直到完成或失败。请一定避免在主线程上用于耗时任务。

```java
public class SyncTaskExample {
    public void submitSync(GRPCTaskSchedulerClient client) {
        String taskId = "sync-task-" + UUID.randomUUID();

        try {
            // 假设服务器有 "calculate" 方法
            String result = client.submitTaskBlocking(taskId, "calculate", 100, 200);
            System.out.println("同步任务结果: " + result);
        } catch (TaskSchedulerException exception) {
            System.err.println("同步任务失败: " + exception.getMessage());
        }
    }

    public void submitComplexSync(GRPCTaskSchedulerClient client) {
        String taskId = "sync-complex-" + UUID.randomUUID();

        try {
            Map<String, Object> dataMap = Map.of("user", "Player789", "level", 10);

            // 假设服务器有 "process" 方法
            String result = client.submitTaskBlocking(taskId, "process", dataMap, "item1", "item2");
            System.out.println("复杂同步任务结果: " + result);
        } catch (TaskSchedulerException exception) {
            System.err.println("复杂同步任务失败: " + exception.getMessage());
        }
    }
}
```

### 提交异步任务

立即返回 `CompletableFuture`，不阻塞当前线程。

```java
public class AsyncTaskExample {
    public void submitAsync(GRPCTaskSchedulerClient client) {
        String taskId = "async-task-" + UUID.randomUUID();
        try {
            // 假设服务器有 "long_operation" 方法
            CompletableFuture<String> future = client.submitTaskAsync(taskId, "long_operation", 5000);

            // 使用 CompletableFuture 处理结果（使用 ExecutorService，而不是 Bukkit 线程）
            future.whenComplete((result, exception) -> {
                if (exception != null) {
                    System.err.println("[WorkerThread] 异步任务失败: " + exception.getMessage());
                } else {
                    System.out.println("[WorkerThread] 异步任务成功: " + result);
                }
            });

            System.out.println("异步任务已提交，继续执行...");
        } catch (Exception exception) {
            System.err.println("提交异步任务时出错: " + exception.getMessage());
        }
    }
}
```

### 通过事件处理结果

注册 Bukkit `Listener` 以响应 `TaskResultEvent`。

需要注意的是，该事件被触发的线程是 gRPC 工作线程，不是 Bukkit 线程。
如果需要使用 Bukkit API，则推荐使用 `commons` 模块内的 `TaskInterface` 进行任务调度，或直接使用 Bukkit 线程池

```java
public class ResultEventListener implements Listener {
    @EventHandler
    public void onTaskResult(TaskResultEvent event) {
        // --- 在 gRPC 工作线程上运行 ---
        String taskId = event.getTaskId();
        String method = event.getMethod();

        if (event.isSuccess()) {
            String result = event.getResult();
            System.out.printf("[WorkerThread-Event] 任务成功: ID=%s, Method=%s, Result=%s%n", taskId, method, result);
        } else {
            Throwable exception = event.getException();
            System.err.printf("[WorkerThread-Event] 任务失败: ID=%s, Method=%s, Error: %s%n", taskId, method, exception.getMessage());
        }
    }
}
```
