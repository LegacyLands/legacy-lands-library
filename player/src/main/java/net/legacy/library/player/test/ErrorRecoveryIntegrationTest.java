package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Integration tests for error recovery mechanisms in LegacyEntityDataService.
 *
 * <p>This test suite validates error handling and recovery capabilities including
 * connection failures, resource exhaustion scenarios, and partial failure recovery.
 * Tests ensure system resilience and graceful degradation under adverse conditions.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 11:15
 */
@ModuleTest(
        testName = "error-recovery-integration-test",
        description = "Integration tests for error recovery in LegacyEntityDataService",
        tags = {"entity", "error-recovery", "resilience", "fault-tolerance"},
        priority = 2,
        timeout = 60000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class ErrorRecoveryIntegrationTest {

    /**
     * Test graceful handling of Redis connection issues.
     */
    public static boolean testRedisConnectionFailureHandling() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("redis-failure");

            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData testEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            testEntity.addAttribute("test", "value");

            // Save entity (should work normally)
            service.saveEntity(testEntity);
            Thread.sleep(1100); // Wait for RStream publishing

            // Verify entity exists in L1 cache even if Redis has issues
            LegacyEntityData fromL1 = service.getFromL1Cache(entityUuid).orElse(null);
            boolean l1CacheWorks = fromL1 != null && "value".equals(fromL1.getAttribute("test"));

            // Test TTL operations with potential Redis issues
            boolean ttlOperationSucceeded = false;
            try {
                boolean ttlResult = service.setEntityDefaultTTL(entityUuid);
                ttlOperationSucceeded = ttlResult;
                TestLogger.logInfo("player", "TTL operation result: %s", ttlResult);
            } catch (Exception exception) {
                // TTL operation may fail, but should not crash the service
                TestLogger.logInfo("player", "TTL operation failed gracefully: %s", exception.getMessage());
                // ttlOperationSucceeded remains false, which is acceptable
            }

            // Service should continue to function with L1 cache
            LegacyEntityData retrievedEntity = service.getEntityData(entityUuid);
            boolean serviceStillFunctional = retrievedEntity != null && "value".equals(retrievedEntity.getAttribute("test"));

            TestLogger.logValidation("player", "RedisConnectionFailureHandling",
                    l1CacheWorks && serviceStillFunctional,
                    "Redis Failure Handling - l1Cache: " + l1CacheWorks + ", ttlSucceeded: " + ttlOperationSucceeded +
                            ", serviceFunctional: " + serviceStillFunctional);

            return l1CacheWorks && serviceStillFunctional;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Redis connection failure handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test handling of MongoDB connection issues with fallback behavior.
     */
    public static boolean testMongoDBConnectionFailureHandling() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("mongodb-failure");

            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData testEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            testEntity.addAttribute("test", "mongo_value");

            // Save to L1 cache first
            service.getL1Cache().getResource().put(entityUuid, testEntity);

            // Attempt database operations that may fail
            boolean databaseQuerySucceeded = false;
            try {
                LegacyEntityData fromDb = service.getFromDatabase(entityUuid);
                // Operation may return null if MongoDB is unavailable
                databaseQuerySucceeded = (fromDb != null);
                TestLogger.logInfo("player", "Database query result: %s", (fromDb != null ? "success" : "null"));
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Database operation failed gracefully: %s", exception.getMessage());
                // databaseQuerySucceeded remains false, which is acceptable for failure testing
            }

            // Service should still function with cache
            LegacyEntityData fromCache = service.getEntityData(entityUuid);
            boolean cacheStillWorks = fromCache != null && "mongo_value".equals(fromCache.getAttribute("test"));

            // Test save operation with potential MongoDB issues
            testEntity.addAttribute("additional", "cache_value");
            service.saveEntity(testEntity);
            Thread.sleep(1100); // Wait for RStream publishing

            // Verify L1 cache update succeeded
            LegacyEntityData updatedEntity = service.getFromL1Cache(entityUuid).orElse(null);
            boolean updateSucceeded = updatedEntity != null && "cache_value".equals(updatedEntity.getAttribute("additional"));

            TestLogger.logValidation("player", "MongoDBConnectionFailureHandling",
                    cacheStillWorks && updateSucceeded,
                    "MongoDB Failure Handling - dbQuerySucceeded: " + databaseQuerySucceeded +
                            ", cacheWorks: " + cacheStillWorks + ", updateSucceeded: " + updateSucceeded);

            return cacheStillWorks && updateSucceeded;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "MongoDB connection failure handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test resource exhaustion scenarios and recovery.
     */
    public static boolean testResourceExhaustionRecovery() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("resource-exhaustion");

            // Create many entities to potentially exhaust resources
            List<UUID> entityUuids = new ArrayList<>();
            int entityCount = 50; // Moderate number to avoid actual exhaustion in tests

            // Create entities for batch save to test resource exhaustion
            List<LegacyEntityData> entities = new ArrayList<>();
            for (int i = 0; i < entityCount; i++) {
                UUID entityUuid = UUID.randomUUID();
                entityUuids.add(entityUuid);

                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                entity.addAttribute("index", String.valueOf(i));
                entity.addAttribute("data", "bulk_data_" + i);
                entities.add(entity);
            }

            // Attempt batch save to test resource exhaustion
            try {
                service.saveEntities(entities);
                Thread.sleep(1100); // Wait for RStream publishing and batch save
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Batch save encountered expected exception: %s", exception.getMessage());
            }

            // Verify service recovery and functionality
            int successfulRetrievals = 0;
            for (int i = 0; i < 10; i++) {
                UUID testUuid = entityUuids.get(i);
                try {
                    LegacyEntityData retrieved = service.getEntityData(testUuid);
                    if (retrieved != null && String.valueOf(i).equals(retrieved.getAttribute("index"))) {
                        successfulRetrievals++;
                    }
                } catch (Exception exception) {
                    TestLogger.logInfo("player", "Retrieval %d failed: %s", i, exception.getMessage());
                }
            }

            // Test new entity creation after potential exhaustion
            UUID newEntityUuid = UUID.randomUUID();
            LegacyEntityData newEntity = LegacyEntityData.of(newEntityUuid, "RecoveryTest");
            newEntity.addAttribute("recovery", "success");

            boolean newEntitySaved = false;
            try {
                service.saveEntity(newEntity);
                Thread.sleep(1100); // Wait for RStream publishing
                LegacyEntityData retrieved = service.getEntityData(newEntityUuid);
                newEntitySaved = retrieved != null && "success".equals(retrieved.getAttribute("recovery"));
            } catch (Exception exception) {
                TestLogger.logInfo("player", "New entity save failed: %s", exception.getMessage());
            }

            boolean recoverySuccessful = successfulRetrievals >= 5 && newEntitySaved;

            TestLogger.logValidation("player", "ResourceExhaustionRecovery", recoverySuccessful,
                    "Resource Exhaustion Recovery - retrievals: " + successfulRetrievals + "/10, newEntity: " + newEntitySaved);

            return recoverySuccessful;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Resource exhaustion recovery test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test partial failure scenarios during batch operations.
     */
    public static boolean testPartialFailureRecovery() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("partial-failure");

            // Create a mix of valid and potentially problematic entities
            List<LegacyEntityData> entities = new ArrayList<>();

            // Valid entities
            for (int i = 0; i < 5; i++) {
                UUID entityUuid = UUID.randomUUID();
                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "ValidEntity");
                entity.addAttribute("index", String.valueOf(i));
                entity.addAttribute("type", "valid");
                entities.add(entity);
            }

            // Potentially problematic entities (large data, special characters, etc.)
            for (int i = 0; i < 3; i++) {
                UUID entityUuid = UUID.randomUUID();
                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "ProblematicEntity");
                entity.addAttribute("large_data", "x".repeat(1000)); // Large data
                entity.addAttribute("special_chars", "测试数据@#$%^&*()");
                entity.addAttribute("index", String.valueOf(i + 100));
                entities.add(entity);
            }

            // Attempt unified batch save
            int successfulSaves = 0;
            try {
                service.saveEntities(entities);
                successfulSaves = entities.size(); // All should succeed in batch
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Batch save failed: %s", exception.getMessage());
                // Fallback to individual saves for error analysis
                for (LegacyEntityData entity : entities) {
                    try {
                        service.saveEntity(entity);
                        successfulSaves++;
                    } catch (Exception individualException) {
                        TestLogger.logInfo("player", "Individual entity save failed: %s", individualException.getMessage());
                    }
                }
            }

            // Wait for async persistence (no RStream)
            Thread.sleep(300);

            // Verify successful entities are retrievable
            int successfulRetrievals = 0;
            for (LegacyEntityData entity : entities) {
                try {
                    LegacyEntityData retrieved = service.getEntityData(entity.getUuid());
                    if (retrieved != null && retrieved.getEntityType().equals(entity.getEntityType())) {
                        successfulRetrievals++;
                    }
                } catch (Exception exception) {
                    TestLogger.logInfo("player", "Entity retrieval failed: %s", exception.getMessage());
                }
            }

            // Test service functionality after partial failures
            UUID testUuid = UUID.randomUUID();
            LegacyEntityData testEntity = LegacyEntityData.of(testUuid, "PostFailureTest");
            testEntity.addAttribute("status", "working");

            boolean postFailureFunctionality = false;
            try {
                service.saveEntity(testEntity);
                Thread.sleep(1100); // Wait for RStream publishing and entity save
                LegacyEntityData retrieved = service.getEntityData(testUuid);
                postFailureFunctionality = retrieved != null && "working".equals(retrieved.getAttribute("status"));
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Post-failure test failed: %s", exception.getMessage());
            }

            boolean partialRecoverySuccessful = successfulSaves >= 6 && successfulRetrievals >= 6 && postFailureFunctionality;

            TestLogger.logValidation("player", "PartialFailureRecovery", partialRecoverySuccessful,
                    "Partial Failure Recovery - saves: " + successfulSaves + "/8, retrievals: " +
                            successfulRetrievals + "/8, postFailure: " + postFailureFunctionality);

            return partialRecoverySuccessful;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Partial failure recovery test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test service recovery after temporary outages.
     */
    public static boolean testServiceRecoveryAfterOutage() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("service-recovery");

            UUID entityUuid = UUID.randomUUID();

            // Pre-outage: Normal operation
            LegacyEntityData preOutageEntity = LegacyEntityData.of(entityUuid, "PreOutage");
            preOutageEntity.addAttribute("phase", "before");
            service.saveEntity(preOutageEntity);
            Thread.sleep(1100); // Wait for RStream publishing and entity save

            // Simulate outage conditions by rapid operations
            List<Exception> outageExceptions = new ArrayList<>();
            for (int i = 0; i < 10; i++) {
                try {
                    // Rapid fire operations that might cause issues
                    service.setEntityTTL(UUID.randomUUID(), Duration.ofSeconds(1));
                    service.getFromDatabase(UUID.randomUUID());
                } catch (Exception exception) {
                    outageExceptions.add(exception);
                }
            }

            // Post-outage: Test service recovery
            Thread.sleep(300); // Allow system to stabilize (no RStream)

            // Verify pre-outage data is still accessible
            LegacyEntityData retrieved = service.getEntityData(entityUuid);
            boolean preOutageDataIntact = retrieved != null && "before".equals(retrieved.getAttribute("phase"));

            // Test new operations work
            if (retrieved != null) {
                retrieved.addAttribute("phase", "after");
                retrieved.addAttribute("recovery", "successful");
                service.saveEntity(retrieved);
                Thread.sleep(1100); // Wait for RStream publishing and entity save
            }

            LegacyEntityData postRecovery = service.getEntityData(entityUuid);
            boolean postRecoveryOperational = postRecovery != null &&
                    "after".equals(postRecovery.getAttribute("phase")) &&
                    "successful".equals(postRecovery.getAttribute("recovery"));

            // Test new entity creation
            UUID newEntityUuid = UUID.randomUUID();
            LegacyEntityData newEntity = LegacyEntityData.of(newEntityUuid, "PostRecovery");
            newEntity.addAttribute("created", "after_recovery");
            service.saveEntity(newEntity);
            Thread.sleep(1100); // Wait for RStream publishing and entity save

            LegacyEntityData newRetrieved = service.getEntityData(newEntityUuid);
            boolean newEntityCreationWorks = newRetrieved != null && "after_recovery".equals(newRetrieved.getAttribute("created"));

            boolean recoverySuccessful = preOutageDataIntact && postRecoveryOperational && newEntityCreationWorks;

            TestLogger.logValidation("player", "ServiceRecoveryAfterOutage", recoverySuccessful,
                    "Service Recovery - preData: " + preOutageDataIntact + ", postOps: " + postRecoveryOperational +
                            ", newEntity: " + newEntityCreationWorks + ", outageExceptions: " + outageExceptions.size());

            return recoverySuccessful;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Service recovery after outage test failed: %s", exception.getMessage());
            return false;
        }
    }
}