# 🚀 Cache Module

> A powerful multi-level caching solution supporting Caffeine and Redis, with functional programming support and automatic lock handling.

## ✨ Features

- 🔄 Support for Caffeine's `Cache` and `AsyncCache`
- 📦 In-memory Redis database implementation
- 🔒 Automatic lock handling mechanism
- 🎯 Functional programming support
- 📚 Multi-level cache architecture
- ⚡ High-performance operations
- 🛡️ Thread-safe implementation

## 📋 Table of Contents

- [Installation](#-installation)
- [Usage](#-usage)
  - [Caffeine Cache](#-caffeine-cache)
  - [Redis Cache](#-redis-cache)
  - [Custom Cache](#-custom-cache)
  - [Multi-Level Cache](#-multi-level-cache)
- [Contributing](#-contributing)
- [License](#-license)

## 📥 Installation

Add the following dependency to your project:

```kotlin
dependencies {
    compileOnly(files("libs/cache-1.0-SNAPSHOT-sources.jar"))
}
```

## 💻 Usage

### 🔵 Caffeine Cache

Caffeine provides high-performance caching capabilities with excellent hit rates.

```java
// Create Caffeine cache service
CacheServiceInterface<Cache<Integer, String>, String> caffeineCache = 
    CacheServiceFactory.createCaffeineCache();

// Basic get operation
String value = caffeineCache.get(
    cache -> cache.getIfPresent(1),         // Get cache value
    () -> "defaultValue",                   // Cache miss handler
    (cache, queryValue) -> cache.put(1, queryValue),  // Cache storage
    true                                    // Enable caching
);

// Thread-safe operation with lock
String valueWithLock = caffeineCache.get(
    cache -> new ReentrantLock(),           // Lock acquisition
    cache -> cache.getIfPresent(1),         // Cache retrieval
    () -> "defaultValue",                   // Cache miss handler
    (cache, queryValue) -> cache.put(1, queryValue),  // Cache storage
    true,                                   // Enable caching
    LockSettings.of(1, 1, TimeUnit.MINUTES) // Lock configuration
);
```

### 🔴 Redis Cache

Redis cache service typically serves as a second-level cache for distributed systems.

```java
// Redis configuration
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");

// Create Redis cache service
RedisCacheServiceInterface redisCache = 
    CacheServiceFactory.createRedisCache(config);

// Type-safe value retrieval
int intValue = redisCache.getWithType(
    cache -> cache.getBucket("key").get(),
    () -> 1,
    (cache, queryValue) -> cache.getBucket("key").set(queryValue),
    true
);
```

### 🔧 Custom Cache

Implement your own cache using Java's `ConcurrentHashMap` or other solutions.

```java
CacheServiceInterface<Map<Integer, String>, String> customCache =
    CacheServiceFactory.createCustomCache(new ConcurrentHashMap<>());

// Access the underlying cache implementation
Map<Integer, String> cache = customCache.getCache();
```

### 📚 Multi-Level Cache

The `FlexibleMultiLevelCacheService` provides sophisticated management of multiple cache levels.

```java
// Create primary cache (memory)
CacheServiceInterface<Cache<String, String>, String> caffeineCache =
    CacheServiceFactory.createCaffeineCache();

// Initialize multi-level cache service
FlexibleMultiLevelCacheService flexibleMultiLevelCacheService =
    CacheServiceFactory.createFlexibleMultiLevelCacheService(Set.of(
        TieredCacheLevel.of(1, caffeineCache.getCache())
    ));
```

## 🤝 Contributing

Contributions are welcome! Feel free to:

- 🐛 Report bugs
- 💡 Suggest new features
- 📝 Improve documentation
- 🔧 Submit pull requests

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)


