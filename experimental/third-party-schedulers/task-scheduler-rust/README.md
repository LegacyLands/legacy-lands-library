# Task Scheduler (Rust)

Task Scheduler is a high-performance task execution backend written in Rust. Clients submit tasks via a gRPC interface
defined using Protocol Buffers. Several scheduler nodes can form a peer-to-peer cluster in which tasks may depend on
tasks executed on other nodes. Leveraging Rust's asynchronous capabilities (`tokio`), concurrency features, and
efficient data structures, the system aims for fast, robust, and scalable task execution.

## Overview

Task Scheduler provides a gRPC service for executing registered tasks. It supports both synchronous and asynchronous
task functions. Task arguments are passed as Protobuf `Any` messages, which are then decoded into typed Rust values
within the scheduler before execution.

Every submitted task gets a lifecycle (`PENDING → RUNNING → SUCCESS / FAILED / CANCELLED`) that can be queried, awaited
and cancelled from any node of the cluster. Finished results are kept for a configurable time to serve queries and
dependent tasks.

## Architecture & Design

### Core Modules

* **`task-macro`:** A procedural macro crate defining `#[sync_task]` and `#[async_task]` attributes. These macros
  automatically register the annotated functions into a global registry upon application startup using the `ctor` crate.
* **`src/bin/task-scheduler.rs`:** The main binary entry point. Parses command-line arguments, initializes logging,
  builds the cluster and the scheduler, starts the `tonic` gRPC server (optionally with TLS) and shuts it down
  gracefully on `Ctrl+C`.
* **`src/scheduler/`:** The cluster-aware scheduler of a node. Resolves dependencies across the cluster, executes or
  forwards tasks, handles cancellation, timeouts and the concurrency limit. `store.rs` holds the task records and their
  lifecycle states.
* **`src/cluster/`:** The peer-to-peer layer. Keeps lazily connected gRPC channels to the configured peers, runs the
  heartbeat (liveness, methods, load), locates tasks on peers and forwards requests.
* **`src/server/service.rs`:** A thin `tonic` adapter that maps the gRPC `TaskScheduler` service to the scheduler and
  converts errors into gRPC status codes.
* **`src/tasks/`:** Task registration (`registry.rs`), built-in example tasks (`builtin.rs`) and dynamic library
  plugins (`dynamic.rs`).
* **`src/models/`:** Internal data structures, including the `ArgValue` enum which represents decoded task arguments.
* **`src/error/mod.rs`:** The `TaskError` type defined with `thiserror`.
* **`src/logger.rs`:** Logging via `tracing` to the console and timestamped files in the `logs/` directory.
* **`build.rs`:** Uses `tonic_build` to compile the `.proto` definitions into Rust code during the build process.

### Task Registration

