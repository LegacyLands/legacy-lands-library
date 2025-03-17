### Cache Module

This module provides a flexible caching solution that supports both `Caffeine`'s `Cache` and `AsyncCache`, along with an
in-memory `Redis` database implementation.
It offers functional programming support and automatic lock handling.
Included is a generic, thread-safe resource management framework, which is not only suitable for caching operations but
can also be used for any resource type requiring thread-safe access.

### Usage

```kotlin
// Dependencies
dependencies {
    // cache module
    compileOnly(files("libs/cache-1.0-SNAPSHOT.jar"))
}
```

### Thread-Safe Resource Management

`LockableInterface` and `AbstractLockable` are generic, cache-agnostic components designed to provide a thread-safe
mechanism for accessing resources.
The design goal of these components is to separate the locking operations from the specific business logic, offering a
universal and reusable locking management framework:

```java
public class CacheLauncher {
    public static void main(String[] args) {
        // Create an instance of the lockable resource (e.g., a database connection)
        DatabaseConnection dbConnection = new DatabaseConnection();
        LockableInterface<DatabaseConnection> lockableDb = AbstractLockable.of(dbConnection);

        // Execute operations under lock protection
        User user = lockableDb.execute(
                // get lock
                conn -> new ReentrantLock(),

                // the operation to be performed under lock protection
                conn -> conn.queryUserById(123),

                // lock settings
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );
    }
}
```

This design allows the same locking mechanism to be applied to a variety of different resource types, including, but not
limited to, caches, database connections, file system access, and so on.
The cache service interface inherits from this generic interface, thereby inheriting the same thread-safety
capabilities.

### Example

```java
public class CacheLauncher {
    public static void main(String[] args) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");


        /*
         * Redis cache example
         */
        RedisCacheServiceInterface redisCache =
                CacheServiceFactory.createRedisCache(config);

        // get method will return object type, getWithType can specify the return type
        int integer = redisCache.getWithType(
                // get cache value by key
                cache -> cache.getBucket("key").get(),

                // if cache miss, do this, like query from database
                () -> 1,

                // if cacheAfterQuery is true, do this, store to cache
                (cache, queryValue) -> cache.getBucket("key").set(queryValue),

                // cacheAfterQuery
                true
        );

        // string
        String string = redisCache.getWithType(
                // get cache value by key
                cache -> cache.getBucket("key2").get(),

                // if cache miss, do this, like query from database
                () -> "qwq",

                // if cacheAfterQuery is true, do this, store to cache
                (cache, queryValue) -> cache.getBucket("key2").set(queryValue),

                // cacheAfterQuery
                true
        );

        // for redisCacheService, it is recommended to use redissonClient to acquire the lock
        redisCache.execute(
                // get lock
                redissonClient -> redissonClient.getLock("a"),

                // do something
                redissonClient -> redissonClient.getBucket("a").get(),

                // lock settings
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );


        /*
         * Caffeine cache example
         *
         * Cache<Integer, String>
         *      - cache key and cache value type
         */
        CacheServiceInterface<Cache<Integer, String>, String> caffeineCache =
                CacheServiceFactory.createCaffeineCache();

        // get
        String qwq = caffeineCache.get(
                // get cache value by key
                cache -> cache.getIfPresent(1),

                // if cache miss, do this, like query from database
                () -> "qwq",

                // if cacheAfterQuery is true, do this, store to cache
                (cache, queryValue) -> cache.put(1, queryValue),

                // cacheAfterQuery
                true
        );

        // get with lock
        String qwq2 = caffeineCache.get(
                // get lock
                cache -> new ReentrantLock(),

                // get cache value by key
                cache -> cache.getIfPresent(1),

                // if cache miss, do this, like query from database
                () -> "qwq",

                // if cacheAfterQuery is true, do this, store to cache
                (cache, queryValue) -> cache.put(1, queryValue),

                // cacheAfterQuery
                true,

                // lock settings
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );

        // thread-safe execution of something
        caffeineCache.execute(
                // get lock
                cache -> new ReentrantLock(),

                // do something
                cache -> cache.getIfPresent(1),

                // lock settings
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );

        /*
         * Most of caffeine's methods are thread-safe,
         * we can directly use getResource() to operate these methods.
         */
        caffeineCache.getResource().put(2, "hi");


        /*
         * Custom cache example
         *
         * CacheServiceInterface<Map<Integer, String>, String> customCache
         *                                            - value type
         *                      - impl cache type, e.g. Map<Integer, String>
         *
         * CacheServiceFactory.createCustomCache(new ConcurrentHashMap<>())
         *                                      - impl cache, e.g. ConcurrentHashMap<>
         */
        CacheServiceInterface<Map<Integer, String>, String> customCache =
                CacheServiceFactory.createCustomCache(new ConcurrentHashMap<>());

        // get impl cache, we can use get, execute, and more method like other cache service
        Map<Integer, String> cache = customCache.getResource();


        /*
         * Multi-Level Cache
         */
        Set<TieredCacheLevel<?, ?>> tiers = Set.of(
                // L1 cache is caffeine
                TieredCacheLevel.of("L1", caffeineCache.getResource()),

                // L2 cache is redis
                TieredCacheLevel.of("L2", redisCache.getResource())
        );

        FlexibleMultiLevelCacheService multiLevelCache =
                CacheServiceFactory.createFlexibleMultiLevelCacheService(tiers);

        // get cache
        Optional<TieredCacheLevel<?, ?>> l1 = multiLevelCache.getCacheLevel("L1");
    }
}
```