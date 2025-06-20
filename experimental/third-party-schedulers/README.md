# Task Scheduler

The Task Scheduler module is a high-performance task execution platform designed to overcome the limitations of
conventional server architectures.

## WARNING

This module is currently known to have certain defects ([Issue 58](https://github.com/LegacyLands/legacy-lands-library/issues/58)).
Although it may be operational, its use is strongly discouraged.
A complete refactoring is planned for the near future. This warning will be removed upon stabilization of the module.

## Overview

In many traditional systems, every computation—regardless of its resource demands—is performed on the primary service
server, leading to significant inefficiencies and potential bottlenecks.

Historically, this centralized approach prompted the development of load balancing and microservices, enabling
distributed and scalable processing. Building on that legacy, our Task Scheduler brings these proven principles into
plugin development.

Even though the Bukkit API is tightly bound to Java, this module demonstrates that resource-intensive computations or
IO-bound tasks can be delegated to specialized servers or languages that excel in these areas.

## Implemented

- [**Rust**](task-scheduler-rust/README.md) - A high-performance task scheduler written in Rust, supporting dependency
  resolution, dynamic linked library loading, macro-based task registration, CLI, and hot-reloading capabilities.

## Client API

- [**bukkit-grpc-client**](bukkit-grpc-client/README.md) - Off-the-shelf APIs with direct gRPC backend communication,
  enabling offloading of tasks to a remote gRPC task scheduler
  service. Supports synchronous and asynchronous task submission, parameter serialization, result handling via Bukkit
  events, connection management with optional TLS, and automatic retries.