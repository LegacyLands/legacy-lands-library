package net.legacy.library.cache.test;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import lombok.Getter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.cache.service.caffeine.CaffeineCacheService;
import net.legacy.library.cache.service.custom.CustomCacheService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import org.jetbrains.annotations.NotNull;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Test class for specific cache implementation functionality.
 *
 * <p>This test class validates concrete cache implementations including
 * Caffeine cache, custom cache, and factory creation patterns.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 18:45
 */
@ModuleTest(
        testName = "cache-implementations-test",
        description = "Tests specific cache implementations and factory patterns",
        tags = {"cache", "implementations", "caffeine", "factory"},
        priority = 2,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class CacheImplementationsTest {

    /**
     * Tests CaffeineCacheService basic functionality.
     */
    public static boolean testCaffeineCacheService() {
        try {
            CaffeineCacheService<String, String> service = new CaffeineCacheService<>();

            Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent("caffeine-key");
            Supplier<String> query = () -> "caffeine-computed-value";
            BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put("caffeine-key", value);

            // Test cache miss and store
            String result1 = service.get(getCacheFunction, query, cacheBiConsumer, true);
            boolean correctFirstResult = "caffeine-computed-value".equals(result1);

            // Test cache hit
            String result2 = service.get(getCacheFunction, () -> "should-not-execute", cacheBiConsumer, false);
            boolean correctSecondResult = "caffeine-computed-value".equals(result2);

            // Verify value is in cache
            Cache<String, String> cache = service.getResource();
            String cachedValue = cache.getIfPresent("caffeine-key");
            boolean valueInCache = "caffeine-computed-value".equals(cachedValue);

            TestLogger.logInfo("cache", "Caffeine cache service test: firstResult=" + correctFirstResult +
                    ", secondResult=" + correctSecondResult + ", valueInCache=" + valueInCache);

            return correctFirstResult && correctSecondResult && valueInCache;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Caffeine cache service test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests CaffeineCacheService with custom cache configuration.
     */
    public static boolean testCaffeineCacheServiceWithCustomConfig() {
        try {
            // Create custom Caffeine cache with expiration
            Cache<String, String> customCache = Caffeine.newBuilder()
                    .maximumSize(100)
                    .expireAfterWrite(10, TimeUnit.SECONDS)
                    .build();

            CaffeineCacheService<String, String> service = new CaffeineCacheService<>(customCache);

            Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent("custom-key");
            Supplier<String> query = () -> "custom-computed-value";
            BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put("custom-key", value);

            String result = service.get(getCacheFunction, query, cacheBiConsumer, true);

            boolean correctResult = "custom-computed-value".equals(result);
            boolean valueInCache = "custom-computed-value".equals(customCache.getIfPresent("custom-key"));

            // Verify cache stats (if available)
            long cacheSize = customCache.estimatedSize();
            boolean hasCacheStats = cacheSize > 0;

            TestLogger.logInfo("cache", "Custom Caffeine config test: correctResult=" + correctResult +
                    ", valueInCache=" + valueInCache + ", cacheSize=" + cacheSize + ", hasCacheStats=" + hasCacheStats);

            return correctResult && valueInCache && hasCacheStats;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Custom Caffeine config test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests CustomCacheService functionality.
     */
    public static boolean testCustomCacheService() {
        try {
            TestCustomCache customCache = new TestCustomCache();
            CustomCacheService<TestCustomCache, String> service = new CustomCacheService<>(customCache);

            Function<TestCustomCache, String> getCacheFunction = cache -> cache.get("custom-service-key");
            Supplier<String> query = () -> "custom-service-value";
            BiConsumer<TestCustomCache, String> cacheBiConsumer = (cache, value) -> cache.put("custom-service-key", value);

            customCache.resetCounters();

            String result = service.get(getCacheFunction, query, cacheBiConsumer, true);

            boolean correctResult = "custom-service-value".equals(result);
            boolean accessedCache = customCache.getGetCount() == 1;
            boolean storedValue = customCache.getPutCount() == 1;
            boolean valueInCache = "custom-service-value".equals(customCache.get("custom-service-key"));

            TestLogger.logInfo("cache", "Custom cache service test: correctResult=" + correctResult +
                    ", accessedCache=" + accessedCache + ", storedValue=" + storedValue +
                    ", valueInCache=" + valueInCache);

            return correctResult && accessedCache && storedValue && valueInCache;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Custom cache service test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests CacheServiceFactory creation methods.
     */
    public static boolean testCacheServiceFactory() {
        try {
            // Test Caffeine cache factory creation
            Cache<String, String> caffeineCache = Caffeine.newBuilder()
                    .maximumSize(50)
                    .build();

            CacheServiceInterface<Cache<String, String>, String> caffeineService =
                    CacheServiceFactory.createCaffeineCache(caffeineCache);

            boolean caffeineServiceCreated = caffeineService != null;
            boolean caffeineServiceCorrectType = caffeineService instanceof CaffeineCacheService;

            // Test Custom cache factory creation
            TestCustomCache customCache = new TestCustomCache();
            CacheServiceInterface<TestCustomCache, String> customService =
                    CacheServiceFactory.createCustomCache(customCache);

            boolean customServiceCreated = customService != null;
            boolean customServiceCorrectType = customService instanceof CustomCacheService;

            TestLogger.logInfo("cache", "Cache service factory test: caffeineCreated=" + caffeineServiceCreated +
                    ", caffeineCorrectType=" + caffeineServiceCorrectType +
                    ", customCreated=" + customServiceCreated +
                    ", customCorrectType=" + customServiceCorrectType);

            return caffeineServiceCreated && caffeineServiceCorrectType &&
                    customServiceCreated && customServiceCorrectType;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache service factory test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache expiration behavior with Caffeine.
     */
    public static boolean testCacheExpiration() {
        try {
            // Create cache with very short expiration for testing
            Cache<String, String> expiringCache = Caffeine.newBuilder()
                    .expireAfterWrite(100, TimeUnit.MILLISECONDS)
                    .build();

            CaffeineCacheService<String, String> service = new CaffeineCacheService<>(expiringCache);

            Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent("expiring-key");
            Supplier<String> query = () -> "expiring-value";
            BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put("expiring-key", value);

            // Store value in cache
            service.get(getCacheFunction, query, cacheBiConsumer, true);

            // Verify value is initially present
            String initialValue = expiringCache.getIfPresent("expiring-key");
            boolean initiallyPresent = "expiring-value".equals(initialValue);

            // Wait for expiration
            Thread.sleep(150);

            // Force cleanup
            expiringCache.cleanUp();

            // Verify value has expired
            String expiredValue = expiringCache.getIfPresent("expiring-key");
            boolean hasExpired = expiredValue == null;

            TestLogger.logInfo("cache", "Cache expiration test: initiallyPresent=" + initiallyPresent +
                    ", hasExpired=" + hasExpired);

            return initiallyPresent && hasExpired;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache expiration test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent access to cache implementations.
     */
    public static boolean testConcurrentCacheAccess() {
        try {
            CaffeineCacheService<String, String> service = new CaffeineCacheService<>();
            AtomicInteger queryCount = new AtomicInteger(0);

            CompletableFuture<String> future1 = CompletableFuture.supplyAsync(() -> {
                Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent("concurrent-key");
                Supplier<String> query = () -> {
                    queryCount.incrementAndGet();
                    return "concurrent-value-1";
                };
                BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put("concurrent-key", value);

                return service.get(getCacheFunction, query, cacheBiConsumer, true);
            });

            CompletableFuture<String> future2 = CompletableFuture.supplyAsync(() -> {
                Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent("concurrent-key");
                Supplier<String> query = () -> {
                    queryCount.incrementAndGet();
                    return "concurrent-value-2";
                };
                BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put("concurrent-key", value);

                return service.get(getCacheFunction, query, cacheBiConsumer, true);
            });

            String result1 = future1.get(2, TimeUnit.SECONDS);
            String result2 = future2.get(2, TimeUnit.SECONDS);

            boolean bothCompleted = result1 != null && result2 != null;
            int totalQueries = queryCount.get();

            // At least one should get a computed value
            boolean hasComputedValues = result1.startsWith("concurrent-value") || result2.startsWith("concurrent-value");

            TestLogger.logInfo("cache", "Concurrent cache access test: bothCompleted=" + bothCompleted +
                    ", totalQueries=" + totalQueries + ", hasComputedValues=" + hasComputedValues);

            return bothCompleted && totalQueries > 0 && hasComputedValues;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Concurrent cache access test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache size limits and eviction.
     */
    public static boolean testCacheSizeLimits() {
        try {
            // Create cache with small size limit
            Cache<String, String> limitedCache = Caffeine.newBuilder()
                    .maximumSize(3)
                    .build();

            CaffeineCacheService<String, String> service = new CaffeineCacheService<>(limitedCache);

            // Fill cache beyond capacity
            for (int i = 0; i < 5; i++) {
                String key = "limited-key-" + i;
                String value = "limited-value-" + i;

                Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent(key);
                Supplier<String> query = () -> value;
                BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, v) -> cache.put(key, v);

                service.get(getCacheFunction, query, cacheBiConsumer, true);
            }

            // Force cleanup to trigger eviction
            limitedCache.cleanUp();

            // Wait a bit for asynchronous eviction to complete
            Thread.sleep(100);
            limitedCache.cleanUp();

            long cacheSize = limitedCache.estimatedSize();
            boolean sizeLimitRespected = cacheSize <= 3;

            // Check that some entries are still present (any entry is fine due to LRU eviction uncertainty)
            boolean hasAnyEntry = false;
            for (int i = 0; i < 5; i++) {
                String key = "limited-key-" + i;
                if (limitedCache.getIfPresent(key) != null) {
                    hasAnyEntry = true;
                    break;
                }
            }

            TestLogger.logInfo("cache", "Cache size limits test: cacheSize=" + cacheSize +
                    ", sizeLimitRespected=" + sizeLimitRespected + ", hasAnyEntry=" + hasAnyEntry);

            // Test passes if size limit is respected and cache contains some entries
            return sizeLimitRespected && hasAnyEntry;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache size limits test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache service performance with different implementations.
     */
    public static boolean testCacheServicePerformance() {
        try {
            // Test Caffeine performance
            CaffeineCacheService<String, String> caffeineService = new CaffeineCacheService<>();
            long caffeineTime = measureCachePerformance(caffeineService);

            // Test Custom cache performance
            TestCustomCache customCache = new TestCustomCache();
            CustomCacheService<TestCustomCache, String> customService = new CustomCacheService<>(customCache);
            long customTime = measureCustomCachePerformance(customService);

            boolean caffeinePerformanceGood = caffeineTime < 500; // Should complete within 500ms
            boolean customPerformanceGood = customTime < 500;

            TestLogger.logInfo("cache", "Cache performance test: caffeineTime=" + caffeineTime + "ms, " +
                    "customTime=" + customTime + "ms, caffeineGood=" + caffeinePerformanceGood +
                    ", customGood=" + customPerformanceGood);

            return caffeinePerformanceGood && customPerformanceGood;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache performance test failed: " + exception.getMessage());
            return false;
        }
    }

    private static long measureCachePerformance(CaffeineCacheService<String, String> service) {
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < 50; i++) {
            final int index = i; // Make variable effectively final
            String key = "perf-key-" + index;
            Function<Cache<String, String>, String> getCacheFunction = cache -> cache.getIfPresent(key);
            Supplier<String> query = () -> "perf-value-" + index;
            BiConsumer<Cache<String, String>, String> cacheBiConsumer = (cache, value) -> cache.put(key, value);

            service.get(getCacheFunction, query, cacheBiConsumer, true);
        }

        return System.currentTimeMillis() - startTime;
    }

    private static long measureCustomCachePerformance(CustomCacheService<TestCustomCache, String> service) {
        long startTime = System.currentTimeMillis();

        for (int i = 0; i < 50; i++) {
            final int index = i; // Make variable effectively final
            String key = "custom-perf-key-" + index;
            Function<TestCustomCache, String> getCacheFunction = cache -> cache.get(key);
            Supplier<String> query = () -> "custom-perf-value-" + index;
            BiConsumer<TestCustomCache, String> cacheBiConsumer = (cache, value) -> cache.put(key, value);

            service.get(getCacheFunction, query, cacheBiConsumer, true);
        }

        return System.currentTimeMillis() - startTime;
    }

    /**
     * Test implementation of custom cache using ConcurrentHashMap.
     */
    private static class TestCustomCache extends ConcurrentHashMap<String, String> {

        @Getter
        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger getCount = new AtomicInteger(0);
        private final AtomicInteger putCount = new AtomicInteger(0);

        @Override
        public String get(Object key) {
            getCount.incrementAndGet();
            return super.get(key);
        }

        @Override
        public String put(@NotNull String key, @NotNull String value) {
            putCount.incrementAndGet();
            return super.put(key, value);
        }

        public int getGetCount() {
            return getCount.get();
        }

        public int getPutCount() {
            return putCount.get();
        }

        public void resetCounters() {
            getCount.set(0);
            putCount.set(0);
        }

    }

}