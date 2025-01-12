# 🎮 Player Module &nbsp; ![Version](https://img.shields.io/badge/version-1.0-blue) ![License](https://img.shields.io/badge/license-MIT-green)

> A **high-performance** and **scalable** player data management system. It leverages **L1 (Caffeine)** and **L2 (Redis)** caching, **MongoDB** persistence, and **Redis Streams** for near “enterprise-level” performance in distributed Minecraft or similar server environments.

---

## 📚 Table of Contents

- [Introduction](#introduction)
- [Key Components](#key-components)
- [Data Flow](#data-flow)
- [Performance Highlights](#performance-highlights)
- [Why Near Enterprise-Level?](#why-near-enterprise-level)
- [Summary](#summary)
- [License](#license)

---

## Introduction

The **Player Module** is a core part of a distributed system aimed at managing player data with **high throughput** and **low latency**. By integrating:
- **L1 Cache (Caffeine):** In-memory, ultra-fast access for online players.
- **L2 Cache (Redis):** Central cache for multi-server environments, synchronized via Redis Streams.
- **MongoDB:** Document-based persistence for flexible schema and long-term data storage.

This architecture ensures that both frequent reads/writes (online players) and less common operations (offline queries, global sync) are handled efficiently.

---

## Key Components

### LegacyPlayerDataService

A flexible “service object” encapsulating:
- **Redis Connection** (L2 Cache + Streams)
- **MongoDB Connection** (Persistence)
- **Caffeine Cache** (L1 Cache)

By creating multiple `LegacyPlayerDataService` instances, you can manage different database or Redis clusters independently.
> Internally, it uses multi-level caching: L1 for ultra-fast memory-based access, and L2 for cross-server data sharing.

### Caching Tiers

1. **L1 (Caffeine)**
    - **Fast Memory Access**
    - Typically holds data for **online** or **recently active** players.
    - Minimizes network overhead and database queries.

2. **L2 (Redis)**
    - **Distributed, Centralized View**
    - Suited for multi-server setups.
    - Uses **Redis Streams** for broadcasting player data changes (e.g., join/quit, data update) across nodes.

### MongoDB

- **Document-based Storage**
- Efficient reads/writes for dynamic player data fields.
- Durable final persistence layer if caches miss.

---

## Data Flow

Below is a simplified, high-level view of how player data moves between caches and the database:

1. **Player Data Retrieval**
    1. **L1 Cache Check**
        - If the player is **online** and data is in L1, return immediately (fastest path).
        - If **not found**, proceed to L2.
    2. **L2 Cache (Redis) Check**
        - If found, populate L1 for future quick lookups and return to caller.
        - If still **not found**, query MongoDB.
    3. **Database (MongoDB) Fetch**
        - On success, load into L1.
        - If the player has never been seen before, create a new record and store it in L1.

2. **Player Logout & Data Sync**
    1. **Gather All `LegacyPlayerDataService` Instances**
        - Each has its own L1 + L2 caches.
    2. **Compare & Sync L1 → L2**
        - Any unsaved changes in L1 are written to L2.
        - Allows later scheduled tasks to persist them into MongoDB.
    3. **Remove Player Data from L1**
        - Frees up memory, avoids stale data if the player stays offline for a long time.

3. **Data Synchronization & Persistence**
    1. **L1 → L2**:
        - `L1ToL2PlayerDataSyncTask` iterates all L1 entries, pushing to Redis if differences are found.
        - Runs periodically or on certain triggers (like logout).
    2. **L2 → MongoDB**:
        - `PlayerDataPersistenceTask` captures all data in Redis, writes it to MongoDB in a batched or scheduled manner.
        - Ensures final, durable storage of changes.

4. **Player Data Update (Cross-Server)**
    1. **Publish to Redis Streams**
        - Using methods like `pubRStreamTask`, an update command is added to the stream (`RStreamTask`).
        - Example: `PlayerDataUpdateByNameRStreamAccepter` or `PlayerDataUpdateByUuidRStreamAccepter` consumes the stream and applies updates.
    2. **Other Servers Receive & Update**
        - If the player is online on another server, that server’s L1 cache is updated in real-time.
        - This ensures data consistency across the network.

---

## Performance Highlights

- **High Throughput**
    - **Caffeine L1** eliminates network round-trips for frequent reads/writes.
    - **Redis L2** allows horizontal scaling with distributed caching.

- **Reduced DB Load**
    - Most lookups are fulfilled by L1/L2 caches, hitting MongoDB only on cache misses or scheduled persistence.
    - Frees the database from constant read pressure.

- **Asynchronous Task Handling**
    - Redis Streams + `RStreamAccepterInterface` classes (e.g., `L1ToL2PlayerDataSyncByUuidRStreamAccepter`) process updates asynchronously, preventing blocking or contention.
    - Spreads out load across multiple servers.

- **Document-Oriented Flexibility**
    - Using MongoDB for final storage avoids rigid schema definitions and supports easy extension of player attributes.

---

## Why Near Enterprise-Level?

1. **Multilayer Caching**:
    - A pattern seen in large-scale enterprise systems: local (memory) + distributed caching for maximum throughput and minimal latency.

2. **Distributed Messaging**:
    - **Redis Streams** act like a message queue/bus, commonly used in microservices or high-scale systems for cross-node sync and event broadcasting.

3. **Concurrency Control**:
    - Uses distributed locks (Redisson) and concurrency settings (`LockSettings`) to ensure safe writes under high concurrency.

4. **Annotation-Driven Architecture**:
    - Similar to Spring Boot or other enterprise frameworks, you declare components (`@RStreamAccepterRegister`, `@TypeAdapterRegister`) and let reflection + scanning wire them up.

5. **Scalable & Modular**:
    - Multiple `LegacyPlayerDataService` can be spun up for different clusters or shards, each safely isolated.
    - Add or remove services as your player base grows.

While labeled for “Minecraft servers,” the structure and design choices (multi-tier cache, streaming, concurrency, flexible data layer) align well with many enterprise-level backend systems.

---

## Summary

Bringing together **Caffeine** (L1 Cache), **Redis** (L2 Cache + Streams), and **MongoDB** (persistence), this module delivers:
- **Ultrafast read/writes** for active players (Caffeine).
- **Cross-instance data consistency** (Redis).
- **Reliable final storage** (MongoDB).
- **Seamless distribution** of updates (Redis Streams).
- **Robust task scheduling** for synchronization and persistence.

By instantiating a dedicated `LegacyPlayerDataService`, you can manage:
- **Player session data** in memory,
- **Multi-server sync** via Redis,
- **Long-term data** in MongoDB,  
  all while keeping overhead low and performance high. This design can scale from single-server scenarios to large distributed networks, ensuring minimal data conflicts and fast responses—essentially approaching an enterprise-grade architecture within the realm of Minecraft (or similar) server applications.

---

## License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.