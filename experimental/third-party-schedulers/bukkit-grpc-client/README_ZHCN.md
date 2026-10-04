### Bukkit gRPC 客户端模块

该模块提供了一个 gRPC 客户端，允许 Minecraft 服务器插件与远程 [任务调度器](../task-scheduler-rust/README_ZHCN.md)
节点通信，从而将长时间运行或资源密集型的任务从服务器中卸载，并促进与外部系统的通信。

该客户端负责连接管理（包括可选的 TLS）、阻塞式、异步和分离式任务提交、跨节点任务依赖、集群感知路由、任务取消、将参数序列化为
Protobuf `Any` 格式、针对临时网络问题的重试，以及在任务完成时触发 Bukkit 事件。

### 用法

```kotlin
dependencies {
    // commons module
    compileOnly(project(":commons"))

    // bukkit-grpc-client module
    compileOnly(project(":bukkit-grpc-client"))
}
```

运行时该插件依赖 `fairy-lib-plugin`、`commons` 和 `foundation`。

### 核心概念

1. **调度节点与集群:**
    * 每个 `GRPCTaskSchedulerClient` 连接一个调度节点。
    * 多个节点可以组成集群。提交到某个节点的任务可以依赖在其他节点上执行的任务，节点会自行解析这些依赖。节点不提供的方法会被转发给提供该方法的
      peer。
    * `ClusterTaskSchedulerClient` 管理多个节点客户端，并提供按节点 id 寻址和基于负载的路由。

2. **任务提交 (`TaskSubmission`):**
    * 描述一个任务：`taskId`、`method`、`args`、`dependencies`、`dependencyTimeoutMs`、`injectDependencyResults`、
      `executionTimeoutMs` 和 `disableForwarding`。
    * 任务 id 在集群内必须唯一。重复提交已知 id 是幂等的：会返回已有任务而不会再次执行，因此重试是安全的。
    * 启用 `injectDependencyResults` 时，所有依赖的结果会以字符串形式按依赖顺序插入到参数前面。

3. **任务结果 (`TaskResult`, `TaskStatus`):**
    * `TaskResult` 包含 `taskId`、`status`、`result`（成功时为输出，失败时为错误信息）以及执行该任务的节点 `nodeId`。
    * `TaskStatus` 取值为 `PENDING`、`RUNNING`、`SUCCESS`、`FAILED`、`CANCELLED` 和 `NOT_FOUND`。

4. **提交方式:**
    * **阻塞式 (`submit`):** 阻塞直到任务结束，包括等待依赖的时间。调用截止时间为 `timeoutMs` 加上依赖超时（存在依赖且未指定时使用服务器默认的
      30 秒）再加上执行超时。
    * **异步 (`submitAsync`):** 返回 `CompletableFuture<TaskResult>`。任务以分离方式提交，然后在客户端的执行器上通过 `WaitResult`
      长轮询跟踪，因此耗时较长的依赖不会触发单次调用的截止时间。
    * **分离式 (`submitDetached`):** 服务器接受任务后立即返回，通常为 `PENDING`。之后可以通过 `getResult` 或 `waitResult` 跟踪任务。

5. **失败处理 (`TaskSchedulerException`):**
    * 阻塞式提交和查询会抛出 `TaskSchedulerException`。`getStatusCode()` 返回 gRPC 状态码：依赖失败为 `FAILED_PRECONDITION`，
      依赖或执行超时为 `DEADLINE_EXCEEDED`，没有任何节点提供该方法为 `NOT_FOUND`，参数被拒绝为 `INVALID_ARGUMENT`。
    * 异步提交会以 `TaskSchedulerException`（包装在 `CompletionException` 中）异常完成，其 `getTaskStatus()` 为任务的最终状态。

6. **参数转换:**
    * 支持的类型：`Integer`、`Long`、`Boolean`、`Float`、`Double`、`String`、`byte[]`、`java.util.List<?>` 和
      `java.util.Map<String, ?>`（递归转换）。
    * 其他类型会通过 `toString()` 转换并输出警告日志，除非任务期望字符串，否则通常无法正常工作。

7. **结果事件 (`TaskResultEvent`):**
    * `submit` 和 `submitAsync`（以及旧版的 `submitTaskBlocking` / `submitTaskAsync`）在任务成功或失败时触发 `TaskResultEvent`。
      `submitDetached` 不会触发事件。
    * 该事件通常从客户端的执行器触发，此时会被标记为异步事件。监听器在使用 Bukkit API 之前必须切换回主线程，例如使用 `commons` 模块中的
      `TaskInterface`。

