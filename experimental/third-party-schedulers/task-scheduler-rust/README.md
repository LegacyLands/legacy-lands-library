# Task Scheduler (Rust)

Task Scheduler is a high-performance task execution backend written in Rust. It allows clients to submit tasks,
potentially with dependencies, via a gRPC interface defined using Protocol Buffers. Leveraging Rust's asynchronous
capabilities (`tokio`), concurrency features, and efficient data structures, the system aims for fast, robust, and
scalable task execution.

## Overview

Task Scheduler provides a gRPC service for executing registered tasks. It supports both synchronous and asynchronous
task functions. Task arguments are passed as Protobuf `Any` messages, which are then decoded into typed Rust values
within the scheduler before execution. Results of completed tasks are cached temporarily.

## Architecture & Design

### Core Modules

* **`task-macro`:** A procedural macro crate defining `#[sync_task]` and `#[async_task]` attributes. These macros
  automatically register the annotated functions into a global registry upon application startup using the `ctor` crate.
* **`src/bin/task-scheduler.rs`:** The main binary entry point. Parses command-line arguments (including server address
  and TLS options), initializes logging (`src/logger.rs`), starts the `tonic` gRPC server (`src/server/service.rs`),
  configures TLS if requested, and ensures built-in tasks (`src/tasks/builtin.rs`) are linked.
* **`src/logger.rs`:** Configures application logging using `tracing` to output to both console and timestamped files in
  the `logs/` directory. Provides convenient logging macros.
* **`src/models/mod.rs` & `src/models/wrappers.rs`:** Defines internal data structures, including the crucial `ArgValue`
  enum which represents decoded task arguments, and helper structs for Protobuf message decoding.
* **`src/tasks/mod.rs`, `src/tasks/registry.rs`, `src/tasks/builtin.rs`:** Manages task registration, storage, and
  execution logic. Contains the global `TaskRegistry` and example task implementations.
* **`src/tasks/dynamic.rs`:** Implements the logic for loading, unloading, and managing dynamic library plugins (.so,
  .dll, .dylib). Includes the `DynamicTaskLoader`.
* **`src/server/mod.rs` & `src/server/service.rs`:** Implements the `TaskScheduler` gRPC service using `tonic`. Handles
  incoming requests, interacts with the `TaskRegistry`, and manages the result cache.
* **`src/error.rs`:** Defines custom error types for the application using `thiserror`.
* **`build.rs`:** Uses `tonic_build` to compile the `.proto` definitions into Rust code during the build process.

### Task Registration and Execution

