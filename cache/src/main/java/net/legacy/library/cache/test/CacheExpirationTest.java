package net.legacy.library.cache.test;

import net.legacy.library.cache.model.CacheItem;
import net.legacy.library.cache.model.ExpirationSettings;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.TimeUnit;

/**
 * Test class for validating CacheItem expiration logic.
 *
 * <p>This test class focuses on the critical expiration calculation and validation
 * logic in {@link CacheItem}, including edge cases like TTL -1 (never expire),
 * null values, and boundary time conditions.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@ModuleTest(
        testName = "cache-expiration-test",
        description = "Tests CacheItem expiration logic and edge cases",
        tags = {"cache", "expiration", "time-based"},
        priority = 1,
        timeout = 3000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class CacheExpirationTest {
    /**
     * Tests that items with TTL -1 never expire.
     */
    public static boolean testNeverExpireItem() {
        try {
            ExpirationSettings neverExpire = new ExpirationSettings(-1, TimeUnit.MILLISECONDS);
            CacheItem<String> item = new CacheItem<>("test-value", neverExpire);

            // Should never expire regardless of time passage
            boolean notExpiredInitially = !item.isExpired();

            // Simulate time passage (in real scenario, time would advance)
            boolean stillNotExpired = !item.isExpired();

            TestLogger.logInfo("cache", "Never-expire item test: initially=%s, after time=%s", notExpiredInitially, stillNotExpired);

            return notExpiredInitially && stillNotExpired;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Never-expire item test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that null values are always considered expired.
     */
    public static boolean testNullValueExpiration() {
        try {
            ExpirationSettings settings = new ExpirationSettings(1000, TimeUnit.MILLISECONDS);
            CacheItem<String> nullItem = new CacheItem<>(null, settings);

            boolean isExpired = nullItem.isExpired();

            TestLogger.logInfo("cache", "Null value expiration test: isExpired=%s", isExpired);

            return isExpired; // null values should always be expired
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Null value expiration test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests expiration with very short TTL.
     */
    public static boolean testShortTTLExpiration() {
        try {
            // Use very short TTL
            ExpirationSettings shortTTL = new ExpirationSettings(1, TimeUnit.MILLISECONDS);
            CacheItem<String> item = new CacheItem<>("test-value", shortTTL);

            // Wait a bit to ensure expiration
            Thread.sleep(5);

            boolean isExpired = item.isExpired();

            TestLogger.logInfo("cache", "Short TTL expiration test: isExpired=%s", isExpired);

            return isExpired; // should be expired after 5ms wait with 1ms TTL
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Short TTL expiration test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that items with sufficient TTL are not expired initially.
     */
    public static boolean testValidItemNotExpired() {
        try {
            // Use long TTL
            ExpirationSettings longTTL = new ExpirationSettings(10, TimeUnit.SECONDS);
            CacheItem<String> item = new CacheItem<>("test-value", longTTL);

            boolean isNotExpired = !item.isExpired();

            TestLogger.logInfo("cache", "Valid item not expired test: isNotExpired=%s", isNotExpired);

            return isNotExpired; // should not be expired immediately
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Valid item not expired test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests edge case with TTL exactly -1.
     */
    public static boolean testExactlyMinusOneTTL() {
        try {
            ExpirationSettings exactMinusOne = new ExpirationSettings(-1, TimeUnit.SECONDS);
            CacheItem<String> item = new CacheItem<>("test-value", exactMinusOne);

            boolean neverExpires = !item.isExpired();

            // Check that expiration time is set to -1
            long expirationTime = item.getExpirationTime();
            boolean correctExpirationTime = (expirationTime == -1);

            TestLogger.logInfo("cache", "Exactly -1 TTL test: neverExpires=%s, expirationTime=%s",
                    neverExpires, expirationTime);

            return neverExpires && correctExpirationTime;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Exactly -1 TTL test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests edge case with TTL less than -1 (should also never expire).
     */
    public static boolean testLessThanMinusOneTTL() {
        try {
            ExpirationSettings lessThanMinusOne = new ExpirationSettings(-100, TimeUnit.SECONDS);
            CacheItem<String> item = new CacheItem<>("test-value", lessThanMinusOne);

            boolean neverExpires = !item.isExpired();

            // Check that expiration time is set to -1
            long expirationTime = item.getExpirationTime();
            boolean correctExpirationTime = (expirationTime == -1);

            TestLogger.logInfo("cache", "Less than -1 TTL test: neverExpires=%s, expirationTime=%s",
                    neverExpires, expirationTime);

            return neverExpires && correctExpirationTime;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Less than -1 TTL test failed: %s", exception.getMessage());
            return false;
        }
    }
}