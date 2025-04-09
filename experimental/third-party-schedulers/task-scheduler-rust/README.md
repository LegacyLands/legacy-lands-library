# Task Scheduler (Rust)

Task Scheduler is a high-performance task execution backend written in Rust. It allows clients to submit tasks, potentially with dependencies, via a gRPC interface defined using Protocol Buffers. Leveraging Rust's asynchronous capabilities (`tokio`), concurrency features, and efficient data structures, the system aims for fast, robust, and scalable task execution.

## Overview

Task Scheduler provides a gRPC service for executing registered tasks. It supports both synchronous and asynchronous task functions. Task arguments are passed as Protobuf `Any` messages, which are then decoded into typed Rust values within the scheduler before execution. Results of completed tasks are cached temporarily.

## Architecture & Design

### Core Modules

*   **`task-macro`:** A procedural macro crate defining `#[sync_task]` and `#[async_task]` attributes. These macros automatically register the annotated functions into a global registry upon application startup using the `ctor` crate.
*   **`src/main.rs`:** The main binary entry point. Parses command-line arguments (e.g., server address), initializes logging (`src/logger.rs`), starts the `tonic` gRPC server (`src/server/service.rs`), and ensures built-in tasks (`src/tasks/builtin.rs`) are linked.
*   **`src/logger.rs`:** Configures application logging using `tracing` to output to both console and timestamped files in the `logs/` directory. Provides convenient logging macros.
*   **`src/models/mod.rs` & `src/models/wrappers.rs`:** Defines internal data structures, including the crucial `ArgValue` enum which represents decoded task arguments, and helper structs for Protobuf message decoding.
*   **`src/tasks/mod.rs`, `src/tasks/registry.rs`, `src/tasks/builtin.rs`:** Manages task registration, storage, and execution logic. Contains the global `TaskRegistry` and example task implementations.
*   **`src/server/mod.rs` & `src/server/service.rs`:** Implements the `TaskScheduler` gRPC service using `tonic`. Handles incoming requests, interacts with the `TaskRegistry`, and manages the result cache.
*   **`src/error.rs`:** Defines custom error types for the application using `thiserror`.
*   **`build.rs`:** Uses `tonic_build` to compile the `.proto` definitions into Rust code during the build process.

### Task Registration and Execution