- **Registration with Macros:**
  Built-in tasks are registered using `#[sync_task]` for synchronous tasks or `#[async_task]` for asynchronous tasks.
  These macros leverage the [`ctor`](https://crates.io/crates/ctor) crate to run registration code automatically when
  the program starts. A synchronous task has the signature `fn(Vec<ArgValue>) -> Result<String>`. An asynchronous task
  may return either `String` or `Result<String>`, returning an `Err` marks the task as failed.

- **Dynamic Library Plugins:**
  External tasks can be provided via dynamic libraries (`.so` on Linux, `.dll` on Windows, `.dylib` on macOS). These
  libraries must expose an `init_plugin` function with the signature
  `unsafe fn() -> &'static [(&'static str, bool, usize)]`. Each tuple represents a task:
  `(task_name, is_async, function_pointer_address)`. The `DynamicTaskLoader` scans a configured directory (default:
  `./libraries`, configurable via `--library-dir`), loads these libraries, calls `init_plugin`, and registers the
  discovered tasks. Task names from plugins are prefixed with the plugin name (e.g., `plugin_name::task_name`).

- **Global Task Registry (`src/tasks/registry.rs`):**
  The `TaskRegistry` stores all tasks in a single `DashMap` keyed by method name, so sync and async tasks are resolved
  automatically. Each entry keeps its own handler, which guarantees that dynamic plugin functions are always dispatched
  by name. Synchronous tasks are executed on `tokio`'s blocking thread pool, so they never stall the async runtime.

### Execution Flow (gRPC `SubmitTask`)

1. The request is validated (non-empty id and method, no self dependency).
2. If the task id is already known, the existing task is returned instead of executing it again (idempotency).
3. If the method is not registered locally, the task is forwarded to the least loaded alive peer providing it
   (see [Cluster](#cluster)).
4. Arguments are decoded into `Vec<ArgValue>` and a `PENDING` record is created.
5. All dependencies are awaited concurrently, locally or on peers, until the dependency timeout. Optionally, their
   results are prepended to the arguments (`inject_dependency_results`).
6. A slot of the concurrency limit is acquired, the record moves to `RUNNING` and the task is executed, optionally
   bounded by the execution timeout.
7. The record moves to `SUCCESS`, `FAILED` or `CANCELLED`. Panicking tasks are recorded as `FAILED`.
8. Blocking submissions return once the task is finished, detached submissions (`detached = true`) return right after
   step 4 with `PENDING`.

### Cluster

Nodes are connected through the static `--peers` list. There is no central coordinator and no external middleware.

- **Heartbeat:** every node periodically calls `GetNodeInfo` on its peers to learn their id, liveness, registered
  methods and load. A peer that went down is marked as not alive and picked up again once it is reachable.
- **Cross-node dependencies:** when a dependency is unknown locally, the node asks all peers concurrently with
  `GetResult` and follows the owning peer with `WaitResult` long polling. A dependency that is unknown in the whole
  cluster is retried with backoff, so dependencies may be submitted after their dependents. Every lookup is bounded by
  the remaining dependency timeout, even if a peer hangs.
- **Failure propagation:** a dependency ending with `FAILED` or `CANCELLED` fails the dependent task with
  `FAILED_PRECONDITION`, the message names the dependency and the node it ran on. A dependency that does not finish in
  time fails the dependent task with `DEADLINE_EXCEEDED`.
- **Forwarding:** tasks for methods that are not registered locally are forwarded once. Forwarded requests carry the
  origin node id and are never forwarded again, which rules out loops. Queries for a forwarded task on the receiving
  node are proxied to the executing node. Forwarding can be disabled per request with `disable_forwarding`.
- **Cluster-wide cancellation:** `CancelTask` on any node locates the owning node and cancels the task there.
- **Node ids:** node ids must be unique. They default to the listen address, or to `hostname:port` when listening on an
  unspecified address such as `0.0.0.0`. A warning is logged if a peer reports the same id as the local node.

### gRPC Interface

The service is defined in [`../proto/task_scheduler.proto`](../proto/task_scheduler.proto):

| RPC           | Description                                                                                       |
|---------------|---------------------------------------------------------------------------------------------------|
| `SubmitTask`  | Submits a task. Blocks until it finishes, or returns `PENDING` immediately when `detached` is set. |
| `GetResult`   | Returns the current state of a task without waiting, `NOT_FOUND` if the node does not know it.     |
| `WaitResult`  | Waits until the task is finished or `timeout_ms` elapses (capped at 30 seconds).                   |
| `CancelTask`  | Cancels a task that is not finished yet, wherever it runs.                                         |
| `GetNodeInfo` | Returns the node id, registered methods, pending and running task counts, and peer states.        |

Important `TaskRequest` fields:

| Field                       | Description                                                                       |
|-----------------------------|-----------------------------------------------------------------------------------|
| `task_id`                   | Cluster-wide unique id. Resubmitting a known id returns the existing task.         |
| `method`                    | Name of the registered task function.                                             |
| `args`                      | Arguments packed into `google.protobuf.Any`.                                      |
| `deps`                      | Ids of tasks that must succeed first, on any node.                                |
| `detached`                  | Return immediately with `PENDING` instead of waiting.                             |
| `dependency_timeout_ms`     | Maximum time to wait for dependencies, `0` uses `--dependency-timeout-ms`.        |
| `inject_dependency_results` | Prepend the dependency results (as strings, in `deps` order) to the arguments.    |
| `execution_timeout_ms`      | Maximum execution time of the task itself, `0` means unlimited.                   |
| `disable_forwarding`        | Fail with `NOT_FOUND` instead of forwarding when the method is not local.         |
| `is_async`                  | Deprecated and ignored, sync and async methods are resolved automatically.        |

Failed blocking submissions are reported as gRPC errors:

| Status code           | Cause                                                       |
|-----------------------|-------------------------------------------------------------|
| `NOT_FOUND`           | The method is registered on no reachable node.              |
| `INVALID_ARGUMENT`    | Invalid request or arguments rejected by the task.          |
| `FAILED_PRECONDITION` | A dependency failed or was cancelled.                       |
| `DEADLINE_EXCEEDED`   | Dependency timeout or execution timeout.                    |
| `CANCELLED`           | The task was cancelled.                                     |
| `INTERNAL`            | The task failed or panicked.                                |

Errors of forwarded tasks keep the status code reported by the executing node.

### Argument Conversion

Incoming `Any` arguments are converted into the `ArgValue` enum, which allows task functions to work with typed Rust
values. Supported types include:

- Integers (i32, i64, u32, u64)
- Floating point numbers (f32, f64)
- Booleans
- Strings
- Byte arrays (`Vec<u8>`)
- Nested Arrays (`Vec<ArgValue>` via `ListValue`)
- Nested Maps (`HashMap<String, ArgValue>` via `MapValue`)

### High Performance Considerations

1. **Concurrent Data Structures:** the task registry and the task store use `DashMap` for low-contention access, task
   states are published through `tokio::sync::watch` channels so waiters are notified without polling.
2. **Asynchronous Runtime:** the gRPC server, dependency resolution and async tasks run on `tokio`, sync tasks run on
   the blocking thread pool.
3. **Long Polling:** cross-node waiting uses `WaitResult` long polls instead of tight polling loops.
4. **Efficient Serialization:** `prost` is used for Protobuf message handling.

### TLS Configuration

- **Server Authentication:** provide `--tls-cert` and `--tls-key` (PEM) to enable TLS.
- **Mutual TLS (mTLS):** additionally provide `--tls-ca-cert` to require client certificates signed by this CA.
- **Peers:** peers configured with `https://` URIs are contacted over TLS. `--peer-ca-cert` sets the CA used to verify
  them (WebPKI roots are used otherwise), `--peer-tls-cert` and `--peer-tls-key` set the client identity for peers that
  require mTLS.

If TLS arguments are not provided, the server runs without encryption.

## Usage

### Registering Built-in Tasks

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

Built-in tasks must live in a module that is linked into the binary, such as `src/tasks/builtin.rs`.

### Creating Dynamic Library Plugins

1. Create a new Rust library project with `crate-type = ["cdylib"]`.
2. Add `task-scheduler` as a path dependency.
3. Define the task functions with `#[no_mangle]` and implement `init_plugin` returning their metadata.
4. Build the library and copy it into the `--library-dir` directory (default: `./libraries`).

See [`examples/plugin_example`](examples/plugin_example) for a complete plugin.

Plugin caveats:

- A plugin links its own copy of `tokio` and cannot use the runtime of the scheduler. Async plugin tasks that use
  timers or I/O must run on a plugin-owned runtime and return a future awaiting the join handle, as shown in the
  example.
- A panic inside a plugin cannot be caught by the scheduler and aborts the whole process. Plugins must return errors
  instead of panicking.
- Plugins must be built with the same compiler and `task-scheduler` version as the scheduler, because task functions
  are exchanged through the Rust ABI.

### Starting a Node

```bash
cargo build --release

# Standalone node
./target/release/task-scheduler --addr 0.0.0.0:50051

# With TLS and client certificate authentication (mTLS)
./target/release/task-scheduler --addr 0.0.0.0:50051 --tls-cert server.crt --tls-key server.key --tls-ca-cert client_ca.crt

# Start with the interactive CLI
./target/release/task-scheduler --cli
```

### Starting a Cluster

Each node lists the other nodes as peers and gets a unique id:

```bash
# Machine 1
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-1 --peers http://10.0.0.2:50051,http://10.0.0.3:50051

# Machine 2
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-2 --peers http://10.0.0.1:50051,http://10.0.0.3:50051

# Machine 3
./target/release/task-scheduler --addr 0.0.0.0:50051 --node-id node-3 --peers http://10.0.0.1:50051,http://10.0.0.2:50051
```

Nodes may be started in any order, peer connections are established lazily and healed by the heartbeat.

### Command-Line Options

| Option                         | Default             | Description                                                     |
|--------------------------------|---------------------|-----------------------------------------------------------------|
| `-a`, `--addr`                 | `127.0.0.1:50051`   | Listen address.                                                 |
| `-l`, `--library-dir`          | `./libraries`       | Directory scanned for dynamic library plugins.                  |
| `-c`, `--cli`                  | off                 | Start the interactive CLI next to the gRPC server.              |
| `--tls-cert`, `--tls-key`      | none                | Server certificate and private key (PEM), enables TLS.          |
| `--tls-ca-cert`                | none                | CA for client certificates, enables mTLS.                       |
| `--node-id`                    | see [Cluster](#cluster) | Unique id of this node.                                     |
| `--peers`                      | none                | Comma separated peer URIs.                                      |
| `--peer-ca-cert`               | none                | CA used to verify `https` peers.                                |
| `--peer-tls-cert`, `--peer-tls-key` | none           | Client identity presented to peers requiring mTLS.              |
| `--heartbeat-interval-ms`      | `3000`              | Interval between peer heartbeats.                               |
| `--peer-timeout-ms`            | `5000`              | Timeout of short peer requests (heartbeat, lookup, cancel).     |
| `--dependency-timeout-ms`      | `30000`             | Default time to wait for dependencies.                          |
| `--max-concurrent-tasks`       | `0`                 | Maximum number of concurrently executing tasks, `0` unlimited.  |
| `--result-ttl-secs`            | `600`               | How long finished results are kept.                             |

### Interactive CLI Mode

With `--cli`, an interactive console runs next to the gRPC server. Available commands:

- `help`: Show the list of available commands.
- `list`: List all registered tasks (built-in and from plugins).
- `plugins`: List loaded plugins and the tasks they provide.
- `load <plugin_name>`: Load a plugin by name (e.g., `load my_plugin` looks for `libmy_plugin.so` or similar).
- `unload <plugin_name>`: Unload a plugin and unregister its tasks.
- `reload`: Rescan the library directory and load all available plugins.
- `exit`: Leave the CLI, the server keeps running.

## Limitations

- Dependency cycles are not detected, the involved tasks fail with a dependency timeout.
- Running sync tasks cannot be interrupted. When they are cancelled or time out, their result is discarded but the
  thread keeps running until the function returns.
- Peers are configured statically, there is no dynamic node discovery.
- Task results are kept in memory only and are lost when a node restarts. Memory usage is bounded by
  `--result-ttl-secs`, not by a maximum number of records.

## Testing

```bash
# Build the example plugin used by the plugin and forwarding tests
(cd examples/plugin_example && cargo build)
mkdir -p libraries && cp examples/plugin_example/target/debug/libplugin_example.so libraries/

cargo test
```

`tests/cluster.rs` and `tests/cluster_resilience.rs` start real multi-node clusters and cover cross-node dependencies,
forwarding, cancellation, node crashes and recovery, unresponsive peers, concurrency limits and result eviction.
