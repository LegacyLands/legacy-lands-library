# Task Scheduler

The Task Scheduler module is a high-performance task execution platform designed to overcome the limitations of conventional server architectures. 


## Overview

In many traditional systems, every computation—regardless of its resource demands—is performed on the primary service server, leading to significant inefficiencies and potential bottlenecks.

Historically, this centralized approach prompted the development of load balancing and microservices, enabling distributed and scalable processing. Building on that legacy, our Task Scheduler brings these proven principles into plugin development. 

Even though the Bukkit API is tightly bound to Java, this module demonstrates that resource-intensive computations or IO-bound tasks can be delegated to specialized servers or languages that excel in these areas.

## Implemented

- [**Rust**](rust/README.md) - A high-performance task scheduler written in Rust
