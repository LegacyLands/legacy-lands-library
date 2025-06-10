package net.legacy.library.player.test;

import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.PlayerLauncher;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * Shared test connection resource providing common database configurations
 * and service instances for player module integration tests.
 *
 * <p>This utility class centralizes the creation and management of test database
 * connections including MongoDB and Redis configurations, as well as pre-configured
 * LegacyPlayerDataService instances to avoid duplication across test methods.
 *
 * <p>All instances are created lazily and cached for reuse throughout the test session.
 * The class provides thread-safe access to shared resources and handles proper cleanup.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 02:30
 */
public class TestConnectionResource {
    private static final String TEST_DATABASE_NAME = "legacy_lands_test_player";
    private static final String REDIS_URL = "redis://localhost:6379";
    private static final String MONGODB_URL = "mongodb://localhost:27017";

    private static final AtomicReference<MongoDBConnectionConfig> mongoConfigRef = new AtomicReference<>();
    private static final AtomicReference<Config> redisConfigRef = new AtomicReference<>();
    private static final Object serviceLock = new Object();
    private static volatile LegacyPlayerDataService sharedService;

    /**
     * Get or create MongoDB connection configuration for tests.
     *
     * @return configured MongoDBConnectionConfig instance
     */
    public static MongoDBConnectionConfig getMongoConfig() {
        MongoDBConnectionConfig existing = mongoConfigRef.get();
        if (existing != null) {
            return existing;
        }

        // Only create if null, use compareAndSet to ensure atomic creation
        MongoDBConnectionConfig newConfig = new MongoDBConnectionConfig(TEST_DATABASE_NAME, MONGODB_URL);
        if (mongoConfigRef.compareAndSet(null, newConfig)) {
            return newConfig;
        } else {
            // Another thread created it, close our attempt and return the existing one
            newConfig.close();
            return mongoConfigRef.get();
        }
    }

    /**
     * Get or create Redis configuration for tests.
     *
     * @return configured Redis Config instance
     */
    public static Config getRedisConfig() {
        Config existing = redisConfigRef.get();
        if (existing != null) {
            return existing;
        }

        // Only create if null, use compareAndSet to ensure atomic creation
        Config newConfig = new Config();
        newConfig.useSingleServer()
                .setAddress(REDIS_URL)
                .setConnectionPoolSize(64)
                .setConnectionMinimumIdleSize(10)
                .setIdleConnectionTimeout(10000)
                .setConnectTimeout(10000)
                .setTimeout(3000)
                .setRetryAttempts(3)
                .setRetryInterval(1500);

        if (redisConfigRef.compareAndSet(null, newConfig)) {
            return newConfig;
        } else {
            // Another thread created it, return the existing one
            return redisConfigRef.get();
        }
    }

    /**
     * Create a new LegacyPlayerDataService with a unique name for testing.
     *
     * @param testName the test name to create a unique service identifier
     * @return new LegacyPlayerDataService instance
     */
    public static LegacyPlayerDataService createTestService(String testName) {
        String serviceName = "test-" + testName + "-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();

        return LegacyPlayerDataService.of(
                serviceName,
                getMongoConfig(),
                getRedisConfig(),
                Duration.ofMinutes(30), // Shorter auto-save interval to reduce lock contention
                List.of("net.legacy.library.player"),
                List.of(PlayerLauncher.class.getClassLoader()),
                Duration.ofSeconds(1) // Longer Redis stream interval to reduce activity
        );
    }

    /**
     * Create a new LegacyPlayerDataService with custom configuration.
     *
     * @param testName                  the test name to create a unique service identifier
     * @param autoSaveInterval          the interval between auto-save operations
     * @param redisStreamAcceptInterval the interval for accepting Redis stream messages
     * @return new LegacyPlayerDataService instance
     */
    public static LegacyPlayerDataService createTestService(String testName, Duration autoSaveInterval, Duration redisStreamAcceptInterval) {
        String serviceName = "test-" + testName + "-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();

        return LegacyPlayerDataService.of(
                serviceName,
                getMongoConfig(),
                getRedisConfig(),
                autoSaveInterval,
                List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()),
                redisStreamAcceptInterval
        );
    }

    /**
     * Get or create a shared LegacyPlayerDataService instance for tests that don't require isolation.
     * This service is reused across multiple test methods to improve performance.
     *
     * @return shared LegacyPlayerDataService instance
     */
    public static LegacyPlayerDataService getSharedService() {
        if (sharedService == null) {
            synchronized (serviceLock) {
                if (sharedService == null) {
                    String serviceName = "shared-test-service-" + System.currentTimeMillis();
                    sharedService = LegacyPlayerDataService.of(
                            serviceName,
                            getMongoConfig(),
                            getRedisConfig(),
                            List.of("net.legacy.library.player.task.redis.impl"),
                            List.of(TestConnectionResource.class.getClassLoader())
                    );
                }
            }
        }
        return sharedService;
    }

    /**
     * Get the test database name used by all test services.
     *
     * @return test database name
     */
    public static String getTestDatabaseName() {
        return TEST_DATABASE_NAME;
    }

    /**
     * Get the Redis URL used by all test services.
     *
     * @return Redis connection URL
     */
    public static String getRedisUrl() {
        return REDIS_URL;
    }

    /**
     * Get the MongoDB URL used by all test services.
     *
     * @return MongoDB connection URL
     */
    public static String getMongoUrl() {
        return MONGODB_URL;
    }

    /**
     * Create a new LegacyEntityDataService with a unique name for testing.
     *
     * @param testName the test name to create a unique service identifier
     * @return new LegacyEntityDataService instance
     */
    public static LegacyEntityDataService createTestEntityService(String testName) {
        String serviceName = "test-entity-" + testName + "-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();

        return LegacyEntityDataService.of(
                serviceName,
                getMongoConfig(),
                getRedisConfig(),
                Duration.ofMinutes(30), // Auto-save interval
                List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()),
                Duration.ofSeconds(1) // Redis stream interval
        );
    }

    /**
     * Create a new LegacyEntityDataService with custom configuration.
     *
     * @param testName                  the test name to create a unique service identifier
     * @param autoSaveInterval          the interval between auto-save operations
     * @param redisStreamAcceptInterval the interval for accepting Redis stream messages
     * @return new LegacyEntityDataService instance
     */
    public static LegacyEntityDataService createTestEntityService(String testName, Duration autoSaveInterval, Duration redisStreamAcceptInterval) {
        String serviceName = "test-entity-" + testName + "-" + System.currentTimeMillis() + "-" + Thread.currentThread().threadId();

        return LegacyEntityDataService.of(
                serviceName,
                getMongoConfig(),
                getRedisConfig(),
                autoSaveInterval,
                List.of("net.legacy.library.player"),
                List.of(PlayerLauncher.class.getClassLoader()),
                redisStreamAcceptInterval
        );
    }
}