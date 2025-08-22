package net.legacy.library.cache.test;

import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.AbstractCacheService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BiConsumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Test class for AbstractCacheService core functionality.
 *
 * <p>This test class validates the abstract cache service implementation,
 * including both locked and non-locked cache operations, cache miss handling,
 * and storage behavior configurations.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 18:30
 */
@ModuleTest(
        testName = "abstract-cache-service-test",
        description = "Tests AbstractCacheService core cache operations and behaviors",
        tags = {"cache", "service", "abstract", "core"},
        priority = 1,
        timeout = 6000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AbstractCacheServiceTest {

    /**
     * Tests basic cache hit scenario without locking.
     */
    public static boolean testBasicCacheHit() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            // Pre-populate cache
            cache.put("test-key", "cached-value");
            cache.resetCounters();

            Function<TestCache, String> getCacheFunction = c -> c.get("test-key");
            Supplier<String> query = () -> "computed-value";
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("test-key", v);

            String result = service.get(getCacheFunction, query, cacheBiConsumer, true);

            boolean correctValue = "cached-value".equals(result);
            boolean accessedCache = cache.getAccessCount() == 1;
            boolean didNotStore = cache.getStoreCount() == 0; // Should not store on cache hit

            TestLogger.logInfo("cache", "Basic cache hit test: correctValue=%s, accessedCache=%s, didNotStore=%s",
                    correctValue, accessedCache, didNotStore);

            return correctValue && accessedCache && didNotStore;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Basic cache hit test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache miss scenario with storage after query.
     */
    public static boolean testCacheMissWithStorage() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            Function<TestCache, String> getCacheFunction = c -> c.get("missing-key");
            Supplier<String> query = () -> "computed-value";
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("missing-key", v);

            String result = service.get(getCacheFunction, query, cacheBiConsumer, true);

            boolean correctValue = "computed-value".equals(result);
            boolean accessedCache = cache.getAccessCount() == 1;
            boolean storedValue = cache.getStoreCount() == 1;
            boolean valueInCache = "computed-value".equals(cache.get("missing-key"));

            TestLogger.logInfo("cache", "Cache miss with storage test: correctValue=%s, accessedCache=%s, storedValue=%s, valueInCache=%s",
                    correctValue, accessedCache, storedValue, valueInCache);

            return correctValue && accessedCache && storedValue && valueInCache;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache miss with storage test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache miss scenario without storage after query.
     */
    public static boolean testCacheMissWithoutStorage() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            Function<TestCache, String> getCacheFunction = c -> c.get("missing-key");
            Supplier<String> query = () -> "computed-value";
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("missing-key", v);

            String result = service.get(getCacheFunction, query, cacheBiConsumer, false);

            boolean correctValue = "computed-value".equals(result);
            boolean accessedCache = cache.getAccessCount() == 1;
            boolean didNotStore = cache.getStoreCount() == 0; // cacheAfterQuery = false
            boolean valueNotInCache = !cache.containsKey("missing-key");

            TestLogger.logInfo("cache", "Cache miss without storage test: correctValue=%s, accessedCache=%s, didNotStore=%s, valueNotInCache=%s",
                    correctValue, accessedCache, didNotStore, valueNotInCache);

            return correctValue && accessedCache && didNotStore && valueNotInCache;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache miss without storage test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests locked cache operations.
     */
    public static boolean testLockedCacheOperations() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            Function<TestCache, Lock> getLockFunction = TestCache::getLock;
            Function<TestCache, String> getCacheFunction = c -> c.get("locked-key");
            Supplier<String> query = () -> "locked-computed-value";
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("locked-key", v);
            LockSettings lockSettings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            String result = service.get(getLockFunction, getCacheFunction, query,
                    cacheBiConsumer, true, lockSettings);

            boolean correctValue = "locked-computed-value".equals(result);
            boolean accessedCache = cache.getAccessCount() == 1;
            boolean storedValue = cache.getStoreCount() == 1;
            boolean valueInCache = "locked-computed-value".equals(cache.get("locked-key"));

            TestLogger.logInfo("cache", "Locked cache operations test: correctValue=%s, accessedCache=%s, storedValue=%s, valueInCache=%s",
                    correctValue, accessedCache, storedValue, valueInCache);

            return correctValue && accessedCache && storedValue && valueInCache;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Locked cache operations test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests null value handling in cache operations.
     */
    public static boolean testNullValueHandling() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            // Test null from cache function
            Function<TestCache, String> getCacheFunction = c -> null;
            Supplier<String> query = () -> "computed-value";
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("null-key", v);

            String result1 = service.get(getCacheFunction, query, cacheBiConsumer, true);
            boolean handledNullCache = "computed-value".equals(result1);

            // Test null from query
            Function<TestCache, String> getCacheFunction2 = c -> null;
            Supplier<String> nullQuery = () -> null;
            BiConsumer<TestCache, String> cacheBiConsumer2 = (c, v) -> c.put("null-key2", v);

            String result2 = service.get(getCacheFunction2, nullQuery, cacheBiConsumer2, true);
            boolean handledNullQuery = result2 == null;
            boolean didNotStoreNull = !cache.containsKey("null-key2"); // Should not store null

            TestLogger.logInfo("cache", "Null value handling test: handledNullCache=%s, handledNullQuery=%s, didNotStoreNull=%s",
                    handledNullCache, handledNullQuery, didNotStoreNull);

            return handledNullCache && handledNullQuery && didNotStoreNull;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Null value handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent cache access without locks.
     */
    public static boolean testConcurrentCacheAccess() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(threadCount);
            AtomicInteger queryCount = new AtomicInteger(0);
            String[] results = new String[threadCount];

            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                new Thread(() -> {
                    try {
                        startLatch.await();

                        Function<TestCache, String> getCacheFunction = c -> c.get("concurrent-key");
                        Supplier<String> query = () -> {
                            queryCount.incrementAndGet();
                            return "computed-" + threadIndex;
                        };
                        BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("concurrent-key", v);

                        results[threadIndex] = service.get(getCacheFunction, query, cacheBiConsumer, true);

                    } catch (Exception exception) {
                        TestLogger.logFailure("cache", "Concurrent thread %d failed: %s", threadIndex, exception.getMessage());
                    } finally {
                        completeLatch.countDown();
                    }
                }).start();
            }

            // Start all threads
            startLatch.countDown();

            // Wait for completion
            boolean completed = completeLatch.await(3, TimeUnit.SECONDS);

            if (!completed) {
                TestLogger.logFailure("cache", "Concurrent access test timed out");
                return false;
            }

            // Check results
            boolean allResultsPresent = true;
            for (String result : results) {
                if (result == null) {
                    allResultsPresent = false;
                    break;
                }
            }

            int totalQueries = queryCount.get();
            boolean someQueriesExecuted = totalQueries > 0;

            TestLogger.logInfo("cache", "Concurrent cache access test: allResultsPresent=%s, totalQueries=%d, someQueriesExecuted=%s",
                    allResultsPresent, totalQueries, someQueriesExecuted);

            return completed && allResultsPresent && someQueriesExecuted;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Concurrent cache access test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests exception handling in query supplier.
     */
    public static boolean testQueryExceptionHandling() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            Function<TestCache, String> getCacheFunction = c -> null; // Cache miss
            Supplier<String> failingQuery = () -> {
                throw new RuntimeException("Query failed");
            };
            BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put("exception-key", v);

            try {
                service.get(getCacheFunction, failingQuery, cacheBiConsumer, true);
                TestLogger.logFailure("cache", "Query exception test failed: expected RuntimeException");
                return false;
            } catch (RuntimeException expected) {
                boolean correctException = "Query failed".equals(expected.getMessage());
                boolean didNotStore = cache.getStoreCount() == 0;

                TestLogger.logInfo("cache", "Query exception handling test: correctException=%s, didNotStore=%s",
                        correctException, didNotStore);

                return correctException && didNotStore;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Query exception handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cache service performance with multiple operations.
     */
    public static boolean testCacheServicePerformance() {
        try {
            TestCache cache = new TestCache();
            TestCacheService service = new TestCacheService(cache);

            long startTime = System.currentTimeMillis();
            int operationCount = 100;

            for (int i = 0; i < operationCount; i++) {
                final int index = i; // Make variable effectively final
                String key = "perf-key-" + index;
                Function<TestCache, String> getCacheFunction = c -> c.get(key);
                Supplier<String> query = () -> "value-" + index;
                BiConsumer<TestCache, String> cacheBiConsumer = (c, v) -> c.put(key, v);

                service.get(getCacheFunction, query, cacheBiConsumer, true);
            }

            long duration = System.currentTimeMillis() - startTime;

            boolean completedInTime = duration < 1000; // Should complete within 1 second
            boolean allValuesStored = cache.size() == operationCount;

            TestLogger.logInfo("cache", "Cache service performance test: duration=%dms, operations=%d, completedInTime=%s, allValuesStored=%s",
                    duration, operationCount, completedInTime, allValuesStored);

            return completedInTime && allValuesStored;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Cache service performance test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test cache implementation using ConcurrentHashMap.
     */
    private static class TestCache extends ConcurrentHashMap<String, String> {

        private final ReentrantLock lock = new ReentrantLock();
        private final AtomicInteger accessCount = new AtomicInteger(0);
        private final AtomicInteger storeCount = new AtomicInteger(0);

        public Lock getLock() {
            return lock;
        }

        public int getAccessCount() {
            return accessCount.get();
        }

        public int getStoreCount() {
            return storeCount.get();
        }

        @Override
        public String get(Object key) {
            accessCount.incrementAndGet();
            return super.get(key);
        }

        @Override
        public String put(String key, String value) {
            storeCount.incrementAndGet();
            return super.put(key, value);
        }

        public void resetCounters() {
            accessCount.set(0);
            storeCount.set(0);
        }

    }

    /**
     * Concrete implementation of AbstractCacheService for testing.
     */
    private static class TestCacheService extends AbstractCacheService<TestCache, String> {

        public TestCacheService(TestCache cache) {
            super(cache);
        }

        // Expose getResource for testing
        public TestCache getTestCache() {
            return getResource();
        }

    }

}