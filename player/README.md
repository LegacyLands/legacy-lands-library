# 👤 Player Framework

A high-performance player data management system with multi-tier caching support, built on top of Caffeine, Redis, and MongoDB for optimal performance and scalability.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 📦 **Multi-Tier Caching**
  - L1: Ultra-fast Caffeine in-memory cache
  - L2: Distributed Redis cache with streams
  - L3: MongoDB persistent storage
  - Automatic data synchronization

- 🔄 **Real-Time Synchronization**
  - Redis Streams for cross-server updates
  - Automatic cache invalidation
  - Thread-safe operations
  - Configurable sync intervals

- 🛠 **Advanced Data Management**
  - Annotation-driven stream handling
  - Custom type adapters
  - Flexible data persistence
  - Automatic resource cleanup

## 📚 Quick Start

### Installation

```kotlin
dependencies {
    implementation("net.legacy.library:player:1.0-SNAPSHOT")
}
```

### Basic Usage

1️⃣ **Service Configuration**
```java
MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
    "playerdb", 
    "mongodb://localhost:27017/",
    UuidRepresentation.STANDARD
);

Config redisConfig = new Config();
redisConfig.useSingleServer().setAddress("redis://127.0.0.1:6379");

LegacyPlayerDataService service = LegacyPlayerDataService.of(
    "player-data-service",
    mongoConfig,
    redisConfig,
    basePackages,
    classLoaders
);
```

2️⃣ **Data Operations**
```java
// Get player data
LegacyPlayerData playerData = service.getLegacyPlayerData(player.getUniqueId());

// Update data
playerData.addData("coins", "1000")
         .addData("rank", "VIP");
```

3️⃣ **Stream Processing**
```java
@RStreamAccepterRegister
public class CustomDataAccepter implements RStreamAccepterInterface {
    @Override
    public void accept(RStream<Object, Object> stream, 
                      StreamMessageId id,
                      LegacyPlayerDataService service, 
                      String data) {
        // Handle incoming data updates
    }
}
```

## 🔧 Core Components

### Data Service
- `LegacyPlayerDataService`: Central service for data operations
- Multi-level cache management
- Automatic data synchronization
- Resource lifecycle management

### Data Model
- `LegacyPlayerData`: Thread-safe player data container
- Support for custom data types
- Automatic serialization
- Versioning support

### Stream System
- Real-time data synchronization
- Annotation-based stream processors
- Configurable message expiration
- Error handling and recovery

## 🎯 Advanced Features

### Cache Coherence

We use Redis Stream to publish tasks to complete cross-server communication and ensure cache consistency between servers

```java
Map<String, String> updates = new HashMap<>();
updates.put("lastLogin", String.valueOf(System.currentTimeMillis()));
updates.put("status", "ONLINE");

service.pubRStreamTask(
    PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
        playerName,
        updates,
        Duration.ofMinutes(5)
    )
);
```

### Batch Operations
```java
// Sync all online players data (L1 L2)
service.getL1Cache().getCache().asMap().forEach((uuid, data) -> {
    L1ToL2PlayerDataSyncTask.of(uuid, service).start();
});
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)