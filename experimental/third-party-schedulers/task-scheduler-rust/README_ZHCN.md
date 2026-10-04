# Task Scheduler (Rust) - 任务调度器

Task Scheduler 是一个使用 Rust 编写的高性能任务执行后端。客户端通过 Protocol Buffers 定义的 gRPC 接口提交任务。多个调度节点可以组成
点对点集群，任务可以依赖在其他节点上执行的任务。系统利用 Rust 的异步能力（`tokio`）、并发特性和高效的数据结构，旨在实现快速、健壮且可扩展的任务执行。

## 概述

Task Scheduler 提供了一个 gRPC 服务来执行已注册的任务，支持同步和异步的任务函数。任务参数以 Protobuf `Any` 消息的形式传递，
在执行前被解码为带类型的 Rust 值。

每个提交的任务都有完整的生命周期（`PENDING → RUNNING → SUCCESS / FAILED / CANCELLED`），可以从集群中的任意节点查询、等待和取消。
已完成任务的结果会保留一段可配置的时间，用于查询和供依赖任务使用。

## 架构与设计

### 核心模块

* **`task-macro`:** 一个过程宏 crate，定义了 `#[sync_task]` 和 `#[async_task]` 属性。这些宏利用 `ctor` crate
  在应用程序启动时自动将带有注解的函数注册到全局注册表中。
* **`src/bin/task-scheduler.rs`:** 主要的二进制入口点。解析命令行参数，初始化日志，构建集群与调度器，启动 `tonic` gRPC
  服务器（可选 TLS），并在 `Ctrl+C` 时优雅关闭。
* **`src/scheduler/`:** 节点的集群感知调度器。负责跨集群解析依赖、执行或转发任务、处理取消、超时和并发上限。`store.rs`
  保存任务记录及其生命周期状态。
* **`src/cluster/`:** 点对点通信层。维护到各 peer 的惰性连接 gRPC 通道，运行心跳（存活状态、方法列表、负载），在 peer
  上定位任务并转发请求。
* **`src/server/service.rs`:** 轻量的 `tonic` 适配层，将 gRPC `TaskScheduler` 服务映射到调度器，并将错误转换为 gRPC 状态码。
* **`src/tasks/`:** 任务注册（`registry.rs`）、内置示例任务（`builtin.rs`）和动态库插件（`dynamic.rs`）。
* **`src/models/`:** 内部数据结构，包括表示解码后任务参数的 `ArgValue` 枚举。
* **`src/error/mod.rs`:** 使用 `thiserror` 定义的 `TaskError` 类型。
* **`src/logger.rs`:** 使用 `tracing` 将日志输出到控制台和 `logs/` 目录下的带时间戳的文件中。
* **`build.rs`:** 在构建过程中使用 `tonic_build` 将 `.proto` 定义编译成 Rust 代码。

### 任务注册

