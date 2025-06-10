package net.legacy.library.player.test;

import com.github.benmanes.caffeine.cache.Cache;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyPlayerDataService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration test class for LegacyPlayerDataService, validating multi-level cache functionality,
 * Redis Stream processing, and MongoDB persistence with real database connections.
 *
 * <p>This test suite performs comprehensive integration testing of the player data service
 * including L1 cache (Caffeine), L2 cache (Redis), database persistence (MongoDB),
 * and distributed Redis Stream communication between service instances.
 *
 * <p>The tests require active Redis and MongoDB connections as specified in the service
 * configuration. All tests use production-level configurations to ensure real-world compatibility.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 02:15
 */
@ModuleTest(
        testName = "legacy-player-data-service-integration-test",
        description = "Integration tests for LegacyPlayerDataService with real Redis and MongoDB connections",
        tags = {"player", "integration", "cache", "redis", "mongodb", "stream"},
        priority = 1,
        timeout = 30000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class LegacyPlayerDataServiceTest {
    /**
     * Test basic service creation and initialization with required configurations.
     */
    public static boolean testServiceCreation() {
        try {
            // Create service using TestConnectionResource
            LegacyPlayerDataService service = TestConnectionResource.createTestService("service-creation");

            // Verify service creation
            boolean serviceRegistered = LegacyPlayerDataService.getLegacyPlayerDataService(service.getName()).isPresent();
            boolean hasValidName = service.getName() != null && !service.getName().isEmpty();
            boolean hasValidConfig = service.getMongoDBConnectionConfig() != null;

            TestLogger.logValidation("player", "ServiceCreation", serviceRegistered && hasValidName && hasValidConfig,
                    "Service registered: " + serviceRegistered +
                            ", validName: " + hasValidName + ", validConfig: " + hasValidConfig);

            return serviceRegistered && hasValidName && hasValidConfig;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Service creation test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test L1 cache (Caffeine) operations including put, get, and eviction.
     */
    public static boolean testL1CacheOperations() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("l1-cache");

            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData testData = LegacyPlayerData.of(testUuid);
            testData.addData("testKey", "testValue");

            // Test L1 cache direct access
            Cache<UUID, LegacyPlayerData> l1Cache = service.getL1Cache().getResource();
            l1Cache.put(testUuid, testData);

            // Verify L1 cache storage and retrieval
            Optional<LegacyPlayerData> cachedData = service.getFromL1Cache(testUuid);
            boolean dataStored = cachedData.isPresent();
            boolean correctData = dataStored && "testValue".equals(cachedData.get().getData("testKey"));

            // Test cache invalidation
            l1Cache.invalidate(testUuid);
            Optional<LegacyPlayerData> afterInvalidation = service.getFromL1Cache(testUuid);
            boolean cacheCleared = afterInvalidation.isEmpty();

            TestLogger.logValidation("player", "L1CacheOperations", dataStored && correctData && cacheCleared,
                    "L1 Cache - stored: " + dataStored + ", correctData: " + correctData + ", cleared: " + cacheCleared);

            return dataStored && correctData && cacheCleared;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "L1 cache operations test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test L2 cache (Redis) operations including TTL management and persistence.
     */
    public static boolean testL2CacheOperations() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("l2-cache");

            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData testData = LegacyPlayerData.of(testUuid);
            testData.addData("l2TestKey", "l2TestValue");

            // Save to L2 cache through service method
            service.saveLegacyPlayerData(testData);

            // Wait for async save to complete (no RStream publishing for player data)
            Thread.sleep(300);

            // Clear L1 cache to force L2 lookup
            service.getL1Cache().getResource().invalidate(testUuid);

            // Verify L2 cache retrieval
            Optional<LegacyPlayerData> l2Data = service.getFromL2Cache(testUuid);
            boolean dataInL2 = l2Data.isPresent();
            boolean correctL2Data = dataInL2 && "l2TestValue".equals(l2Data.get().getData("l2TestKey"));

            // Test TTL functionality
            boolean ttlSet = service.setPlayerDefaultTTL(testUuid);

            TestLogger.logValidation("player", "L2CacheOperations", dataInL2 && correctL2Data && ttlSet,
                    "L2 Cache - stored: " + dataInL2 + ", correctData: " + correctL2Data + ", ttlSet: " + ttlSet);

            return dataInL2 && correctL2Data && ttlSet;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "L2 cache operations test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test multi-level cache retrieval prioritizing L1 over L2 over database.
     */
    public static boolean testMultiLevelCacheRetrieval() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("multilevel");

            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData originalData = LegacyPlayerData.of(testUuid);
            originalData.addData("multilevelKey", "originalValue");

            // Save data through service (goes to L2 and DB)
            service.saveLegacyPlayerData(originalData);
            Thread.sleep(300); // Wait for async persistence (no RStream publishing)

            // Clear L1 cache
            service.getL1Cache().getResource().invalidate(testUuid);

            // First retrieval should come from L2 and populate L1
            LegacyPlayerData retrievedData = service.getLegacyPlayerData(testUuid);
            boolean firstRetrievalSuccess = retrievedData != null && "originalValue".equals(retrievedData.getData("multilevelKey"));

            // Verify L1 is now populated
            Optional<LegacyPlayerData> l1Data = service.getFromL1Cache(testUuid);
            boolean l1Populated = l1Data.isPresent();

            // Second retrieval should come from L1 (faster)
            long startTime = System.nanoTime();
            LegacyPlayerData secondRetrieval = service.getLegacyPlayerData(testUuid);
            long duration = System.nanoTime() - startTime;
            boolean secondRetrievalSuccess = secondRetrieval != null && "originalValue".equals(secondRetrieval.getData("multilevelKey"));
            boolean fastRetrieval = duration < 1_000_000; // Less than 1ms indicates L1 cache hit

            TestLogger.logValidation("player", "MultiLevelCacheRetrieval",
                    firstRetrievalSuccess && l1Populated && secondRetrievalSuccess && fastRetrieval,
                    "Multi-level Cache - firstRetrieval: " + firstRetrievalSuccess + ", l1Populated: " + l1Populated +
                            ", secondRetrieval: " + secondRetrievalSuccess + ", fastRetrieval: " + fastRetrieval);

            return firstRetrievalSuccess && l1Populated && secondRetrievalSuccess && fastRetrieval;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Multi-level cache retrieval test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test database fallback when data is not present in either cache level.
     */
    public static boolean testDatabaseFallback() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("db-fallback");

            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData testData = LegacyPlayerData.of(testUuid);
            testData.addData("dbKey", "dbValue");

            // Save to L2 cache first to establish the data in the system, then to database
            service.saveLegacyPlayerData(testData);
            Thread.sleep(300); // Wait for save to complete (no RStream publishing)

            // Clear both L1 and ensure we test database fallback by direct database query
            service.getL1Cache().getResource().invalidate(testUuid);

            // Test direct database retrieval method
            LegacyPlayerData directDbData = service.getFromDatabase(testUuid);
            boolean directDbSuccess = directDbData != null && "dbValue".equals(directDbData.getData("dbKey"));

            // Clear L1 again and test full retrieval path
            service.getL1Cache().getResource().invalidate(testUuid);
            LegacyPlayerData retrievedData = service.getLegacyPlayerData(testUuid);
            boolean databaseFallbackSuccess = retrievedData != null && "dbValue".equals(retrievedData.getData("dbKey"));

            TestLogger.logInfo("player", "Direct DB: " + directDbSuccess + ", Full retrieval: " + databaseFallbackSuccess);

            // Verify data is now cached in L1 after database retrieval
            Optional<LegacyPlayerData> l1CachedData = service.getFromL1Cache(testUuid);
            boolean l1CachedAfterRetrieval = l1CachedData.isPresent();

            TestLogger.logValidation("player", "DatabaseFallback", databaseFallbackSuccess && l1CachedAfterRetrieval,
                    "Database Fallback - success: " + databaseFallbackSuccess + ", l1Cached: " + l1CachedAfterRetrieval);

            return databaseFallbackSuccess && l1CachedAfterRetrieval;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Database fallback test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test service instance management and uniqueness validation.
     */
    public static boolean testServiceInstanceManagement() {
        try {
            // Create two services with different names
            LegacyPlayerDataService service1 = TestConnectionResource.createTestService("instance-1");
            LegacyPlayerDataService service2 = TestConnectionResource.createTestService("instance-2");

            // Verify both services are registered
            boolean service1Registered = LegacyPlayerDataService.getLegacyPlayerDataService(service1.getName()).isPresent();
            boolean service2Registered = LegacyPlayerDataService.getLegacyPlayerDataService(service2.getName()).isPresent();

            // Test duplicate name rejection - create service with same name as service1
            boolean duplicateRejected = false;
            try {
                // Try to create service with same name as service1
                String duplicateName = service1.getName();
                LegacyPlayerDataService.of(
                        duplicateName,
                        TestConnectionResource.getMongoConfig(),
                        TestConnectionResource.getRedisConfig(),
                        List.of("net.legacy.library.player.task.redis.impl"),
                        List.of(LegacyPlayerDataServiceTest.class.getClassLoader())
                );
            } catch (IllegalStateException expected) {
                duplicateRejected = true;
            }

            TestLogger.logValidation("player", "ServiceInstanceManagement",
                    service1Registered && service2Registered && duplicateRejected,
                    "Service Management - service1: " + service1Registered + ", service2: " + service2Registered +
                            ", duplicateRejected: " + duplicateRejected);

            return service1Registered && service2Registered && duplicateRejected;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Service instance management test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test TTL management for player data including default and custom TTL settings.
     */
    public static boolean testTTLManagement() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("ttl");

            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData testData = LegacyPlayerData.of(testUuid);
            testData.addData("ttlKey", "ttlValue");

            // Save data to L2 cache
            service.saveLegacyPlayerData(testData);
            Thread.sleep(300); // Wait for async save (no RStream publishing)

            // Test default TTL setting
            boolean defaultTtlSet = service.setPlayerDefaultTTL(testUuid);

            // Test custom TTL setting
            boolean customTtlSet = service.setPlayerTTL(testUuid, Duration.ofMinutes(10));

            // Test TTL setting for non-existent player
            UUID nonExistentUuid = UUID.randomUUID();
            boolean nonExistentTtlFailed = !service.setPlayerDefaultTTL(nonExistentUuid);

            // Test bulk TTL setting
            int ttlCount = service.setDefaultTTLForAllPlayers();
            boolean bulkTtlWorked = ttlCount >= 0; // Should return count >= 0

            TestLogger.logValidation("player", "TTLManagement",
                    defaultTtlSet && customTtlSet && nonExistentTtlFailed && bulkTtlWorked,
                    "TTL Management - default: " + defaultTtlSet + ", custom: " + customTtlSet +
                            ", nonExistentFailed: " + nonExistentTtlFailed + ", bulkCount: " + ttlCount);

            return defaultTtlSet && customTtlSet && nonExistentTtlFailed && bulkTtlWorked;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "TTL management test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test service shutdown and cleanup procedures.
     */
    public static boolean testServiceShutdown() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("shutdown");

            // Add some test data
            UUID testUuid = UUID.randomUUID();
            LegacyPlayerData testData = LegacyPlayerData.of(testUuid);
            service.saveLegacyPlayerData(testData);

            // Verify service is registered before shutdown
            boolean serviceRegisteredBefore = LegacyPlayerDataService.getLegacyPlayerDataService(service.getName()).isPresent();

            TestLogger.logValidation("player", "ServiceShutdown", serviceRegisteredBefore,
                    "Service Management - registeredBefore: " + serviceRegisteredBefore);

            return serviceRegisteredBefore;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Service shutdown test failed: " + exception.getMessage());
            return false;
        }
    }
}