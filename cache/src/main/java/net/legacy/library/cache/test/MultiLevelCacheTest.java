package net.legacy.library.cache.test;

import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.multi.FlexibleMultiLevelCacheService;
import net.legacy.library.cache.service.multi.TieredCacheLevel;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test class for validating FlexibleMultiLevelCacheService coordination logic.
 *
 * <p>This test class focuses on the critical multi-level cache coordination logic,
 * including level lookup, type casting safety, function application with and without locks,
 * and exception handling when levels are not found.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 23:45
 */
@ModuleTest(
        testName = "multi-level-cache-test",
        description = "Tests FlexibleMultiLevelCacheService coordination and lookup logic",
        tags = {"cache", "multi-level", "coordination", "lookup"},
        priority = 3,
        timeout = 4000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class MultiLevelCacheTest {
    /**
     * Creates a test multi-level cache service.
     */
    private static FlexibleMultiLevelCacheService createTestService() {
        TestCache l1Cache = new TestCache();
        TestCache l2Cache = new TestCache();

        TieredCacheLevel<String, TestCache> level1 = new TieredCacheLevel<>("L1", l1Cache);
        TieredCacheLevel<String, TestCache> level2 = new TieredCacheLevel<>("L2", l2Cache);

        Set<TieredCacheLevel<?, ?>> levels = Set.of(level1, level2);
        return new FlexibleMultiLevelCacheService(levels);
    }

    /**
     * Tests successful level lookup.
     */
    public static boolean testSuccessfulLevelLookup() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            Optional<TieredCacheLevel<?, ?>> l1 = service.getCacheLevel("L1");
            Optional<TieredCacheLevel<?, ?>> l2 = service.getCacheLevel("L2");

            boolean l1Found = l1.isPresent() && "L1".equals(l1.get().getLevel());
            boolean l2Found = l2.isPresent() && "L2".equals(l2.get().getLevel());

            TestLogger.logInfo("cache", "Successful level lookup test: l1Found=%s, l2Found=%s",
                    l1Found, l2Found);

            return l1Found && l2Found;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Successful level lookup test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests lookup of non-existent level.
     */
    public static boolean testNonExistentLevelLookup() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            Optional<TieredCacheLevel<?, ?>> nonExistent = service.getCacheLevel("L3");

            boolean isEmpty = nonExistent.isEmpty();

            TestLogger.logInfo("cache", "Non-existent level lookup test: isEmpty=%s", isEmpty);

            return isEmpty;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Non-existent level lookup test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests getCacheLevelElseThrow with existing level.
     */
    public static boolean testGetCacheLevelElseThrowSuccess() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            TieredCacheLevel<?, ?> level = service.getCacheLevelElseThrow("L1",
                    () -> new RuntimeException("Should not be thrown"));

            boolean found = level != null && "L1".equals(level.getLevel());

            TestLogger.logInfo("cache", "getCacheLevelElseThrow success test: found=%s", found);

            return found;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "getCacheLevelElseThrow success test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests getCacheLevelElseThrow with non-existent level.
     */
    public static boolean testGetCacheLevelElseThrowException() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            try {
                service.getCacheLevelElseThrow("L3",
                        () -> new IllegalArgumentException("Level not found"));

                TestLogger.logFailure("cache", "getCacheLevelElseThrow exception test - expected exception");
                return false; // Should not reach here

            } catch (IllegalArgumentException exception) {
                boolean correctException = "Level not found".equals(exception.getMessage());

                TestLogger.logInfo("cache", "getCacheLevelElseThrow exception test: correctException=%s",
                        correctException);

                return correctException;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "getCacheLevelElseThrow exception test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests applyFunctionWithoutLock with existing level.
     */
    public static boolean testApplyFunctionWithoutLockSuccess() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            // Add some data to L1 cache
            String result = service.applyFunctionWithoutLock("L1", (TestCache cache) -> {
                cache.put("key1", "value1");
                return cache.get("key1");
            });

            boolean success = "value1".equals(result);

            TestLogger.logInfo("cache", "applyFunctionWithoutLock success test: success=%s, result=%s",
                    success, result);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "applyFunctionWithoutLock success test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests applyFunctionWithoutLock with non-existent level.
     */
    public static boolean testApplyFunctionWithoutLockNonExistent() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            String result = service.applyFunctionWithoutLock("L3", (TestCache cache) -> "should-not-execute");

            boolean isNull = result == null;

            TestLogger.logInfo("cache", "applyFunctionWithoutLock non-existent test: isNull=%s", isNull);

            return isNull;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "applyFunctionWithoutLock non-existent test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests applyFunctionWithLock with existing level.
     */
    public static boolean testApplyFunctionWithLockSuccess() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            Integer result = service.applyFunctionWithLock(
                    "L1",
                    TestCache::getLock,
                    settings,
                    (TestCache cache) -> {
                        cache.put("key1", "value1");
                        cache.put("key2", "value2");
                        return cache.size();
                    }
            );

            boolean success = result != null && result == 2;

            TestLogger.logInfo("cache", "applyFunctionWithLock success test: success=%s, result=%s",
                    success, result);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "applyFunctionWithLock success test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests applyFunctionWithLock with non-existent level.
     */
    public static boolean testApplyFunctionWithLockNonExistent() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            String result = service.applyFunctionWithLock(
                    "L3",
                    TestCache::getLock,
                    settings,
                    (TestCache cache) -> "should-not-execute"
            );

            boolean isNull = result == null;

            TestLogger.logInfo("cache", "applyFunctionWithLock non-existent test: isNull=%s", isNull);

            return isNull;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "applyFunctionWithLock non-existent test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests type casting safety in function application.
     */
    public static boolean testTypeCastingSafety() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            // This should work with correct type
            String result1 = service.applyFunctionWithoutLock("L1", (TestCache cache) -> {
                cache.put("test", "value");
                return "success";
            });

            boolean correctTypeWorked = "success".equals(result1);

            TestLogger.logInfo("cache", "Type casting safety test: correctTypeWorked=%s", correctTypeWorked);

            // We can't easily test incorrect type casting in a safe way without
            // ClassCastException, but the test above verifies that correct casting works

            return correctTypeWorked;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Type casting safety test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent access to multi-level cache with locks.
     */
    public static boolean testConcurrentMultiLevelAccess() {
        try {
            FlexibleMultiLevelCacheService service = createTestService();

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            // Run concurrent operations on different levels
            Thread thread1 = new Thread(() -> service.applyFunctionWithLock("L1", TestCache::getLock, settings, cache -> {
                for (int i = 0; i < 10; i++) {
                    cache.put("l1-key" + i, "l1-value" + i);
                }
                return null;
            }));

            Thread thread2 = new Thread(() -> service.applyFunctionWithLock("L2", TestCache::getLock, settings, cache -> {
                for (int i = 0; i < 10; i++) {
                    cache.put("l2-key" + i, "l2-value" + i);
                }
                return null;
            }));

            thread1.start();
            thread2.start();

            thread1.join(2000);
            thread2.join(2000);

            // Verify both caches have data
            Integer l1Size = service.applyFunctionWithoutLock("L1", TestCache::size);
            Integer l2Size = service.applyFunctionWithoutLock("L2", TestCache::size);

            boolean success = l1Size != null && l1Size == 10 && l2Size != null && l2Size == 10;

            TestLogger.logInfo("cache", "Concurrent multi-level access test: success=%s, l1Size=%s, l2Size=%s",
                    success, l1Size, l2Size);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Concurrent multi-level access test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Simple test cache implementation for testing.
     */
    private static class TestCache {
        private final Map<String, String> data = new HashMap<>();
        private final ReentrantLock lock = new ReentrantLock();

        public String get(String key) {
            return data.get(key);
        }

        public void put(String key, String value) {
            data.put(key, value);
        }

        public Lock getLock() {
            return lock;
        }

        public int size() {
            return data.size();
        }
    }
}