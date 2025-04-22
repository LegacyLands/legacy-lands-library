# Task Scheduler (Rust) - 任务调度器

Task Scheduler 是一个使用 Rust 编写的高性能任务执行后端。它允许客户端通过 Protocol Buffers 定义的 gRPC
接口提交任务（可能带有依赖关系）。系统利用 Rust 的异步能力（`tokio`）、并发特性和高效的数据结构，旨在实现快速、健壮且可扩展的任务执行。

## 概述

Task Scheduler 提供了一个 gRPC 服务来执行已注册的任务。它支持同步和异步的任务函数。任务参数以 Protobuf `Any`
消息的形式传递，然后在调度器内部执行前被解码为带类型的 Rust 值。已完成任务的结果会被临时缓存。

## 架构与设计

### 核心模块

* **`task-macro`:** 一个过程宏 crate，定义了 `#[sync_task]` 和 `#[async_task]` 属性。这些宏利用 `ctor` crate
  在应用程序启动时自动将带有注解的函数注册到全局注册表中。
* **`src/bin/task-scheduler.rs`:** 主要的二进制入口点。解析命令行参数（包括服务器地址和 TLS 选项），初始化日志（
  `src/logger.rs`），启动 `tonic` gRPC 服务器（`src/server/service.rs`），根据请求配置 TLS，并确保内置任务（
  `src/tasks/builtin.rs`）被链接。
* **`src/logger.rs`:** 使用 `tracing` 配置应用程序日志，将日志输出到控制台和 `logs/` 目录下的带时间戳的文件中。提供了便捷的日志宏。
* **`src/models/mod.rs` & `src/models/wrappers.rs`:** 定义内部数据结构，包括关键的 `ArgValue` 枚举（代表解码后的任务参数）以及用于
  Protobuf 消息解码的辅助结构体。
* **`src/tasks/mod.rs`, `src/tasks/registry.rs`, `src/tasks/builtin.rs`:** 管理任务的注册、存储和执行逻辑。包含全局的
  `TaskRegistry` 和示例任务实现。
* **`src/tasks/dynamic.rs`:** 实现加载、卸载和管理动态库插件（.so, .dll, .dylib）的逻辑。包含 `DynamicTaskLoader`。
* **`src/server/mod.rs` & `src/server/service.rs`:** 使用 `tonic` 实现 `TaskScheduler` gRPC 服务。处理传入的请求，与
  `TaskRegistry` 交互，并管理结果缓存。
* **`src/error.rs`:** 使用 `thiserror` 定义应用程序的自定义错误类型。
* **`build.rs`:** 在构建过程中使用 `tonic_build` 将 `.proto` 定义编译成 Rust 代码。

### 任务注册与执行

