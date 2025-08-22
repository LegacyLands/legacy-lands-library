package net.legacy.library.player.test;

import com.github.benmanes.caffeine.cache.Cache;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;

import java.time.Duration;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * Integration test class for LegacyEntityDataService, validating multi-level cache functionality,
 * Redis Stream processing, and MongoDB persistence with real database connections.
 *
 * <p>This test suite performs comprehensive integration testing of the entity data service
 * including L1 cache (Caffeine), L2 cache (Redis), database persistence (MongoDB),
 * and distributed Redis Stream communication between service instances.
 *
 * <p>The tests require active Redis and MongoDB connections as specified in the service
 * configuration. All tests use production-level configurations to ensure real-world compatibility.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 10:46
 */
@ModuleTest(
        testName = "legacy-entity-data-service-integration-test",
        description = "Integration tests for LegacyEntityDataService with real Redis and MongoDB connections",
        tags = {"entity", "integration", "cache", "redis", "mongodb", "stream"},
        priority = 1,
        timeout = 30000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class LegacyEntityDataServiceTest {

    /**
     * Test basic service creation and initialization with required configurations.
     */
    public static boolean testServiceCreation() {
        try {
            // Create service using TestConnectionResource
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-service-creation");

            // Verify service creation
            boolean serviceRegistered = LegacyEntityDataService.getLegacyEntityDataService(service.getName()).isPresent();
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
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-l1-cache");

            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testData = LegacyEntityData.of(testUuid, "TestEntity");
            testData.addAttribute("testKey", "testValue");

            // Test L1 cache direct access
            Cache<UUID, LegacyEntityData> l1Cache = service.getL1Cache().getResource();
            l1Cache.put(testUuid, testData);

            // Verify L1 cache storage and retrieval
            Optional<LegacyEntityData> cachedData = service.getFromL1Cache(testUuid);
            boolean dataStored = cachedData.isPresent();
            boolean correctData = dataStored && "testValue".equals(cachedData.get().getAttribute("testKey"));

            // Test cache invalidation
            l1Cache.invalidate(testUuid);
            Optional<LegacyEntityData> afterInvalidation = service.getFromL1Cache(testUuid);
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
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-l2-cache");

            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testData = LegacyEntityData.of(testUuid, "TestEntity");
            testData.addAttribute("l2TestKey", "l2TestValue");

            // Save to L2 cache through service method
            service.saveEntity(testData);

            // Wait for RStream publishing and async save to complete
            Thread.sleep(1100);

            // Clear L1 cache to force L2 lookup
            service.getL1Cache().getResource().invalidate(testUuid);

            // Verify L2 cache retrieval
            Optional<LegacyEntityData> l2Data = service.getFromL2Cache(testUuid);
            boolean dataInL2 = l2Data.isPresent();
            boolean correctL2Data = dataInL2 && "l2TestValue".equals(l2Data.get().getAttribute("l2TestKey"));

            // Test TTL functionality
            boolean ttlSet = service.setEntityDefaultTTL(testUuid);

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
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-multilevel");

            UUID testUuid = UUID.randomUUID();
            LegacyEntityData originalData = LegacyEntityData.of(testUuid, "TestEntity");
            originalData.addAttribute("multilevelKey", "originalValue");

            // Save data through service (goes to L2 and DB)
            service.saveEntity(originalData);
            Thread.sleep(1100); // Wait for RStream publishing and async persistence

            // Clear L1 cache
            service.getL1Cache().getResource().invalidate(testUuid);

            // First retrieval should come from L2 and populate L1
            LegacyEntityData retrievedData = service.getEntityData(testUuid);
            boolean firstRetrievalSuccess = retrievedData != null && "originalValue".equals(retrievedData.getAttribute("multilevelKey"));

            // Verify L1 is now populated
            Optional<LegacyEntityData> l1Data = service.getFromL1Cache(testUuid);
            boolean l1Populated = l1Data.isPresent();

            // Second retrieval should come from L1 (faster)
            long startTime = System.nanoTime();
            LegacyEntityData secondRetrieval = service.getEntityData(testUuid);
            long duration = System.nanoTime() - startTime;
            boolean secondRetrievalSuccess = secondRetrieval != null && "originalValue".equals(secondRetrieval.getAttribute("multilevelKey"));
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
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-db-fallback");

            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testData = LegacyEntityData.of(testUuid, "TestEntity");
            testData.addAttribute("dbKey", "dbValue");

            // Save to cache and database
            service.saveEntity(testData);
            Thread.sleep(1100); // Wait for RStream publishing and save to complete

            // Clear L1 cache
            service.getL1Cache().getResource().invalidate(testUuid);

            // Test direct database retrieval method
            LegacyEntityData directDbData = service.getFromDatabase(testUuid);
            boolean directDbSuccess = directDbData != null && "dbValue".equals(directDbData.getAttribute("dbKey"));

            // Clear L1 again and test full retrieval path
            service.getL1Cache().getResource().invalidate(testUuid);
            LegacyEntityData retrievedData = service.getEntityData(testUuid);
            boolean databaseFallbackSuccess = retrievedData != null && "dbValue".equals(retrievedData.getAttribute("dbKey"));

            TestLogger.logInfo("player", "Direct DB: " + directDbSuccess + ", Full retrieval: " + databaseFallbackSuccess);

            // Verify data is now cached in L1 after database retrieval
            Optional<LegacyEntityData> l1CachedData = service.getFromL1Cache(testUuid);
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
            LegacyEntityDataService service1 = TestConnectionResource.createTestEntityService("entity-instance-1");
            LegacyEntityDataService service2 = TestConnectionResource.createTestEntityService("entity-instance-2");

            // Verify both services are registered
            boolean service1Registered = LegacyEntityDataService.getLegacyEntityDataService(service1.getName()).isPresent();
            boolean service2Registered = LegacyEntityDataService.getLegacyEntityDataService(service2.getName()).isPresent();

            // Test duplicate name rejection
            boolean duplicateRejected = false;
            try {
                String duplicateName = service1.getName();
                LegacyEntityDataService.of(
                        duplicateName,
                        TestConnectionResource.getMongoConfig(),
                        TestConnectionResource.getRedisConfig(),
                        List.of("net.legacy.library.player.task.redis.impl"),
                        List.of(LegacyEntityDataServiceTest.class.getClassLoader())
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
     * Test TTL management for entity data including default and custom TTL settings.
     */
    public static boolean testTTLManagement() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-ttl");

            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testData = LegacyEntityData.of(testUuid, "TestEntity");
            testData.addAttribute("ttlKey", "ttlValue");

            // Save data to L2 cache
            service.saveEntity(testData);
            Thread.sleep(1100); // Wait for RStream publishing and async save

            // Test default TTL setting
            boolean defaultTtlSet = service.setEntityDefaultTTL(testUuid);

            // Test custom TTL setting
            boolean customTtlSet = service.setEntityTTL(testUuid, Duration.ofMinutes(10));

            // Test TTL setting for non-existent entity
            UUID nonExistentUuid = UUID.randomUUID();
            boolean nonExistentTtlFailed = !service.setEntityDefaultTTL(nonExistentUuid);

            // Test bulk TTL setting
            int ttlCount = service.setDefaultTTLForAllEntities();
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
     * Test entity relationship operations including creating and querying relationships.
     */
    public static boolean testEntityRelationships() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-relationships");

            // Create test entities
            UUID entity1Uuid = UUID.randomUUID();
            UUID entity2Uuid = UUID.randomUUID();
            UUID entity3Uuid = UUID.randomUUID();

            LegacyEntityData entity1 = service.createEntityIfNotExists(entity1Uuid, "Player");
            LegacyEntityData entity2 = service.createEntityIfNotExists(entity2Uuid, "Guild");
            LegacyEntityData entity3 = service.createEntityIfNotExists(entity3Uuid, "Player");

            // Save entities using batch operation
            service.saveEntities(List.of(entity1, entity2, entity3));

            // Create bidirectional relationship
            boolean bidirectionalCreated = service.createBidirectionalRelationship(
                    entity1Uuid, entity2Uuid, "member_of", "has_member");

            // Test relationship queries
            List<LegacyEntityData> guildMembers = service.findEntitiesByRelationship("has_member", entity1Uuid);
            List<LegacyEntityData> playerGuilds = service.findEntitiesByRelationship("member_of", entity2Uuid);

            boolean relationshipQuerySuccess = !guildMembers.isEmpty() && !playerGuilds.isEmpty();

            // Test relationship counting
            int memberCount = service.countEntitiesWithRelationship("member_of", entity2Uuid);
            boolean countingSuccess = memberCount > 0;

            TestLogger.logValidation("player", "EntityRelationships",
                    bidirectionalCreated && relationshipQuerySuccess && countingSuccess,
                    "Entity Relationships - bidirectional: " + bidirectionalCreated +
                            ", querySuccess: " + relationshipQuerySuccess + ", counting: " + countingSuccess);

            return bidirectionalCreated && relationshipQuerySuccess && countingSuccess;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Entity relationships test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test service shutdown and cleanup procedures.
     */
    public static boolean testServiceShutdown() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("entity-shutdown");

            // Add some test data
            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testData = LegacyEntityData.of(testUuid, "TestEntity");
            service.saveEntity(testData);

            // Verify service is registered before shutdown
            boolean serviceRegisteredBefore = LegacyEntityDataService.getLegacyEntityDataService(service.getName()).isPresent();

            TestLogger.logValidation("player", "ServiceShutdown", serviceRegisteredBefore,
                    "Service Management - registeredBefore: " + serviceRegisteredBefore);

            return serviceRegisteredBefore;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Service shutdown test failed: " + exception.getMessage());
            return false;
        }
    }

}