- **Registration with Macros:**
  Built-in tasks (regular functions) are registered using the `#[sync_task]` attribute for synchronous tasks or
  `#[async_task]` for asynchronous tasks (functions returning a `Future`). These macros leverage the [
  `ctor`](https://crates.io/crates/ctor) crate to run registration code automatically when the program starts, adding
  the function pointer and its name (derived from the function identifier) to the global `TaskRegistry`.

- **Dynamic Library Plugins:**
  External tasks can be provided via dynamic libraries (e.g., `.so` on Linux, `.dll` on Windows, `.dylib` on macOS).
  These libraries must expose an `init_plugin` function with the signature
  `unsafe fn() -> &'static [(&'static str, bool, usize)]`. This function returns a static slice where each tuple
  represents a task: `(task_name, is_async, function_pointer_address)`. The `DynamicTaskLoader` scans a configured
  directory (default: `./libraries`, configurable via `--library-dir`), loads these libraries, calls `init_plugin`, and
  registers the discovered tasks in the `TaskRegistry`. Task names from plugins are automatically prefixed with the
  plugin name (derived from the library filename, e.g., `plugin_name::task_name`).

- **Global Task Registry (`src/tasks/registry.rs`):**
  The `TaskRegistry` uses `DashMap` (a concurrent hash map) to store registered synchronous and asynchronous tasks
  separately. It differentiates between built-in tasks and dynamically loaded tasks. It provides the core `execute_task`
  method used by the gRPC service.

- **Execution Flow (gRPC `SubmitTask`):**
    1. The gRPC service (`src/server/service.rs`) receives a `TaskRequest`.
    2. It calls `TaskRegistry::convert_args` to decode the `prost_types::Any` arguments into a `Vec<ArgValue>`.
    3. It checks if all task dependencies listed in the request are present in the results cache (
       `TaskRegistry::get_task_result`). If not, it returns an error.
    4. It looks up the task function (sync or async) in the registry based on the `method` name.
    5. It executes the task function with the converted arguments. Asynchronous tasks are awaited.
    6. The result (`TaskResult` containing status and a string value) is stored in the results cache (
       `TaskRegistry::cache_task_result`).
    7. A `TaskResponse` (containing task ID, status, and result string) is sent back to the client.

### gRPC Interface and Proto Integration

- **Protobuf Definition (`task_scheduler.proto`):**
  The service interface is defined using Protocol Buffers. Key elements include:
    - **Service (`TaskScheduler`):**
        - `SubmitTask`: Submits a task for execution, supporting sync/async modes and dependency definition.
        - `GetResult`: Queries the execution result and status of a specified task.
    - **Main Messages:**
        - `TaskRequest`: The request body used when submitting a task, containing task ID, method name, arguments,
          dependencies, and execution mode.
        - `TaskResponse`: The response body for `SubmitTask`, containing task ID, status, and initial result.
        - `ResultRequest`: The request body used when querying a result, containing the task ID.
        - `ResultResponse`: The response body for `GetResult`, containing task status and the final result.
    - Utilizes `google.protobuf.Any` to flexibly handle different types of arguments and results.

- **Communication Flow:**
  Clients encode task arguments into appropriate Protobuf messages (e.g., `google.protobuf.Int32Value`, `StringValue`,
  custom `ListValue`, `MapValue`) and wrap them in `google.protobuf.Any`. The server decodes these `Any` messages back
  into the internal `ArgValue` enum before executing the task.

### Argument Conversion and Dependency Management

- **Parameter Handling (`ArgValue` enum):**
  Incoming `Any` arguments are converted into the `ArgValue` enum within the `TaskRegistry`. This allows task functions
  to work with typed Rust values. Supported types include:
    - Integers (i32, i64, u32, u64)
    - Floating point numbers (f32, f64)
    - Booleans
    - Strings
    - Byte arrays (`Vec<u8>`)
    - Nested Arrays (`Vec<ArgValue>` via `ListValue`)
    - Nested Maps (`HashMap<String, ArgValue>` via `MapValue`)

- **Dependency Tracking & Caching:**
  Before executing a task, the scheduler checks its dependencies (`deps` field in `TaskRequest`). It looks up each
  dependency `task_id` in a sharded LRU cache (`Arc<[Mutex<LruCache<String, TaskResult>>]>` in `TaskRegistry`). If any
  dependency's result is not found in the cache, the task execution fails early. Successful task results are added to
  this cache. The cache helps ensure that dependent tasks only run after their prerequisites are complete within a
  reasonable timeframe (defined by LRU eviction).

### High Performance Considerations

1. **Concurrent Data Structures:**
   The global task registry uses `DashMap` for efficient, low-contention concurrent access to registered tasks.
2. **Asynchronous Runtime:**
   The gRPC server and asynchronous tasks run on the `tokio` runtime, enabling non-blocking I/O and efficient handling
   of many concurrent connections and tasks.
3. **Sharded LRU Caching:**
   Intermediate task results for dependency checking are stored in an LRU cache sharded across multiple
   `parking_lot::Mutex` instances. This reduces lock contention compared to a single global lock when accessing or
   updating the cache. `ahash` is used for fast hashing to determine the shard.
4. **Efficient Serialization:**
   `prost` is used for Protobuf message handling, providing fast serialization and deserialization.

### TLS Configuration

The server supports enabling Transport Layer Security (TLS) for encrypted communication (gRPCs). This requires providing
server certificate and key files via command-line arguments.

- **Server Authentication:** Provide `--tls-cert` (certificate chain in PEM format) and `--tls-key` (private key in PEM
  format) arguments to enable TLS. The server will present this certificate to connecting clients.
- **Mutual TLS (mTLS):** To additionally require clients to present a valid certificate for authentication, provide the
  `--tls-ca-cert` argument with the path to the CA certificate (PEM format) that signed the allowed client certificates.
  If this argument is provided, only clients with certificates signed by this CA will be able to connect.

If TLS arguments are not provided, the server will run without encryption.

## Usage

### Registering Built-in Tasks

Register built-in tasks using the `#[sync_task]` or `#[async_task]` attributes. The function signature should accept
`Vec<ArgValue>` and return `String`.

```rust
use task_scheduler::models::ArgValue;
use task_macro::{sync_task, async_task};
use std::time::Duration;

#[sync_task]
pub fn add(args: Vec<ArgValue>) -> String {
    // Example: Sum i32 args
    let sum: i32 = args.into_iter()
        .filter_map(|v| if let ArgValue::Int32(n) = v { Some(n) } else { None })
        .sum();
    sum.to_string()
}

#[async_task]
pub async fn long_running_task(args: Vec<ArgValue>) -> String {
    // Simulate work
    tokio::time::sleep(Duration::from_secs(1)).await;
    format!("Processed async task with {} args", args.len())
}

// Make sure to import the module containing tasks in src/bin/task-scheduler.rs or lib.rs
// e.g., use task_scheduler::tasks::builtin;
```

For example projects, see [examples](examples/plugin_example)

### Creating Dynamic Library Plugins

1. Create a new Rust library project (`cargo new --lib my_plugin`).
2. Set the `crate-type` to `["cdylib"]` in `Cargo.toml`.
3. Add `task-scheduler` as a dependency using a path: `task-scheduler = { path = "../../path/to/task-scheduler" }`.
4. Define your task functions (sync or async) similar to built-in tasks, but they don't need the `#[sync_task]` or
   `#[async_task]` attributes.
5. Implement the `init_plugin` function as described in the Architecture section to return metadata about your tasks.
   Use `my_sync_task as usize` and `my_async_task as usize` to get the function pointer addresses.
6. Build the library (`cargo build`).
7. Copy the resulting dynamic library file (e.g., `target/debug/libmy_plugin.so`) into the directory specified by the
   `--library-dir` argument (default: `./libraries`).

```rust
// Example plugin src/lib.rs
use std::future::Future;
use std::pin::Pin;
use task_scheduler::error::{Result, TaskError}; // Assuming Result alias for std::result::Result<String, TaskError>
use task_scheduler::models::ArgValue;

// Example sync task within plugin
#[no_mangle] // Important for C ABI compatibility
pub unsafe fn plugin_multiply(args: Vec<ArgValue>) -> Result<String> {
    let product: std::result::Result<i32, TaskError> = args.into_iter().try_fold(1, |acc, v| match v {
        ArgValue::Int32(n) => Ok(acc * n),
        _ => Err(TaskError::InvalidArguments("Expected Int32".to_string())),
    });
    Ok(product?.to_string())
}

// Example async task within plugin
#[no_mangle]
pub unsafe fn plugin_echo(args: Vec<ArgValue>) -> Pin<Box<dyn Future<Output = String> + Send>> {
    Box::pin(async move {
        let msg = args.iter().filter_map(|a| if let ArgValue::String(s) = a { Some(s.as_str()) } else { None }).collect::<Vec<_>>().join(" ");
        format!("Plugin echo: {}", msg)
    })
}

// Task metadata structure
type TaskEntryInfo = (&'static str, bool, usize);

// Initialization function called by the scheduler
#[no_mangle]
pub unsafe fn init_plugin() -> &'static [TaskEntryInfo] {
    static TASKS: [TaskEntryInfo; 2] = [
        ("multiply", false, plugin_multiply as usize), // (name, is_async, fn_ptr_address)
        ("echo", true, plugin_echo as usize),
    ];
    &TASKS
}
```

### Starting the Server

Build and run the server using `cargo`:

```bash
cargo build --release

# Without TLS, default library dir ('./libraries')
./target/release/task-scheduler --addr "0.0.0.0:50051"

# Specify a custom library directory
./target/release/task-scheduler --addr "0.0.0.0:50051" --library-dir /path/to/custom/plugins

# With TLS (server cert only)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key

# With TLS and Client Certificate Authentication (mTLS)
./target/release/task-scheduler --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key --tls-ca-cert path/to/client_ca.crt

# Start in interactive CLI mode
./target/release/task-scheduler --cli

# Or using cargo run (example with TLS and CLI):
# cargo run --release -- --addr "0.0.0.0:50051" --tls-cert path/to/server.crt --tls-key path/to/server.key --cli
```

- Use the `--addr` (or `-a`) option to specify the host and port. Defaults to `127.0.0.1:50051`.
- Use `--library-dir` (or `-l`) to specify the directory to scan for dynamic library plugins. Defaults to `./libraries`.
- Use `--tls-cert` and `--tls-key` to provide the server's certificate and private key files (PEM format) for enabling
  TLS.
- Optionally, use `--tls-ca-cert` to provide a CA certificate file (PEM format) for verifying client certificates (
  enables mutual TLS - mTLS).
- Use `--cli` (or `-c`) to start the server in interactive command-line interface mode instead of directly starting the
  gRPC server.

### Interactive CLI Mode

If started with the `--cli` flag, the application enters an interactive mode where you can manage plugins and view
tasks. The gRPC server does **not** start in this mode.

Available commands:

- `help`: Show the list of available commands.
- `list`: List all currently registered tasks (both built-in and from plugins).
- `plugins`: List all currently loaded dynamic library plugins and the tasks they provide.
- `load <plugin_name>`: Load a dynamic library plugin by its name (e.g., `load my_plugin` will look for
  `libmy_plugin.so` or similar in the library directory).
- `unload <plugin_name>`: Unload a currently loaded plugin and unregister its tasks.
- `reload`: Unload all currently loaded plugins and rescan the library directory to load all available plugins.
- `exit`: Exit the CLI application.

### gRPC Endpoint Interaction (Conceptual)

Clients need to use a Protobuf-compatible gRPC library.