- **使用宏注册内置任务:**
  内置任务（普通函数）使用 `#[sync_task]` 属性注册同步任务，或使用 `#[async_task]` 注册异步任务（返回 `Future`
  的函数）。这些宏利用 [`ctor`](https://crates.io/crates/ctor) crate 在程序启动时自动运行注册代码，将函数指针及其名称（从函数标识符派生）添加到全局
  `TaskRegistry` 中。

- **动态库插件:**
  外部任务可以通过动态库（例如 Linux 上的 `.so`，Windows 上的 `.dll`，macOS 上的 `.dylib`）提供。这些库必须导出一个
  `init_plugin` 函数，其签名为 `unsafe fn() -> &'static [(&'static str, bool, usize)]`。此函数返回一个静态切片，其中每个元组代表一个任务：
  `(任务名称, 是否异步, 函数指针地址)`。`DynamicTaskLoader` 会扫描一个配置的目录（默认为 `./libraries`，可通过
  `--library-dir` 配置），加载这些库，调用 `init_plugin`，并将发现的任务注册到 `TaskRegistry`
  中。来自插件的任务名称会自动添加插件名称前缀（从库文件名派生，例如 `插件名::任务名`）。

- **全局任务注册表 (`src/tasks/registry.rs`):**
  `TaskRegistry` 使用 `DashMap`（一个并发哈希映射）分别存储已注册的同步和异步任务。它区分内置任务和动态加载的任务。它提供了
  gRPC 服务使用的核心 `execute_task` 方法。

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
  客户端将任务参数编码为适当的 Protobuf 消息（例如 `google.protobuf.Int32Value`, `StringValue`, 自定义的 `ListValue`,
  `MapValue`），并将它们包装在 `google.protobuf.Any` 中。服务器在执行任务前将这些 `Any` 消息解码回内部的 `ArgValue` 枚举。

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
  在执行任务之前，调度器会检查其依赖项（`TaskRequest` 中的 `deps` 字段）。它在分片的 LRU 缓存 (`TaskRegistry` 中的
  `Arc<[Mutex<LruCache<String, TaskResult>>]>`) 中查找每个依赖项的 `task_id`
  。如果在缓存中找不到任何依赖项的结果，任务执行将提前失败。成功的任务结果会被添加到此缓存中。该缓存有助于确保依赖任务仅在其前置任务在合理的时间范围内（由
  LRU 策略决定）完成后才运行。

### 高性能考量

1. **并发数据结构:**
   全局任务注册表使用 `DashMap` 来实现对已注册任务的高效、低竞争的并发访问。
2. **异步运行时:**
   gRPC 服务器和异步任务运行在 `tokio` 运行时上，实现了非阻塞 I/O，并能高效处理大量并发连接和任务。
3. **分片 LRU 缓存:**
   用于依赖检查的中间任务结果存储在一个跨多个 `parking_lot::Mutex` 实例分片的 LRU 缓存中。与单个全局锁相比，这减少了访问或更新缓存时的锁竞争。使用
   `ahash` 进行快速哈希以确定分片。
4. **高效序列化:**
   使用 `prost` 处理 Protobuf 消息，提供快速的序列化和反序列化。

### TLS 配置

服务器支持启用传输层安全 (TLS) 以进行加密通信 (gRPCs)。这需要通过命令行参数提供服务器证书和密钥文件。

- **服务器认证:** 提供 `--tls-cert`（PEM 格式的证书链）和 `--tls-key`（PEM 格式的私钥）参数以启用 TLS。服务器将向连接的客户端出示此证书。
- **双向 TLS (mTLS):** 如果需要额外要求客户端出示有效证书进行身份验证，请提供 `--tls-ca-cert` 参数以及签署了允许的客户端证书的
  CA 证书（PEM 格式）的路径。如果提供了此参数，则只有拥有由此 CA 签署的证书的客户端才能连接。

如果未提供 TLS 参数，服务器将在没有加密的情况下运行。

## 使用方法

### 注册内置任务

使用 `#[sync_task]` 或 `#[async_task]` 属性注册内置任务。函数签名应接受 `Vec<ArgValue>` 并返回 `String`。

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

示例项目可以查看 [examples](examples/plugin_example)

### 创建动态库插件

1. 创建一个新的 Rust 库项目 (`cargo new --lib my_plugin`)。
2. 在 `Cargo.toml` 中将 `crate-type` 设置为 `["cdylib"]`。
3. 使用路径将 `task-scheduler` 添加为依赖项：`task-scheduler = { path = "../../path/to/task-scheduler" }`。
4. 定义您的任务函数（同步或异步），类似于内置任务，但它们不需要 `#[sync_task]` 或 `#[async_task]` 属性。
5. 实现 `init_plugin` 函数（如架构部分所述），以返回有关您的任务的元数据。使用 `my_sync_task as usize` 和
   `my_async_task as usize` 获取函数指针地址。
6. 构建库 (`cargo build`)。
7. 将生成的动态库文件（例如 `target/debug/libmy_plugin.so`）复制到 `--library-dir` 参数指定的目录中（默认为 `./libraries`）。

```rust
// 示例插件 src/lib.rs
use std::future::Future;
use std::pin::Pin;
use task_scheduler::error::{Result, TaskError}; // 假设 Result 是 std::result::Result<String, TaskError> 的别名
use task_scheduler::models::ArgValue;

// 插件中的示例同步任务
#[no_mangle] // 对 C ABI 兼容性很重要
pub unsafe fn plugin_multiply(args: Vec<ArgValue>) -> Result<String> {
    let product: std::result::Result<i32, TaskError> = args.into_iter().try_fold(1, |acc, v| match v {
        ArgValue::Int32(n) => Ok(acc * n),
        _ => Err(TaskError::InvalidArguments("Expected Int32".to_string())),
    });
    Ok(product?.to_string())
}

// 插件中的示例异步任务
#[no_mangle]
pub unsafe fn plugin_echo(args: Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>> {
    Box::pin(async move {
        let msg = args.iter().filter_map(|a| if let ArgValue::String(s) = a { Some(s.as_str()) } else { None }).collect::<Vec<_>>().join(" ");
        format!("Plugin echo: {}", msg)
    })
}

// 任务元数据结构
type TaskEntryInfo = (&'static str, bool, usize);

// 由调度器调用的初始化函数
#[no_mangle]
pub unsafe fn init_plugin() -> &'static [TaskEntryInfo] {
    static TASKS: [TaskEntryInfo; 2] = [
        ("multiply", false, plugin_multiply as usize), // (名称, 是否异步, 函数指针地址)
        ("echo", true, plugin_echo as usize),
    ];
    &TASKS
}
```

### 启动服务器

使用 `cargo` 构建并运行服务器：

```bash
cargo build --release

# 不使用 TLS，默认库目录 ('./libraries')
./target/release/task-scheduler --addr "0.0.0.0:50051"

# 指定自定义库目录
./target/release/task-scheduler --addr "0.0.0.0:50051" --library-dir /path/to/custom/plugins

# 使用 TLS (仅服务器证书)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key

# 使用 TLS 和客户端证书认证 (mTLS)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key --tls-ca-cert path/to/client_ca.crt

# 启动交互式 CLI 模式
./target/release/task-scheduler --cli

# 或使用 cargo run (带 TLS 和 CLI 的示例):
# cargo run --release -- --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key --cli
```

- 使用 `--addr` (或 `-a`) 选项指定主机和端口。默认为 `127.0.0.1:50051`。
- 使用 `--library-dir` (或 `-l`) 指定要扫描动态库插件的目录。默认为 `./libraries`。
- 使用 `--tls-cert` 和 `--tls-key` 提供服务器的证书和私钥文件（PEM 格式）以启用 TLS。
- 可选地，使用 `--tls-ca-cert` 提供 CA 证书文件（PEM 格式）以验证客户端证书（启用双向 TLS - mTLS）。
- 使用 `--cli` (或 `-c`) 以交互式命令行界面模式启动服务器，而不是直接启动 gRPC 服务器。

### 交互式 CLI 模式

如果使用 `--cli` 标志启动，应用程序将进入交互模式，您可以在其中管理插件和查看任务。在此模式下，gRPC 服务器**不会**启动。

可用命令：

- `help`: 显示可用命令列表。
- `list`: 列出所有当前已注册的任务（包括内置任务和来自插件的任务）。
- `plugins`: 列出所有当前已加载的动态库插件及其提供的任务。
- `load <plugin_name>`: 按名称加载动态库插件（例如 `load my_plugin` 将在库目录中查找 `libmy_plugin.so` 或类似文件）。
- `unload <plugin_name>`: 卸载当前加载的插件并取消注册其任务。
- `reload`: 卸载所有当前加载的插件，并重新扫描库目录以加载所有可用的插件。
- `exit`: 退出 CLI 应用程序。

### gRPC 端点交互示例 (概念性)

客户端需要使用与 Protobuf 兼容的 gRPC 库。

1. **定义参数:** 为您的参数创建 Protobuf 消息（例如 `google.protobuf.Int32Value`, `StringValue`）。
2. **包装在 `Any` 中:** 将每个参数消息编码为 `google.protobuf.Any` 消息，正确设置 `type_url`（例如
   `"type.googleapis.com/google.protobuf.Int32Value"`）并将 `value` 设置为参数消息的序列化字节。
3. **创建 `TaskRequest`:** 构建 `TaskRequest`，包括 `task_id`、 `method` 名称（例如 `"add"`）、`Any` 参数列表、依赖项和
   `is_async`。
4. **调用 `SubmitTask`:** 将 `TaskRequest` 发送到服务器。`TaskResponse` 将在执行完成后包含结果。
5. **调用 `GetResult` (可选):** 如果需要，稍后使用 `task_id` 调用 `GetResult` 以检索缓存的结果。

**一个值为 5 的 `Int32Value` 的 `Any` 结构示例:**

```protobuf
// 概念上:
    Any {
type_url: "type.googleapis.com/google.protobuf.Int32Value";
// 'value' 包含 Int32Value { value: 5 } 的序列化字节
    value: bytes {...};
    }
```

请参阅 `proto/task_scheduler.proto` 文件以获取确切的消息定义。 