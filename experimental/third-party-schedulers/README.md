# Task Scheduler

The Task Scheduler module is a high-performance task execution platform designed to overcome the limitations of
conventional server architectures.

## WARNING

This module is still **experimental**. The multi-node cluster described below partially addresses
[Issue 58](https://github.com/LegacyLands/legacy-lands-library/issues/58) (multiple backends and cross-server task
dependencies), but some parts are not available yet, for example dynamic node discovery and persistent task results.
APIs may still change before the module is declared stable.

## Overview

In many traditional systems, every computation—regardless of its resource demands—is performed on the primary service
server, leading to significant inefficiencies and potential bottlenecks.

Historically, this centralized approach prompted the development of load balancing and microservices, enabling
distributed and scalable processing. Building on that legacy, our Task Scheduler brings these proven principles into
plugin development.

Even though the Bukkit API is tightly bound to Java, this module demonstrates that resource-intensive computations or
IO-bound tasks can be delegated to specialized servers or languages that excel in these areas.

## Cluster

Several scheduler nodes can be connected into a peer-to-peer cluster over gRPC, without any external middleware:

- **Cross-node dependencies:** a task on node 1 may depend on a task on node 2. The executing node locates the
  dependency on its peers and waits for it, even if the dependency is submitted later. Failures and timeouts of
  dependencies are propagated to the dependent task.
- **Forwarding:** a task whose method is not registered on the receiving node is forwarded to the least loaded peer
  that provides it.
- **Cluster-wide operations:** tasks can be queried and cancelled through any node.
- **Health tracking:** nodes exchange heartbeats with liveness, registered methods and load, and recover automatically
  when a peer restarts.

```text
           ┌──────────────────────────┐          ┌──────────────────────────┐
 Bukkit ──►│ node-a                   │◄────────►│ node-b                   │◄── Bukkit
 client    │ Task A (deps: [Task B])  │  gRPC    │ Task B                   │    client
           └──────────────────────────┘  peers   └──────────────────────────┘
```

## Implemented

- [**Rust**](task-scheduler-rust/README.md) - A high-performance task scheduler written in Rust, supporting
  multi-node clusters, cross-node dependency resolution, task forwarding, cancellation and timeouts, dynamic linked
  library loading, macro-based task registration, CLI, and hot-reloading capabilities.

## Client API

- [**bukkit-grpc-client**](bukkit-grpc-client/README.md) - Off-the-shelf APIs with direct gRPC backend communication,
  enabling offloading of tasks to remote scheduler nodes. Supports blocking, asynchronous and detached submission,
  cross-node dependencies, cluster-aware routing, cancellation, result handling via Bukkit events, connection
  management with optional TLS, and automatic retries.
