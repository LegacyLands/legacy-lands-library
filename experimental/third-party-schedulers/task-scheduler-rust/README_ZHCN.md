# Task Scheduler (Rust) - 任务调度器

Task Scheduler 是一个使用 Rust 编写的高性能任务执行后端。它允许客户端通过 Protocol Buffers 定义的 gRPC 接口提交任务（可能带有依赖关系）。系统利用 Rust 的异步能力（`tokio`）、并发特性和高效的数据结构，旨在实现快速、健壮且可扩展的任务执行。

## 概述

Task Scheduler 提供了一个 gRPC 服务来执行已注册的任务。它支持同步和异步的任务函数。任务参数以 Protobuf `Any` 消息的形式传递，然后在调度器内部执行前被解码为带类型的 Rust 值。已完成任务的结果会被临时缓存。

## 架构与设计

### 核心模块

*   **`task-macro`:** 一个过程宏 crate，定义了 `#[sync_task]` 和 `#[async_task]` 属性。这些宏利用 `ctor` crate 在应用程序启动时自动将带有注解的函数注册到全局注册表中。
*   **`src/bin/task-scheduler.rs`:** 主要的二进制入口点。解析命令行参数（包括服务器地址和 TLS 选项），初始化日志（`src/logger.rs`），启动 `tonic` gRPC 服务器（`src/server/service.rs`），根据请求配置 TLS，并确保内置任务（`src/tasks/builtin.rs`）被链接。
*   **`src/logger.rs`:** 使用 `tracing` 配置应用程序日志，将日志输出到控制台和 `logs/` 目录下的带时间戳的文件中。提供了便捷的日志宏。
*   **`src/models/mod.rs` & `src/models/wrappers.rs`:** 定义内部数据结构，包括关键的 `ArgValue` 枚举（代表解码后的任务参数）以及用于 Protobuf 消息解码的辅助结构体。
*   **`src/tasks/mod.rs`, `src/tasks/registry.rs`, `src/tasks/builtin.rs`:** 管理任务的注册、存储和执行逻辑。包含全局的 `TaskRegistry` 和示例任务实现。
*   **`src/server/mod.rs` & `src/server/service.rs`:** 使用 `tonic` 实现 `TaskScheduler` gRPC 服务。处理传入的请求，与 `TaskRegistry` 交互，并管理结果缓存。
*   **`src/error.rs`:** 使用 `thiserror` 定义应用程序的自定义错误类型。
*   **`build.rs`:** 在构建过程中使用 `tonic_build` 将 `.proto` 定义编译成 Rust 代码。

### 任务注册与执行

