# 🚀 Cache Module

A powerful multi-level caching solution that leverages Caffeine and Redis, with functional programming support and automatic lock handling. This module is built for asynchronous, distributed, and multi-tier caching scenarios focused on flexible configurations and high performance.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🔄 Caffeine's in-memory caching (both sync and async)
- 🔴 Integration with Redis for distributed caching
- 🔒 Automatic lock handling using ReentrantLock or Redisson RLock
- ♻️ Easy fallback from one cache layer to another (multi-level tiered caches)
- 🎯 Functional-style interface for custom read/write logic
- ⚡ Concurrent, high-performance operations
- 🛡️ Thread-safe at all layers

## 📋 Table of Contents

- [Introduction](#introduction)
- [Installation](#installation)
- [Usage](#usage)
  - [Caffeine Cache (Synchronous and Asynchronous)](#caffeine-cache-synchronous-and-asynchronous)
  - [Redis Cache](#redis-cache)
  - [Custom Cache](#custom-cache)
  - [Multi-Level Cache](#multi-level-cache)
- [Architecture](#architecture)
  - [Core Classes](#core-classes)
  - [Locking Mechanism](#locking-mechanism)
- [Contributing](#contributing)
- [License](#license)

## Introduction

The "cache" module provides a flexible, extensible caching solution suitable for a variety of scenarios, from single-node in-memory caches to distributed systems requiring shared state across multiple nodes. By combining different caching technologies (Caffeine for in-memory and Redis for remote caching), you can create multi-tier caching strategies that optimize both performance and data consistency.

Key highlights include:
1. Standardized access methods across all cache implementations.
2. Lockable cache operations to handle concurrency, ensuring that only one thread computes or updates a cache entry at a time.
3. Simple extension points enabling custom caches to integrate seamlessly.
4. Multiple layers of caches that can be composed to form a hierarchical cache (e.g., a local memory cache tier, plus a Redis tier).

## Installation

Add the following dependency to your Gradle (Kotlin DSL) build file:

```kotlin
dependencies {
    compileOnly(files("libs/cache-1.0-SNAPSHOT-sources.jar"))
}
```

Adjust according to your preferred build system and repository hosting.

## Usage

Below are common use cases and examples showcasing how to instantiate and use the caches. For more detailed or advanced scenarios, refer to individual class documentation within the source code.

### Caffeine Cache (Synchronous and Asynchronous)

Caffeine provides a high-performance, in-memory cache with excellent hit rates. You can create either synchronous or asynchronous cache services through the CacheServiceFactory.

• Synchronous Example:

```java
// Create a synchronous Caffeine cache service
CacheServiceInterface<Cache<Integer, String>, String> caffeineCache =
    CacheServiceFactory.createCaffeineCache();

// Retrieve a value or compute it if missing
String result = caffeineCache.get(
    cache -> cache.getIfPresent(123), // Attempt retrieval
    () -> "Default Value",            // Fallback if absent
    (cache, val) -> cache.put(123, val), // Cache the computed value
    true
);
```

• Asynchronous Example:

```java
// Create an asynchronous Caffeine cache service
CacheServiceInterface<AsyncCache<Integer, String>, String> asyncCache =
    CacheServiceFactory.createCaffeineAsyncCache();

// (Example) Asynchronous retrieval or compute
String asyncValue = asyncCache.get(
    cache -> cache.synchronous().getIfPresent(123), 
    () -> "Async Default",
    (cache, val) -> cache.synchronous().put(123, val),
    true
);
```

Both offer a functional approach with optional lock usage for thread-safety.

### Redis Cache

Redis-based caching is ideal for distributed environments where multiple nodes need to share cached data:

```java
// Redis configuration
Config config = new Config();
config.useSingleServer().setAddress("redis://127.0.0.1:6379");

// Create Redis cache service
RedisCacheServiceInterface redisCache = CacheServiceFactory.createRedisCache(config);

// Retrieve a value from Redis or compute it if missing
Integer myInt = redisCache.getWithType(
    client -> client.getBucket("myKey").get(),              // Attempt retrieval
    () -> 42,                                               // Default or computed value
    (client, val) -> client.getBucket("myKey").set(val),    // Write to Redis
    true
);
```

In addition to the standard get methods, the Redis cache service offers:
• Lock-based methods with Redisson RLock.
• A clean "shutdown" method to gracefully close the Redisson client.

### Custom Cache

Implement your own cache if you need specialized features, while still benefiting from the same functional interface:

```java
// Using a ConcurrentHashMap-based custom cache
Map<Integer, String> myHashMap = new ConcurrentHashMap<>();
CacheServiceInterface<Map<Integer, String>, String> customCache =
    CacheServiceFactory.createCustomCache(myHashMap);

String computedValue = customCache.get(
    map -> map.get(111),
    () -> "HelloWorld",
    (map, val) -> map.put(111, val),
    true
);
```

### Multi-Level Cache

You can combine multiple cache layers (e.g., a fast local memory tier + a Redis tier) using FlexibleMultiLevelCacheService plus TieredCacheLevel objects. For instance:

```java
// Primary in-memory Caffeine cache
CacheServiceInterface<Cache<String, String>, String> localCache =
    CacheServiceFactory.createCaffeineCache();

// Redis cache for a secondary layer
RedisCacheServiceInterface redisCache =
    CacheServiceFactory.createRedisCache(config);

// Register these as tiers
Set<TieredCacheLevel<?, ?>> tiers = Set.of(
    TieredCacheLevel.of("LOCAL", localCache.getCache()),
    TieredCacheLevel.of("REDIS", redisCache.getCache())
);

// Create and use multi-level service
FlexibleMultiLevelCacheService multiLevelCache =
    CacheServiceFactory.createFlexibleMultiLevelCacheService(tiers);

// Example usage: interact with the LOCAL tier
String multiValue = multiLevelCache.applyFunctionWithoutLock(
    "LOCAL",
    caffeine -> ( (Cache<String, String>) caffeine ).getIfPresent("testKey")
);
```

Multi-level configurations allow you to decide priorities, fallback strategies, and lock usage for each tier, ensuring you get the speed of local caching while retaining the consistency or scalability of remote caches.

## Architecture

### Core Classes

• CacheServiceInterface
  - Defines essential get(...) methods for retrieving or computing values.
  - Implemented by various cache service classes (RedisCacheService, CaffeineCacheService, etc.).

• AbstractCacheService & AbstractLockableCache
  - Provide shared logic, enabling locked and non-locked operations.
  - Manage concurrency with Lock or Redisson RLock.

• FlexibleMultiLevelCacheService
  - Manages multiple caching tiers.
  - Uses TieredCacheLevel objects to unify them under a single interface.

• RedisCacheService & RedisCacheServiceInterface
  - Wrap RedissonClient for persistent/distributed caching.
  - Safe "shutdown" handling and extended typed retrieval methods.

### Locking Mechanism

One of the module's chief advantages is lock-based operations.
• Redisson RLock is used when a Redis-based lock is required, typically for distributed locks.
• ReentrantLock is used for local concurrency in non-Redis caches.

The built-in logic (execute(...) in AbstractLockableCache) handles tryLock with configurable wait time, ensuring any thread that fails to acquire the lock can time out gracefully. This lock-based approach ensures that only one thread updates or computes a given cache entry at a time.

## Contributing

Contributions are always welcome. You can:
- Report bugs or suggest features through the issue tracker.
- Create pull requests with enhancements, optimizations, or documentation improvements.
- Expand functionality by adding new cache implementations or multi-tier strategies.

## License

This project is licensed under the MIT License. See the [LICENSE](../LICENSE) file for more information.

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)