8. **重试机制:**
    * 以 `UNAVAILABLE` 或 `RESOURCE_EXHAUSTED` 失败的调用会以指数退避方式重试，最多 `maxRetries` 次。其他错误会立即失败。

9. **TLS 支持:**
    * 客户端可以使用 TLS。启用 TLS 时需要提供受信任的 CA 证书文件（`ca.crt`）。

10. **打包:**
    * Paper 自带较旧版本的 Protobuf 和 Guava，因此两者都在插件 jar 中被重定位。公开 API 只暴露本模块自己的类型，不会暴露生成的 Protobuf 类。

### 客户端实例创建

在插件启动时创建客户端，并在插件禁用时调用 `shutdown()`。`shutdown()` 也会关闭客户端的 `ExecutorService`。

```java
// 使用独立的虚拟线程执行器
GRPCTaskSchedulerClient client = new GRPCTaskSchedulerClient("localhost", 50051, 10000, 3);

// 使用自定义执行器
GRPCTaskSchedulerClient clientWithExecutor = new GRPCTaskSchedulerClient("localhost", 50051, 10000, 3, executor);

// 使用 TLS，信任指定的 CA 证书
GRPCTaskSchedulerClient tlsClient = new GRPCTaskSchedulerClient(
        "scheduler.example.com", 50051, 15000, 2, executor, true, "plugins/MyPlugin/ca.crt"
);
```

### 提交任务

```java
// 阻塞式，避免在主线程调用
TaskResult sum = client.submit(TaskSubmission.of("sum-" + UUID.randomUUID(), "add", 1, 2, 3));

// 异步
client.submitAsync(TaskSubmission.of("fib-" + UUID.randomUUID(), "fibonacci", 30))
        .whenComplete((result, throwable) -> {
            // 运行在客户端的执行器上，而不是主线程
        });

// 旧版快捷方法，直接返回结果字符串
String value = client.submitTaskBlocking("ping-" + UUID.randomUUID(), "ping", "hello");
CompletableFuture<String> future = client.submitTaskAsync("ping-" + UUID.randomUUID(), "ping", "hello");
```

### 跨节点依赖

`node-1` 上的任务 A 使用 `node-2` 上任务 B 的结果。只要依赖在依赖超时时间内完成，它可以在被依赖任务之前或之后提交。

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

### 集群客户端

`ClusterTaskSchedulerClient` 通过 `GetNodeInfo` 获取拓扑信息（节点 id、方法和负载）：

* `submit(nodeId, submission)`、`submitAsync(nodeId, submission)`、`submitDetached(nodeId, submission)` 向指定节点提交。
* `submit(submission)` 和 `submitAsync(submission)` 路由到提供该方法且负载最低的节点；若没有，则交给任意可达节点，由其转发任务。
* `getResult(taskId)` 在任意节点上查找任务，`cancelTask(taskId)` 无论任务在哪里运行都可以取消。
* `refreshTopology()` 重新加载节点信息，负载数据只与最近一次刷新一样新。
* `getNodeIds()`、`getNodeInfo(nodeId)` 和 `getClient(nodeId)` 提供已知节点的信息。

节点 id 必须唯一，如果两个节点上报相同的 id 会输出警告日志。

### 查询、等待与取消

```java
TaskResult state = client.getResult(taskId);           // 节点不知道该任务时为 NOT_FOUND
TaskResult finished = client.waitResult(taskId, 5000); // 等待超时时状态不是终态
boolean cancelled = client.cancelTask(taskId);         // 任务可以位于任意节点
SchedulerNodeInfo info = client.getNodeInfo();         // 节点 id、方法、负载和 peer
```

等待依赖的任务和异步任务在被取消时会立即停止。正在运行的同步任务会在后台运行完毕，但其结果会被丢弃。

### 通过事件处理结果

```java
public class ResultEventListener implements Listener {

    @EventHandler
    public void onTaskResult(TaskResultEvent event) {
        // 通常运行在客户端的执行器上，使用 Bukkit API 前需切换到主线程
        if (event.isSuccess()) {
            String result = event.getResult();
        } else {
            Throwable exception = event.getException();
        }
    }

}
```

### 测试

`GRPCClientLauncher.DEBUG` 会启用 `net.legacy.library.grpcclient.test` 中的集成测试。测试需要两个互为 peer 的调度节点：

```bash
task-scheduler --addr 127.0.0.1:50051 --node-id node-a --peers http://127.0.0.1:50052
task-scheduler --addr 127.0.0.1:50052 --node-id node-b --peers http://127.0.0.1:50051
```

可以通过系统属性 `grpcclient.test.node-a` 和 `grpcclient.test.node-b`（`host:port`）覆盖地址。
