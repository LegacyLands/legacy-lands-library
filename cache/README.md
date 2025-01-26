# 🚀 Cache Framework

A flexible multi-level caching framework that integrates Caffeine and Redis, featuring comprehensive lock mechanisms and functional programming support.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🔄 **Flexible Cache Implementations**
  - Caffeine-based in-memory caching (`CaffeineCacheService`)
  - Redis-based distributed caching (`RedisCacheService`)
  - Custom cache support (`CustomCacheService`)
  - Multi-level caching (`FlexibleMultiLevelCacheService`)

- 🔒 **Comprehensive Lock Mechanism**
  - Abstract lock handling via `AbstractLockableCache`
  - Support for both ReentrantLock and Redisson RLock
  - Configurable lock timeouts and retry policies
  - Thread-safe operations with `LockSettings`

- 🎯 **Type-Safe Operations**
  - Generic cache interfaces for type safety
  - Functional programming style API
  - Explicit type conversion support in Redis operations
  - Type-safe multi-level cache access

- ⚡ **Performance Optimizations**
  - Lazy loading capabilities
  - Configurable cache expiration
  - Efficient multi-level cache lookups
  - Thread-safe concurrent operations

## 📚 Quick Start

### Installation

```kotlin
dependencies {
    implementation("net.legacy.library:cache:1.0-SNAPSHOT")
}
```

### Basic Usage

1️⃣ **Caffeine Cache (In-Memory)**
```java
CacheServiceInterface<Cache<String, User>, User> caffeineCache = 
    CacheServiceFactory.createCaffeineCache();

User user = caffeineCache.get(
    cache -> cache.getIfPresent("user1"),
    () -> new User("user1"),
    (cache, value) -> cache.put("user1", value),
    true
);
```

2️⃣ **Redis Cache (Distributed)**
```java
Config config = new Config();
config.useSingleServer().setAddress("redis://localhost:6379");

RedisCacheServiceInterface redisCache = 
    CacheServiceFactory.createRedisCache(config);

User user = redisCache.getWithType(
    client -> client.getBucket("user1").get(),
    () -> new User("user1"),
    (client, value) -> client.getBucket("user1").set(value),
    true
);
```

3️⃣ **Multi-Level Cache**
```java
Set<TieredCacheLevel<?, ?>> tiers = Set.of(
    TieredCacheLevel.of("L1", caffeineCache.getCache()),
    TieredCacheLevel.of("L2", redisCache.getCache())
);

FlexibleMultiLevelCacheService multiLevelCache = 
    CacheServiceFactory.createFlexibleMultiLevelCacheService(tiers);
```

## 🔧 Core Components

### Cache Services
- `CaffeineCacheService`: In-memory caching with Caffeine
- `RedisCacheService`: Distributed caching with Redis
- `CustomCacheService`: Support for custom cache implementations
- `FlexibleMultiLevelCacheService`: Multi-level cache orchestration

### Lock Management
- `AbstractLockableCache`: Base class for lock-enabled caches
- `LockSettings`: Configuration for lock behavior
- `LockableCacheInterface`: Core locking operations

### Models
- `CacheItem`: Wrapper for cached values with expiration
- `ExpirationSettings`: TTL configuration
- `TieredCacheLevel`: Multi-level cache tier definition

## 🎯 Advanced Features

### Lock-Protected Operations
```java
LockSettings lockSettings = LockSettings.of(1000, 500, TimeUnit.MILLISECONDS);

User user = caffeineCache.get(
    cache -> cache.asMap().get("key"),
    () -> new User("key"),
    (cache, value) -> cache.put("key", value),
    true,
    lockSettings
);
```

### Custom Cache Implementation
```java
Map<String, User> customMap = new ConcurrentHashMap<>();
CacheServiceInterface<Map<String, User>, User> customCache = 
    CacheServiceFactory.createCustomCache(customMap);
```

### Multi-Level Cache Operations
```java
User l1User = multiLevelCache.applyFunctionWithoutLock(
    "L1",
    cache -> ((Cache<String, User>) cache).getIfPresent("user1")
);
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)