- **Registration with Macros:**
  Tasks (regular functions) are registered using the `#[sync_task]` attribute for synchronous tasks or `#[async_task]` for asynchronous tasks (functions returning a `Future`). These macros leverage the [`ctor`](https://crates.io/crates/ctor) crate to run registration code automatically when the program starts, adding the function pointer and its name (derived from the function identifier) to the global `TaskRegistry`.

- **Global Task Registry (`src/tasks/registry.rs`):**
  The `TaskRegistry` uses `DashMap` (a concurrent hash map) to store registered synchronous and asynchronous tasks separately. It provides the core `execute_task` method used by the gRPC service.

- **Execution Flow (gRPC `SubmitTask`):**
    1. The gRPC service (`src/server/service.rs`) receives a `TaskRequest`.
    2. It calls `TaskRegistry::convert_args` to decode the `prost_types::Any` arguments into a `Vec<ArgValue>`.
    3. It checks if all task dependencies listed in the request are present in the results cache (`TaskRegistry::get_task_result`). If not, it returns an error.
    4. It looks up the task function (sync or async) in the registry based on the `method` name.
    5. It executes the task function with the converted arguments. Asynchronous tasks are awaited.
    6. The result (`TaskResult` containing status and a string value) is stored in the results cache (`TaskRegistry::cache_task_result`).
    7. A `TaskResponse` (containing task ID, status, and result string) is sent back to the client.

### gRPC Interface and Proto Integration

- **Protobuf Definition (`proto/task_scheduler.proto`):**
  The service interface is defined using Protocol Buffers. Key elements include:
    - `TaskScheduler` service with `SubmitTask` and `GetResult` RPC methods.
    - `TaskRequest`: Contains `task_id`, `method` name, `args` (as `repeated google.protobuf.Any`), `deps` (list of dependency task IDs), and `is_async` flag.
    - `TaskResponse`: Contains `task_id`, `status` (e.g., SUCCESS, FAILED), and `result` (string).
    - `ResultRequest`: Contains `task_id`.
    - `ResultResponse`: Contains `status` and `result` (string).
    - Helper message types (`ListValue`, `MapValue`) for structured arguments within `Any`.

- **Communication Flow:**
  Clients encode task arguments into appropriate Protobuf messages (e.g., `google.protobuf.Int32Value`, `StringValue`, custom `ListValue`, `MapValue`) and wrap them in `google.protobuf.Any`. The server decodes these `Any` messages back into the internal `ArgValue` enum before executing the task.

### Argument Conversion and Dependency Management

- **Parameter Handling (`ArgValue` enum):**
  Incoming `Any` arguments are converted into the `ArgValue` enum within the `TaskRegistry`. This allows task functions to work with typed Rust values. Supported types include:
    - Integers (i32, i64, u32, u64)
    - Floating point numbers (f32, f64)
    - Booleans
    - Strings
    - Byte arrays (`Vec<u8>`)
    - Nested Arrays (`Vec<ArgValue>` via `ListValue`)
    - Nested Maps (`HashMap<String, ArgValue>` via `MapValue`)

- **Dependency Tracking & Caching:**
  Before executing a task, the scheduler checks its dependencies (`deps` field in `TaskRequest`). It looks up each dependency `task_id` in a sharded LRU cache (`Arc<[Mutex<LruCache<String, TaskResult>>]>` in `TaskRegistry`). If any dependency's result is not found in the cache, the task execution fails early. Successful task results are added to this cache. The cache helps ensure that dependent tasks only run after their prerequisites are complete within a reasonable timeframe (defined by LRU eviction).

### High Performance Considerations

1.  **Concurrent Data Structures:**
    The global task registry uses `DashMap` for efficient, low-contention concurrent access to registered tasks.
2.  **Asynchronous Runtime:**
    The gRPC server and asynchronous tasks run on the `tokio` runtime, enabling non-blocking I/O and efficient handling of many concurrent connections and tasks.
3.  **Sharded LRU Caching:**
    Intermediate task results for dependency checking are stored in an LRU cache sharded across multiple `parking_lot::Mutex` instances. This reduces lock contention compared to a single global lock when accessing or updating the cache. `ahash` is used for fast hashing to determine the shard.
4.  **Efficient Serialization:**
    `prost` is used for Protobuf message handling, providing fast serialization and deserialization.

## Usage

### Registering Tasks

Register tasks using the `#[sync_task]` or `#[async_task]` attributes. The function signature should accept `Vec<ArgValue>` and return `String`.

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

// Make sure to import the module containing tasks in main.rs or lib.rs
// e.g., use task_scheduler::tasks::builtin;
```

### gRPC Endpoints

The backend exposes two primary gRPC endpoints defined in `proto/task_scheduler.proto`:

- **`SubmitTask(TaskRequest) returns (TaskResponse)`:**
  Submits a task for execution. Requires a unique `task_id`, the registered `method` name (e.g., `"add"`), arguments encoded as `google.protobuf.Any`, a list of dependency `task_id`s, and the `is_async` flag. Returns the final status and result string.

- **`GetResult(ResultRequest) returns (ResultResponse)`:**
  Retrieves the cached outcome of a previously submitted task using its `task_id`. Returns the status and result string if found in the cache.

## CLI and gRPC Usage

### Starting the Server

Build and run the server using `cargo`:

```bash
cargo build --release
./target/release/task-scheduler --addr "0.0.0.0:50051"
# Or using cargo run:
# cargo run --release -- --addr "0.0.0.0:50051"
```

- Use the `--addr` (or `-a`) option to specify the host and port. Defaults to `127.0.0.1:50051`.

### gRPC Interaction Example (Conceptual)

Clients need to use a gRPC library compatible with Protobuf.

1.  **Define Arguments:** Create Protobuf messages for your arguments (e.g., `google.protobuf.Int32Value`, `StringValue`).
2.  **Wrap in `Any`:** Encode each argument message into a `google.protobuf.Any` message, setting the `type_url` correctly (e.g., `"type.googleapis.com/google.protobuf.Int32Value"`) and the `value` as the serialized bytes of the argument message.
3.  **Create `TaskRequest`:** Construct the `TaskRequest` including the `task_id`, `method` name (e.g., `"add"`), the list of `Any` arguments, dependencies, and `is_async`.
4.  **Call `SubmitTask`:** Send the `TaskRequest` to the server. The `TaskResponse` will contain the result once execution finishes.
5.  **Call `GetResult` (Optional):** If needed, call `GetResult` with the `task_id` to retrieve the cached result later.

**Example `Any` structure for an `Int32Value` of 5:**

```protobuf
// Conceptually:
Any {
  type_url: "type.googleapis.com/google.protobuf.Int32Value";
  // 'value' contains the serialized bytes of Int32Value { value: 5 }
  value: bytes { ... };
}
```

Refer to the `proto/task_scheduler.proto` file for the exact message definitions.
