### cache

The module is a caching solution that supports `Caffeine`'s `Cache` and `AsyncCache`,
as well as an in-memory `Redis` database implementation, offering functional programming support and automatic lock
handling.

The original purpose of this module was to design a L1 cache for the `mongodb` module, but now it is **general-purpose
**.

The multi-level cache originally expected to be handled by the `data` module will be directly handled by the `cache` module.

### usage

```kotlin
// Dependencies
dependencies {
    // cache module
    compileOnly(files("libs/cache-1.0-SNAPSHOT-sources.jar"))
}
```

### [Caffeine](src/main/java/net/legacy/library/cache/service/caffeine/)

`Caffeine`-based cache, including `Cache` and `AsyncCache`.

```java
public class CacheLauncher {
    public static void main(String[] args) {
        /*
         * Caffeine cache example
         *
         * Cache<Integer, String>
         *      - cache key and cache value type
         *
         * Cache<String, String>, String>
         *                       - Cache value type
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
         * Although most of caffeine's methods are thread-safe,
         * we can directly use getCache() to operate these methods.
         */
        caffeineCache.getCache().put(2, "hi");
    }
}
```

### [RedisCacheService](src/main/java/net/legacy/library/cache/service/redis/RedisCacheServiceInterface.java)

This is the Redis cache service class, usually it is used as a second-level cache.

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
    }
}
```

### [CustomCacheService](src/main/java/net/legacy/library/cache/service/custom/CustomCacheService.java)

Custom cache, yes, you can use `ConcurrentHashMap` provided by Java or other implementations you like.

```java
public class Example {
    public static void main(String[] args) {
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
        Map<Integer, String> cache = customCache.getCache();
    }
}
```

### [FlexibleMultiLevelCacheService](src/main/java/net/legacy/library/cache/service/multi/FlexibleMultiLevelCacheService.java)

This is a small service for flexibly managing multiple caches. It looks a bit complicated. Essentially, it provides the ability to quickly find and operate the specified level of cache.

```java
public class Example {
    public static void main(String[] args) {
        // level 1 cache (memory)
        CacheServiceInterface<Cache<String, String>, String> caffeineCache =
                CacheServiceFactory.createCaffeineCache();

        // idk if this is really useful, it seems to make management more complicated
        FlexibleMultiLevelCacheService flexibleMultiLevelCacheService =
                CacheServiceFactory.createFlexibleMultiLevelCacheService(Set.of(
                        // level 1 is the caffeine cache
                        TieredCacheLevel.of(1, caffeineCache.getCache())
                ));

        // get the cache from the level 1
        flexibleMultiLevelCacheService.getCacheLevel(1).ifPresent(tieredCacheLevel -> {
            // we know the level 1 cache service is CacheServiceInterface so we can safely cast it
            CacheServiceInterface<Cache<String, String>, String> cache = tieredCacheLevel.getCacheWithType();

            // do something
        });

        // apply a function to the cache of level 1 without lock
        String result = flexibleMultiLevelCacheService.applyFunctionWithoutLock(
                // level 1
                1,

                /*
                 * we know the level 1 cache service is CacheServiceInterface
                 * so we can safely cast it to CacheServiceInterface<Cache<String, String>, String>
                 *
                 * and just use the get method or do something to get the value from the cache
                 */
                (CacheServiceInterface<Cache<String, String>, String> cacheStringCacheServiceInterface) ->
                        cacheStringCacheServiceInterface.get(
                                cache -> cache.getIfPresent("qwq"),
                                () -> "qwq",
                                (cache, queryValue) -> cache.put("qwq", queryValue),
                                true
                        )
        );
    }
}
```