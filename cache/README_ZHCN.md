### 缓存 (Cache) 模块

本模块提供了一个灵活的缓存解决方案，支持 `Caffeine` 的 `Cache` 和 `AsyncCache`，以及一个内存型的 `Redis`
数据库实现，提供了函数式编程支持和自动锁处理。

### 用法

```kotlin
// Dependencies
dependencies {
    // cache module
    compileOnly(files("libs/cache-1.0-SNAPSHOT.jar"))
}
```

### 举个例子

```java
public class CacheLauncher {
    public static void main(String[] args) {
        Config config = new Config();
        config.useSingleServer().setAddress("redis://127.0.0.1:6379");
        

        /*
         * Redis 缓存
         */
        RedisCacheServiceInterface redisCache =
                CacheServiceFactory.createRedisCache(config);

        // get method 会返回 object 对象, getWithType 可以指定返回类型
        int integer = redisCache.getWithType(
                // 获取缓存
                cache -> cache.getBucket("key").get(),

                // 如果未命中则执行此操作，比如向数据库查询
                () -> 1,

                // 如果 cacheAfterQuery 为 true，则执行此操作，存储到缓存
                (cache, queryValue) -> cache.getBucket("key").set(queryValue),

                // cacheAfterQuery
                true
        );

        // string
        String string = redisCache.getWithType(
                // 获取缓存
                cache -> cache.getBucket("key2").get(),

                // 如果未命中则执行此操作，比如向数据库查询
                () -> "qwq",

                // 如果 cacheAfterQuery 为 true，则执行此操作，存储到缓存
                (cache, queryValue) -> cache.getBucket("key2").set(queryValue),

                // cacheAfterQuery
                true
        );

        // 对于 redisCacheService，建议使用 redissonClient 来获取锁
        redisCache.execute(
                // 获取锁
                redissonClient -> redissonClient.getLock("a"),

                // do something
                redissonClient -> redissonClient.getBucket("a").get(),

                // 锁设置
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );


        /*
         * Caffeine 缓存
         *
         * Cache<Integer, String>
         *      - 缓存键与值的类型
         */
        CacheServiceInterface<Cache<Integer, String>, String> caffeineCache =
                CacheServiceFactory.createCaffeineCache();

        // get
        String qwq = caffeineCache.get(
                // 获取缓存
                cache -> cache.getIfPresent(1),

                // 如果未命中则执行此操作，比如向数据库查询
                () -> "qwq",

                // 如果 cacheAfterQuery 为 true，则执行此操作，存储到缓存
                (cache, queryValue) -> cache.put(1, queryValue),

                // cacheAfterQuery
                true
        );

        // get with lock
        String qwq2 = caffeineCache.get(
                // 获取锁
                cache -> new ReentrantLock(),

                // 获取缓存
                cache -> cache.getIfPresent(1),

                // 如果未命中则执行此操作，比如向数据库查询
                () -> "qwq",

                // 如果 cacheAfterQuery 为 true，则执行此操作，存储到缓存
                (cache, queryValue) -> cache.put(1, queryValue),

                // cacheAfterQuery
                true,

                // 锁设置
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );

        // 使用锁来线程安全的执行某些操作
        caffeineCache.execute(
                // 获取锁
                cache -> new ReentrantLock(),

                // do something
                cache -> cache.getIfPresent(1),

                // 锁设置
                LockSettings.of(1, 1, TimeUnit.MINUTES)
        );

        /*
         * caffeine 的大多数方法都是线程安全的，
         * 我们可以直接使用 getCache() 来操作这些方法。
         */
        caffeineCache.getCache().put(2, "hi");


        /*
         * 自定义缓存
         *
         * CacheServiceInterface<Map<Integer, String>, String> customCache
         *                                            - value type
         *                      - 实现缓存的容器类型, e.g. Map<Integer, String>
         *
         * CacheServiceFactory.createCustomCache(new ConcurrentHashMap<>())
         *                                      - 实现缓存的实际容器对象, e.g. ConcurrentHashMap<>
         */
        CacheServiceInterface<Map<Integer, String>, String> customCache =
                CacheServiceFactory.createCustomCache(new ConcurrentHashMap<>());

        // 获取实现缓存，我们可以像其他缓存服务一样使用 get，execute 等方法
        Map<Integer, String> cache = customCache.getCache();
        

        /*
         * 多级缓存
         */
        Set<TieredCacheLevel<?, ?>> tiers = Set.of(
                // L1 cache 是 caffeine
                TieredCacheLevel.of("L1", caffeineCache.getCache()),
                
                // L2 cache 是 redis
                TieredCacheLevel.of("L2", redisCache.getCache())
        );

        FlexibleMultiLevelCacheService multiLevelCache =
                CacheServiceFactory.createFlexibleMultiLevelCacheService(tiers);

        // get cache
        Optional<TieredCacheLevel<?, ?>> l1 = multiLevelCache.getCacheLevel("L1");
    }
}
```