- **使用宏进行注册:**
  内置任务使用 `#[sync_task]`（同步任务）或 `#[async_task]`（异步任务）注册。这些宏利用
  [`ctor`](https://crates.io/crates/ctor) crate 在程序启动时自动运行注册代码。同步任务的签名为
  `fn(Vec<ArgValue>) -> Result<String>`。异步任务可以返回 `String` 或 `Result<String>`，返回 `Err` 会将任务标记为失败。

- **动态库插件:**
  外部任务可以通过动态库提供（Linux 上的 `.so`、Windows 上的 `.dll`、macOS 上的 `.dylib`）。这些库必须暴露一个签名为
  `unsafe fn() -> &'static [(&'static str, bool, usize)]` 的 `init_plugin` 函数，每个元组表示一个任务：
  `(task_name, is_async, function_pointer_address)`。`DynamicTaskLoader` 扫描配置的目录（默认 `./libraries`，可通过
  `--library-dir` 配置），加载这些库，调用 `init_plugin` 并注册发现的任务。插件中的任务名称会自动添加插件名作为前缀（例如
  `plugin_name::task_name`）。

- **全局任务注册表 (`src/tasks/registry.rs`):**
  `TaskRegistry` 使用一个以方法名为键的 `DashMap` 存储所有任务，同步和异步任务会被自动识别。每个条目保存自己的处理函数，
  保证动态插件函数始终按名称分发。同步任务在 `tokio` 的阻塞线程池中执行，不会阻塞异步运行时。

### 执行流程 (gRPC `SubmitTask`)

1. 校验请求（任务 id 和方法名非空，不能依赖自身）。
2. 如果任务 id 已存在，直接返回已有任务而不会再次执行（幂等）。
3. 如果方法未在本地注册，任务会被转发给提供该方法、负载最低且存活的 peer（见 [集群](#集群)）。
4. 将参数解码为 `Vec<ArgValue>`，并创建 `PENDING` 状态的记录。
5. 在依赖超时时间内并发等待所有依赖（本地或 peer 上）。可选地将依赖结果插入到参数前面（`inject_dependency_results`）。
6. 获取并发上限的执行槽位，记录进入 `RUNNING`，执行任务（可选执行超时）。
7. 记录进入 `SUCCESS`、`FAILED` 或 `CANCELLED`。发生 panic 的任务会被记录为 `FAILED`。
8. 阻塞式提交在任务结束后返回，分离式提交（`detached = true`）在第 4 步后立即返回 `PENDING`。

### 集群

节点之间通过静态的 `--peers` 列表互联，没有中心协调者，也不需要外部中间件。

- **心跳:** 每个节点定期调用 peer 的 `GetNodeInfo`，获取其 id、存活状态、已注册方法和负载。宕机的 peer 会被标记为不可用，
  恢复后自动重新接入。
- **跨节点依赖:** 当依赖在本地不存在时，节点会通过 `GetResult` 并发询问所有 peer，并通过 `WaitResult` 长轮询跟踪拥有该任务的
  peer。在整个集群中都不存在的依赖会以退避方式重试，因此依赖可以晚于依赖它的任务提交。即使 peer 无响应，每次查找也受剩余依赖超时时间约束。
- **失败传播:** 以 `FAILED` 或 `CANCELLED` 结束的依赖会使依赖它的任务以 `FAILED_PRECONDITION` 失败，错误信息包含依赖的 id
  及其所在节点。未能按时完成的依赖会使依赖它的任务以 `DEADLINE_EXCEEDED` 失败。
- **转发:** 方法未在本地注册的任务只会被转发一次。转发的请求携带来源节点 id，不会被再次转发，从而避免循环。在接收节点上查询被转发的任务时，
  请求会被代理到实际执行的节点。可以通过请求中的 `disable_forwarding` 禁用转发。
- **集群范围取消:** 在任意节点上调用 `CancelTask` 都会定位拥有该任务的节点并在那里取消。
- **节点 id:** 节点 id 必须唯一。默认使用监听地址；监听 `0.0.0.0` 等未指定地址时使用 `主机名:端口`。如果某个 peer 上报的 id
  与本节点相同，会输出警告日志。

### gRPC 接口

服务定义位于 [`../proto/task_scheduler.proto`](../proto/task_scheduler.proto)：

| RPC           | 说明                                                       |
|---------------|----------------------------------------------------------|
| `SubmitTask`  | 提交任务。默认阻塞直到任务结束；设置 `detached` 时立即返回 `PENDING`。          |
| `GetResult`   | 不等待，直接返回任务当前状态；节点不知道该任务时返回 `NOT_FOUND`。                 |
| `WaitResult`  | 等待任务结束或 `timeout_ms` 到期（最长 30 秒）。                       |
| `CancelTask`  | 取消尚未结束的任务，无论它在哪个节点上运行。                                  |
| `GetNodeInfo` | 返回节点 id、已注册方法、等待中与运行中的任务数，以及 peer 状态。                   |

`TaskRequest` 的重要字段：

| 字段                          | 说明                                         |
|-----------------------------|--------------------------------------------|
| `task_id`                   | 集群范围内唯一的 id。重复提交已知 id 会返回已有任务。               |
| `method`                    | 已注册任务函数的名称。                                |
| `args`                      | 打包为 `google.protobuf.Any` 的参数。             |
| `deps`                      | 必须先成功完成的任务 id，可以位于任意节点。                     |
| `detached`                  | 立即返回 `PENDING` 而不等待任务完成。                   |
| `dependency_timeout_ms`     | 等待依赖的最长时间，`0` 使用 `--dependency-timeout-ms`。 |
| `inject_dependency_results` | 将依赖结果（字符串，按 `deps` 顺序）插入到参数前面。              |
| `execution_timeout_ms`      | 任务本身的最长执行时间，`0` 表示不限制。                     |
| `disable_forwarding`        | 方法不在本地时直接以 `NOT_FOUND` 失败，而不是转发。            |
| `is_async`                  | 已废弃且会被忽略，同步和异步方法会被自动识别。                    |

阻塞式提交失败时以 gRPC 错误返回：

| 状态码                   | 原因                    |
|-----------------------|-----------------------|
| `NOT_FOUND`           | 没有任何可达节点注册了该方法。       |
| `INVALID_ARGUMENT`    | 请求无效，或参数被任务拒绝。        |
| `FAILED_PRECONDITION` | 依赖失败或被取消。             |
| `DEADLINE_EXCEEDED`   | 依赖超时或执行超时。            |
| `CANCELLED`           | 任务被取消。                |
| `INTERNAL`            | 任务执行失败或发生 panic。      |

被转发任务的错误会保留实际执行节点返回的状态码。

### 参数转换

传入的 `Any` 参数会被转换为 `ArgValue` 枚举，使任务函数能够处理带类型的 Rust 值。支持的类型包括：

- 整数 (i32, i64, u32, u64)
- 浮点数 (f32, f64)
- 布尔值
- 字符串
- 字节数组 (`Vec<u8>`)
- 嵌套数组 (通过 `ListValue` 实现的 `Vec<ArgValue>`)
- 嵌套映射 (通过 `MapValue` 实现的 `HashMap<String, ArgValue>`)

### 高性能考量

1. **并发数据结构:** 任务注册表和任务存储使用 `DashMap` 实现低争用访问，任务状态通过 `tokio::sync::watch` 通道发布，等待方无需轮询即可收到通知。
2. **异步运行时:** gRPC 服务器、依赖解析和异步任务运行在 `tokio` 上，同步任务运行在阻塞线程池中。
3. **长轮询:** 跨节点等待使用 `WaitResult` 长轮询，而不是高频轮询。
4. **高效序列化:** 使用 `prost` 处理 Protobuf 消息。

### TLS 配置

- **服务器认证:** 提供 `--tls-cert` 和 `--tls-key`（PEM）以启用 TLS。
- **双向 TLS (mTLS):** 额外提供 `--tls-ca-cert`，要求客户端出示由该 CA 签发的证书。
- **Peer:** 使用 `https://` URI 配置的 peer 会通过 TLS 连接。`--peer-ca-cert` 指定用于验证 peer 的 CA（否则使用 WebPKI
  根证书），`--peer-tls-cert` 和 `--peer-tls-key` 指定连接要求 mTLS 的 peer 时使用的客户端身份。

如果未提供 TLS 参数，服务器将以不加密的方式运行。

## 使用方法

### 注册内置任务

```rust
use task_macro::{async_task, sync_task};
use task_scheduler::error::{Result, TaskError};
use task_scheduler::models::ArgValue;

#[sync_task]
pub fn add(args: Vec<ArgValue>) -> Result<String> {
    let sum: Result<i32> = args
        .into_iter()
        .map(|value| match value {
            ArgValue::Int32(number) => Ok(number),
            _ => Err(TaskError::InvalidArguments("Expected Int32".to_string())),
        })
        .sum();
    Ok(sum?.to_string())
}

#[async_task]
pub async fn long_running_task(args: Vec<ArgValue>) -> Result<String> {
    tokio::time::sleep(std::time::Duration::from_secs(1)).await;
    Ok(format!("Processed {} args", args.len()))
}
```

内置任务必须位于被链接进二进制文件的模块中，例如 `src/tasks/builtin.rs`。

### 创建动态库插件

1. 创建一个 `crate-type = ["cdylib"]` 的 Rust 库项目。
2. 以路径依赖的方式添加 `task-scheduler`。
3. 使用 `#[no_mangle]` 定义任务函数，并实现返回任务元数据的 `init_plugin`。
4. 构建库并将其复制到 `--library-dir` 指定的目录（默认 `./libraries`）。

完整插件请参考 [`examples/plugin_example`](examples/plugin_example)。

插件注意事项：

- 插件链接的是自己的一份 `tokio`，无法使用调度器的运行时。使用定时器或 I/O 的异步插件任务必须运行在插件自己持有的运行时上，
  并返回一个等待 join handle 的 future，参见示例。
- 插件内部的 panic 无法被调度器捕获，会导致整个进程中止。插件必须返回错误而不是 panic。
- 插件必须使用与调度器相同的编译器和 `task-scheduler` 版本构建，因为任务函数通过 Rust ABI 交换。

### 启动节点

```bash
cargo build --release

# 单机节点
./target/release/task-scheduler --addr 0.0.0.0:50051

# 使用 TLS 和客户端证书认证 (mTLS)
./target/release/task-scheduler --addr 0.0.0.0:50051 --tls-cert server.crt --tls-key server.key --tls-ca-cert client_ca.crt

# 同时启动交互式 CLI
./target/release/task-scheduler --cli
```

### 启动集群

每个节点将其他节点列为 peer，并使用唯一的 id：

```bash
# 机器 1
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-1 --peers http://10.0.0.2:50051,http://10.0.0.3:50051

# 机器 2
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-2 --peers http://10.0.0.1:50051,http://10.0.0.3:50051

# 机器 3
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-3 --peers http://10.0.0.1:50051,http://10.0.0.2:50051
```

节点可以按任意顺序启动，peer 连接是惰性建立的，并由心跳自动恢复。

### 命令行参数

| 参数                                  | 默认值               | 说明                          |
|-------------------------------------|-------------------|-----------------------------|
| `-a`, `--addr`                      | `127.0.0.1:50051` | 监听地址。                       |
| `-l`, `--library-dir`               | `./libraries`     | 扫描动态库插件的目录。                 |
| `-c`, `--cli`                       | 关闭                | 在 gRPC 服务器旁启动交互式 CLI。       |
| `--tls-cert`, `--tls-key`           | 无                 | 服务器证书和私钥（PEM），启用 TLS。       |
| `--tls-ca-cert`                     | 无                 | 客户端证书的 CA，启用 mTLS。          |
| `--node-id`                         | 见 [集群](#集群)       | 本节点的唯一 id。                  |
| `--peers`                           | 无                 | 以逗号分隔的 peer URI。            |
| `--peer-ca-cert`                    | 无                 | 用于验证 `https` peer 的 CA。     |
| `--peer-tls-cert`, `--peer-tls-key` | 无                 | 连接要求 mTLS 的 peer 时使用的客户端身份。 |
| `--heartbeat-interval-ms`           | `3000`            | peer 心跳间隔。                  |
| `--peer-timeout-ms`                 | `5000`            | 短 peer 请求（心跳、查找、取消）的超时时间。   |
| `--dependency-timeout-ms`           | `30000`           | 默认的依赖等待时间。                  |
| `--max-concurrent-tasks`            | `0`               | 最大并发执行任务数，`0` 表示不限制。        |
| `--result-ttl-secs`                 | `600`             | 已完成结果的保留时间。                 |

### 交互式 CLI 模式

使用 `--cli` 时，交互式控制台会与 gRPC 服务器同时运行。可用命令：

- `help`: 显示可用命令列表。
- `list`: 列出所有已注册的任务（内置和来自插件的）。
- `plugins`: 列出已加载的插件及其提供的任务。
- `load <plugin_name>`: 按名称加载插件（例如 `load my_plugin` 会查找 `libmy_plugin.so` 或类似文件）。
- `unload <plugin_name>`: 卸载插件并注销其任务。
- `reload`: 重新扫描库目录并加载所有可用插件。
- `exit`: 退出 CLI，服务器继续运行。

## 限制

- 不检测依赖环，涉及的任务会以依赖超时失败。
- 正在运行的同步任务无法被中断。被取消或超时后其结果会被丢弃，但线程会继续运行直到函数返回。
- peer 是静态配置的，不支持动态节点发现。
- 任务结果仅保存在内存中，节点重启后会丢失。内存占用由 `--result-ttl-secs` 限制，而不是最大记录数。

## 测试

```bash
# 构建插件测试和转发测试使用的示例插件
(cd examples/plugin_example && cargo build)
mkdir -p libraries && cp examples/plugin_example/target/debug/libplugin_example.so libraries/

cargo test
```

`tests/cluster.rs` 和 `tests/cluster_resilience.rs` 会启动真实的多节点集群，覆盖跨节点依赖、转发、取消、节点宕机与恢复、
无响应 peer、并发上限以及结果过期清理。