- **使用宏注册:**
  任务（普通函数）使用 `#[sync_task]` 属性注册同步任务，或使用 `#[async_task]` 注册异步任务（返回 `Future` 的函数）。这些宏利用 [`ctor`](https://crates.io/crates/ctor) crate 在程序启动时自动运行注册代码，将函数指针及其名称（从函数标识符派生）添加到全局 `TaskRegistry` 中。

- **全局任务注册表 (`src/tasks/registry.rs`):**
  `TaskRegistry` 使用 `DashMap`（一个并发哈希映射）分别存储已注册的同步和异步任务。它提供了 gRPC 服务使用的核心 `execute_task` 方法。

- **执行流程 (gRPC `SubmitTask`):**
    1. gRPC 服务 (`src/server/service.rs`) 接收到一个 `TaskRequest`。
    2. 它调用 `TaskRegistry::convert_args` 将 `prost_types::Any` 参数解码为 `Vec<ArgValue>`。
    3. 它检查请求中列出的所有任务依赖项是否存在于结果缓存中 (`TaskRegistry::get_task_result`)。如果不存在，则返回错误。
    4. 它根据 `method` 名称在注册表中查找任务函数（同步或异步）。
    5. 它使用转换后的参数执行任务函数。异步任务会被 `await`。
    6. 结果 (`TaskResult`，包含状态和字符串值) 被存储在结果缓存中 (`TaskRegistry::cache_task_result`)。
    7. 一个 `TaskResponse`（包含任务 ID、状态和结果字符串）被发送回客户端。

### gRPC 接口与 Proto 集成

- **Protobuf 定义 (`task_scheduler.proto`):** 定义了 `TaskScheduler` gRPC 服务及其相关的数据结构 (消息)。
  - **服务 (`TaskScheduler`):**
    - `SubmitTask`: 提交任务执行，支持同步/异步模式，可定义任务依赖。
    - `GetResult`: 查询指定任务的执行结果和状态。
  - **主要消息:**
    - `TaskRequest`: 提交任务时使用的请求体，包含任务 ID、方法名、参数、依赖项和执行模式。
    - `TaskResponse`: `SubmitTask` 的响应体，包含任务 ID、状态和初步结果。
    - `ResultRequest`: 查询结果时使用的请求体，包含任务 ID。
    - `ResultResponse`: `GetResult` 的响应体，包含任务状态和最终结果。
    - 使用 `google.protobuf.Any` 来灵活处理不同类型的参数和结果。

- **通信流程:**
  客户端将任务参数编码为适当的 Protobuf 消息（例如 `google.protobuf.Int32Value`, `StringValue`, 自定义的 `ListValue`, `MapValue`），并将它们包装在 `google.protobuf.Any` 中。服务器在执行任务前将这些 `Any` 消息解码回内部的 `ArgValue` 枚举。

### 参数转换与依赖管理

- **参数处理 (`ArgValue` 枚举):**
  传入的 `Any` 参数在 `TaskRegistry` 中被转换为 `ArgValue` 枚举。这使得任务函数可以使用带类型的 Rust 值。支持的类型包括：
    - 整数 (i32, i64, u32, u64)
    - 浮点数 (f32, f64)
    - 布尔值
    - 字符串
    - 字节数组 (`Vec<u8>`)
    - 嵌套数组 (`Vec<ArgValue>`，通过 `ListValue`)
    - 嵌套映射 (`HashMap<String, ArgValue>`，通过 `MapValue`)

- **依赖跟踪与缓存:**
  在执行任务之前，调度器会检查其依赖项（`TaskRequest` 中的 `deps` 字段）。它在分片的 LRU 缓存 (`TaskRegistry` 中的 `Arc<[Mutex<LruCache<String, TaskResult>>]>`) 中查找每个依赖项的 `task_id`。如果在缓存中找不到任何依赖项的结果，任务执行将提前失败。成功的任务结果会被添加到此缓存中。该缓存有助于确保依赖任务仅在其前置任务在合理的时间范围内（由 LRU 策略决定）完成后才运行。

### 高性能考量

1.  **并发数据结构:**
    全局任务注册表使用 `DashMap` 来实现对已注册任务的高效、低竞争的并发访问。
2.  **异步运行时:**
    gRPC 服务器和异步任务运行在 `tokio` 运行时上，实现了非阻塞 I/O，并能高效处理大量并发连接和任务。
3.  **分片 LRU 缓存:**
    用于依赖检查的中间任务结果存储在一个跨多个 `parking_lot::Mutex` 实例分片的 LRU 缓存中。与单个全局锁相比，这减少了访问或更新缓存时的锁竞争。使用 `ahash` 进行快速哈希以确定分片。
4.  **高效序列化:**
    使用 `prost` 处理 Protobuf 消息，提供快速的序列化和反序列化。

### TLS 配置

服务器支持启用传输层安全 (TLS) 以进行加密通信 (gRPCs)。这需要通过命令行参数提供服务器证书和密钥文件。

- **服务器认证:** 提供 `--tls-cert`（PEM 格式的证书链）和 `--tls-key`（PEM 格式的私钥）参数以启用 TLS。服务器将向连接的客户端出示此证书。
- **双向 TLS (mTLS):** 如果需要额外要求客户端出示有效证书进行身份验证，请提供 `--tls-ca-cert` 参数以及签署了允许的客户端证书的 CA 证书（PEM 格式）的路径。如果提供了此参数，则只有拥有由此 CA 签署的证书的客户端才能连接。

如果未提供 TLS 参数，服务器将在没有加密的情况下运行。

## 使用方法

### 注册任务

使用 `#[sync_task]` 或 `#[async_task]` 属性注册任务。函数签名应接受 `Vec<ArgValue>` 并返回 `String`。

```rust
use task_scheduler::models::ArgValue;
use task_macro::{sync_task, async_task};
use std::time::Duration;

#[sync_task]
pub fn add(args: Vec<ArgValue>) -> String {
    // 示例：对 i32 参数求和
    let sum: i32 = args.into_iter()
        .filter_map(|v| if let ArgValue::Int32(n) = v { Some(n) } else { None })
        .sum();
    sum.to_string()
}

#[async_task]
pub async fn long_running_task(args: Vec<ArgValue>) -> String {
    // 模拟工作
    tokio::time::sleep(Duration::from_secs(1)).await;
    format!("Processed async task with {} args", args.len())
}

// 确保在 src/bin/task-scheduler.rs 或 lib.rs 中导入包含任务的模块
// 例如：use task_scheduler::tasks::builtin;
```

### gRPC 端点

后端公开了两个在 `proto/task_scheduler.proto` 中定义的主要 gRPC 端点：

- **`SubmitTask(TaskRequest) returns (TaskResponse)`:**
  提交一个任务以供执行。需要提供唯一的 `task_id`、已注册的 `method` 名称（例如 `"add"`）、编码为 `google.protobuf.Any` 的参数、依赖的 `task_id` 列表以及 `is_async` 标志。返回最终状态和结果字符串。

- **`GetResult(ResultRequest) returns (ResultResponse)`:**
  使用其 `task_id` 检索先前提交任务的缓存结果。如果在缓存中找到，则返回状态和结果字符串。

## CLI 和 gRPC 用法

### 启动服务器

使用 `cargo` 构建并运行服务器：

```bash
cargo build --release
# 不使用 TLS
./target/release/task-scheduler --addr "0.0.0.0:50051"

# 使用 TLS (仅服务器证书)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key

# 使用 TLS 和客户端证书认证 (mTLS)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key --tls-ca-cert path/to/client_ca.crt

# 或使用 cargo run (带 TLS 的示例):
# cargo run --release -- --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key
```

- 使用 `--addr` (或 `-a`) 选项指定主机和端口。默认为 `127.0.0.1:50051`。
- 使用 `--tls-cert` 和 `--tls-key` 提供服务器的证书和私钥文件（PEM 格式）以启用 TLS。
- 可选地，使用 `--tls-ca-cert` 提供 CA 证书文件（PEM 格式）以验证客户端证书（启用双向 TLS - mTLS）。

### gRPC 交互示例 (概念性)

客户端需要使用与 Protobuf 兼容的 gRPC 库。

1.  **定义参数:** 为您的参数创建 Protobuf 消息（例如 `google.protobuf.Int32Value`, `StringValue`）。
2.  **包装在 `Any` 中:** 将每个参数消息编码为 `google.protobuf.Any` 消息，正确设置 `type_url`（例如 `"type.googleapis.com/google.protobuf.Int32Value"`）并将 `value` 设置为参数消息的序列化字节。
3.  **创建 `TaskRequest`:** 构建 `TaskRequest`，包括 `task_id`、 `method` 名称（例如 `"add"`）、`Any` 参数列表、依赖项和 `is_async`。
4.  **调用 `SubmitTask`:** 将 `TaskRequest` 发送到服务器。`TaskResponse` 将在执行完成后包含结果。
5.  **调用 `GetResult` (可选):** 如果需要，稍后使用 `task_id` 调用 `GetResult` 以检索缓存的结果。

**一个值为 5 的 `Int32Value` 的 `Any` 结构示例:**

```protobuf
// 概念上:
Any {
  type_url: "type.googleapis.com/google.protobuf.Int32Value";
  // 'value' 包含 Int32Value { value: 5 } 的序列化字节
  value: bytes { ... };
}
```

请参阅 `proto/task_scheduler.proto` 文件以获取确切的消息定义。 