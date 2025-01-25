# 🎮 Player Module &nbsp; ![Version](https://img.shields.io/badge/version-1.0-blue) ![License](https://img.shields.io/badge/license-MIT-green)

A high-performance, multi-tier caching, and MongoDB-based player data management system. Suitable for single-server or
distributed (multi-server) environments, it takes advantage of L1 caching (Caffeine), L2 caching (Redis), and MongoDB as
a durable data layer. By combining Redis Streams, you gain real-time, cross-server data synchronization—akin to an "
enterprise-level" architecture in a Minecraft setting.

---

## 📚 Table of Contents

- [Introduction](#introduction)
- [Architecture](#architecture)
    - [Multi-Tier Caching (L1 & L2)](#multi-tier-caching-l1--l2)
    - [Core Components](#core-components)
    - [Data Flow](#data-flow)
- [Main Classes & Services](#main-classes--services)
    - [LegacyPlayerDataService](#legacyplayerdataservice)
    - [LegacyPlayerData](#legacyplayerdata)
- [Data Synchronization](#data-synchronization)
- [Performance & Scalability](#performance--scalability)
- [Additional Notes](#additional-notes)
- [License](#license)

---

## Introduction

The Player Module addresses a common challenge in modern Minecraft servers: managing player data with low latency, high
throughput, and real-time synchronization across multiple instances or shards. By leveraging Caffeine (L1), Redis (L2 +
Streams), and MongoDB (persistence), developers can manage player data efficiently, ensuring data consistency while
maintaining top performance.

---

## Architecture

### Multi-Tier Caching (L1 & L2)

1. **L1: Caffeine (In-Memory)**
    - Ultra-fast, local-in-JVM cache for online players.
    - Eliminates network overhead for the most frequent operations.

2. **L2: Redis**
    - Centralized, distributed cache (via Redisson).
    - Redis Streams for publish/subscribe patterns (e.g., cross-server data sync).

3. **MongoDB (Persistence)**
    - Stores data long-term to handle offline players or rarely accessed data.
    - Transactional consistency and flexible document schemas.

Such a caching architecture ensures that high-demand data is served from memory while also providing a robust fallback
to distributed caching and permanent storage.

### Core Components

• L1: Caffeine caches for near-instant data lookups.  
• L2: Redis caches for cross-server consistency and asynchronous notifications through Redis Streams.  
• Final Storage: MongoDB for guaranteed durability and flexible schema updates.

### Data Flow

1. **Cache Miss** → Check Caffeine → If not found, check Redis → If not found, pull from MongoDB.
2. **Cache Hit** → Data is immediately returned, potentially within nanoseconds.
3. **Sync** → Data updates propagate to L2 (Redis), triggering streams that update other servers' L1 caches if that
   player is online there.
4. **Persistence** → Periodic or event-driven writes from L2 to MongoDB ensure data permanency.

---

## Main Classes & Services

### LegacyPlayerDataService

The core service that orchestrates caching and database interactions:  
• Maintains references to L1 (Caffeine) and L2 (Redis) caches.  
• Handles read/write requests using a "fetch or create" pattern.  
• Provides sync tasks to push data from L1 → L2 or from Redis → MongoDB.  
• Encourages concurrency-safe operations through lock settings.

Typical usage pattern:  
• Create an instance (e.g., "player-data-service") with your MongoDB and Redis configs.  
• Use getLegacyPlayerData(UUID) to retrieve or create player data from caches/database.  
• On server shutdown or at intervals, trigger tasks to persist any pending data.

### LegacyPlayerData

Represents the per-player data object stored in the caches and database:  
• Holds custom key-value pairs in a thread-safe map.  
• Facilitates read/write logic for player-based attributes (e.g., stats, metadata, preferences).

---

## Data Synchronization

1. **Redis Streams**:
    - Allows broadcasting events like data changes to all servers.
    - Classes annotated with @RStreamAccepterRegister automatically handle incoming stream requests (e.g., updating L1
      caches).

2. **Periodic Tasks**:
    - Scheduled tasks push data from L1 to L2 or from L2 to MongoDB (ensuring data durability).
    - A read-write lock pattern prevents collisions.

3. **Offline & Online Players**:
    - Online players remain in L1 for quick access.
    - Once offline, data eventually moves to L2 and is persisted to MongoDB, freeing local memory.

---

## Performance & Scalability

• **Local Memory**: Caffeine provides lightning-fast lookups (microseconds), eliminating frequent cross-network calls.  
• **Redis**: Scales horizontally, suitable for multi-server or multi-proxy environments, keeping caches in sync.  
• **Batched Writes**: Writes from L2 to MongoDB happen in batches or on intervals, greatly reducing database overhead.  
• **Redisson**: Offers distributed locks, ensuring concurrency safety across servers.

With this design, the system can accommodate thousands of players concurrently by separating "hot" data in memory from
the "cold" data in Redis / MongoDB. Real-time updates are distributed using Redis Streams, matching many microservice or
enterprise approaches.

---

## Additional Notes

• You can spin up multiple LegacyPlayerDataService instances (for different shards or cluster segments).  
• Extensively uses annotation scanning (e.g., for Redis stream accepters or type adapters), reducing boilerplate.  
• If you want further customization (e.g., TTL strategies, advanced indexing, or custom tasks), the underlying
architecture remains flexible.

---

## License

This project is licensed under the MIT License. See the [LICENSE](LICENSE) file for more details.