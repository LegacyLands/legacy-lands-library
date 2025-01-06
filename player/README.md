# 🎮 Player Module &nbsp; ![Version](https://img.shields.io/badge/version-1.0-blue) ![License](https://img.shields.io/badge/license-MIT-green)

> A high-performance distributed player data management system utilizing multi-tier caching for optimal data consistency and access efficiency.

---

## 📚 Table of Contents

- [Introduction](#introduction)
- [Automation](#automation)
- [Key Components](#key-components)
- [Data Processes](#data-processes)
- [Performance Optimizations](#performance-optimizations)
- [Summary](#summary)
- [License](#license)

---

## 📝 Introduction

This module is designed for processing player data within a distributed environment, employing L1 (Caffeine) and L2 (Redis) caches to ensure data consistency and efficient access.

---

## 🤖 Automation

Automates the management and synchronization of player data across multiple layers, including caches and databases, ensuring seamless data flow and integrity.

---

## 🔑 Key Components

### 🏗️ LegacyPlayerDataService

- **Description**: A composite object that can be instantiated indefinitely, each instance having its own dedicated connections:
  - **🔗 Redis Connection**: For L2 caching layer.
  - **💾 MongoDB Connection**: For persistent storage.
  - **🔄 Redis Streams Connection**: For inter-server communication.

### 🗄️ Caching Layers

- **L1 Cache (Caffeine)**: In-memory cache designed for online player data, offering ultra-fast data access.
- **L2 Cache (Redis)**: Secondary caching layer meant for persistent storage of player data, providing a centralized view.

---

## 🔄 Data Processes

### 📊 Player Data Query Process

1. **Check L1 Cache**:
   - **Note**: Optimized for online players; not queried for offline data.
2. **Query L2 Cache (Redis)**:
   - **Cache Hit**: Retrieve data, store in L1, return to requester.
   - **Cache Miss**: Query MongoDB.
3. **Query Database (MongoDB)**:
   - **Success**: Retrieve, store in L1, return data.
   - **No Data Found**: Create new record, store in L1.

### 🚪 Player Logout Process

1. **Retrieve All Instances**: Gather all instances of `LegacyPlayerDataService`.
2. **Compare L1 and L2 Cache**:
   - **Consistent**: No changes needed.
   - **Inconsistent**: Update L2 with L1 data.
3. **Remove Player Data**: Delete from L1 to maintain integrity.

### 🔄 Data Synchronization

1. **Synchronize L1 to L2**: Transfer all data from L1 to L2.
2. **Persist L2 Data to Database**: Save L2 data into MongoDB.

### ✏️ Player Data Modification Process

1. **Publish Update Tasks Using Redis Stream**:
   - Ensure distributed synchronization.
   - Update L2 cache directly.
   - **L2 Cache Miss**: Update database.
2. **Process on Each Server**:
   - **Online Player**: Sync updated data from L2 to L1.

### 📥 Player Data Acquisition Process

1. **L1 L2 Data Synchronization**: Use Redis Stream for synchronization.
2. **Search**: Directly in L2; if miss, search database.

---

## ⚙️ Performance Optimizations

- **L1 Cache**: Indefinite retention for active players, rapid access.
- **L2 Cache**: Long-term storage with expiration policies.
- **Database**: Accessed mainly on cache misses, reducing load.

---

## 📜 Summary

This architecture utilizes a multi-tier caching strategy for efficient player data management:
- **L1 Cache (Caffeine)**: Speedy access for online players.
- **L2 Cache (Redis)**: Centralized caching of persistent data.
- **Database (MongoDB)**: Main storage for cache misses.
- **Redis Streams**: Real-time server synchronization.
- **LegacyPlayerDataService**: Dedicated connections for Redis, MongoDB, and Redis Streams.

In conclusion, this design maximizes data retrieval efficiency, ensures consistency across distributed servers, and optimizes overall performance, delivering a seamless experience for players.

---

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.



