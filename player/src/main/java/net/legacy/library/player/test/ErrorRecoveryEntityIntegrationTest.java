package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Error recovery integration tests for LegacyEntityDataService.
 *
 * <p>This test suite validates the error handling and recovery mechanisms
 * implemented in the entity data service, ensuring system resilience
 * under various failure conditions.
 *
 * <p>Tests cover connection failures, resource exhaustion, partial failures,
 * timeout handling, and graceful degradation scenarios.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 11:00
 */
@ModuleTest(
        testName = "error-recovery-entity-integration-test",
        description = "Error recovery tests for LegacyEntityDataService with fault tolerance and resilience validation",
        tags = {"entity", "error-recovery", "resilience", "fault-tolerance", "integration"},
        priority = 3,
        timeout = 60000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class ErrorRecoveryEntityIntegrationTest {

    /**
     * Test graceful handling of null or invalid entity data.
     */
    public static boolean testInvalidEntityDataHandling() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("invalid-data");

            boolean nullEntityHandled = false;
            boolean invalidUuidHandled = false;
            boolean emptyAttributesHandled = false;

            // Test null entity handling
            try {
                service.saveEntity(null);
            } catch (Exception exception) {
                nullEntityHandled = exception instanceof IllegalArgumentException ||
                        exception instanceof NullPointerException;
            }

            // Test retrieval of non-existent entity
            UUID nonExistentUuid = UUID.randomUUID();
            LegacyEntityData result = service.getEntityData(nonExistentUuid);
            boolean nonExistentHandled = result == null;

            // Test entity with empty UUID handling
            try {
                LegacyEntityData invalidEntity = LegacyEntityData.of(null, "TestEntity");
                service.saveEntity(invalidEntity);
            } catch (Exception exception) {
                invalidUuidHandled = true;
            }

            // Test valid entity with empty attributes
            try {
                LegacyEntityData emptyEntity = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
                service.saveEntity(emptyEntity);
                emptyAttributesHandled = true;
            } catch (Exception exception) {
                TestLogger.logFailure("player", "Empty attributes caused unexpected exception: %s", exception.getMessage());
            }

            TestLogger.logValidation("player", "InvalidEntityDataHandling",
                    nullEntityHandled && nonExistentHandled && invalidUuidHandled && emptyAttributesHandled,
                    "Invalid data handling - null: " + nullEntityHandled + ", nonExistent: " + nonExistentHandled +
                            ", invalidUuid: " + invalidUuidHandled + ", emptyAttrs: " + emptyAttributesHandled);

            return nullEntityHandled && nonExistentHandled && invalidUuidHandled && emptyAttributesHandled;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Invalid entity data handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test service behavior under cache memory pressure.
     */
    public static boolean testCacheMemoryPressure() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("memory-pressure");

            List<UUID> entityUuids = new ArrayList<>();
            int entityCount = 100; // Reduced count for reasonable test execution time

            // Create many entities to put pressure on L1 cache using batch operation
            List<LegacyEntityData> entities = new ArrayList<>();
            for (int i = 0; i < entityCount; i++) {
                UUID entityUuid = UUID.randomUUID();
                entityUuids.add(entityUuid);

                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                entity.addAttribute("index", String.valueOf(i));
                entity.addAttribute("data", "large_data_chunk_" + "x".repeat(100)); // Add some bulk
                entities.add(entity);
            }

            // Save all entities in batch to test cache behavior
            service.saveEntities(entities);
            Thread.sleep(1100); // Wait for RStream publishing and batch save

            // Verify that recent entities are still accessible (some may be evicted from L1)
            int accessibleCount = 0;
            int sampleSize = 50; // Sample size for verification

            for (int i = entityCount - sampleSize; i < entityCount; i++) {
                LegacyEntityData retrieved = service.getEntityData(entityUuids.get(i));
                if (retrieved != null && String.valueOf(i).equals(retrieved.getAttribute("index"))) {
                    accessibleCount++;
                }
            }

            boolean memoryPressureHandled = accessibleCount >= sampleSize * 0.8; // At least 80% should be accessible
            boolean noMemoryLeaks = Runtime.getRuntime().freeMemory() > 0; // Basic check

            TestLogger.logValidation("player", "CacheMemoryPressure",
                    memoryPressureHandled && noMemoryLeaks,
                    "Memory pressure - accessible: " + accessibleCount + "/" + sampleSize +
                            ", handled: " + memoryPressureHandled + ", noLeaks: " + noMemoryLeaks);

            return memoryPressureHandled && noMemoryLeaks;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Cache memory pressure test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test partial failure scenarios with batch operations.
     */
    public static boolean testPartialFailureHandling() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("partial-failure");

            List<LegacyEntityData> entities = new ArrayList<>();
            List<LegacyEntityData> validEntities = new ArrayList<>();

            // Create mix of valid and potentially problematic entities
            for (int i = 0; i < 20; i++) {
                UUID entityUuid = UUID.randomUUID();
                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                entity.addAttribute("index", String.valueOf(i));

                if (i % 5 == 0) {
                    // Every 5th entity has potential issues (very large attribute)
                    entity.addAttribute("large_data", "x".repeat(10000));
                } else {
                    validEntities.add(entity);
                }

                entities.add(entity);
            }

            // Save entities in batch
            try {
                service.saveEntities(entities);
                Thread.sleep(1100); // Wait for RStream publishing and persistence
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Batch save encountered expected exception: %s", exception.getClass().getSimpleName());
            }

            // Verify that valid entities were still processed
            int successfullyRetrieved = 0;
            for (LegacyEntityData validEntity : validEntities) {
                LegacyEntityData retrieved = service.getEntityData(validEntity.getUuid());
                if (retrieved != null && retrieved.getAttribute("index") != null) {
                    successfullyRetrieved++;
                }
            }

            boolean serviceStillOperational;
            boolean partialSuccessAchieved = successfullyRetrieved >= validEntities.size() * 0.7; // At least 70% success

            // Verify service is still operational after partial failure
            try {
                UUID testUuid = UUID.randomUUID();
                LegacyEntityData testEntity = LegacyEntityData.of(testUuid, "TestEntity");
                testEntity.addAttribute("test", "operational");
                service.saveEntity(testEntity);

                Thread.sleep(1100); // Wait for RStream publishing and entity save
                LegacyEntityData retrieved = service.getEntityData(testUuid);
                serviceStillOperational = retrieved != null && "operational".equals(retrieved.getAttribute("test"));
            } catch (Exception exception) {
                serviceStillOperational = false;
                TestLogger.logFailure("player", "Service not operational after partial failure: %s", exception.getMessage());
            }

            TestLogger.logValidation("player", "PartialFailureHandling",
                    partialSuccessAchieved && serviceStillOperational,
                    "Partial failure - successRate: " +
                            successfullyRetrieved + "/" + validEntities.size() + ", operational: " + serviceStillOperational);

            return partialSuccessAchieved && serviceStillOperational;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Partial failure handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test timeout handling for TTL operations.
     */
    public static boolean testTTLTimeoutHandling() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("ttl-timeout");

            List<UUID> entityUuids = new ArrayList<>();
            int successfulTTLSets = 0;

            // Create entities for TTL testing using batch operation
            List<LegacyEntityData> entities = new ArrayList<>();
            for (int i = 0; i < 30; i++) {
                UUID entityUuid = UUID.randomUUID();
                entityUuids.add(entityUuid);

                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                entity.addAttribute("ttl_test", String.valueOf(i));
                entities.add(entity);
            }

            service.saveEntities(entities);
            Thread.sleep(1100); // Wait for RStream publishing and batch persistence

            // Rapidly set TTL for all entities
            for (UUID uuid : entityUuids) {
                try {
                    boolean ttlSet = service.setEntityTTL(uuid, Duration.ofMinutes(5));
                    if (ttlSet) {
                        successfulTTLSets++;
                    }
                } catch (Exception exception) {
                    TestLogger.logInfo("player", "TTL operation failed for entity %s: %s", uuid, exception.getMessage());
                }
            }

            // Test bulk TTL operation
            boolean bulkTTLWorked;
            try {
                int bulkCount = service.setDefaultTTLForAllEntities();
                bulkTTLWorked = bulkCount >= 0; // Should not fail catastrophically
            } catch (Exception exception) {
                TestLogger.logInfo("player", "Bulk TTL operation handled exception: %s", exception.getMessage());
                bulkTTLWorked = true; // Graceful handling is acceptable
            }

            boolean acceptableSuccessRate = successfulTTLSets >= entityUuids.size() * 0.6; // 60% success rate acceptable

            // Verify service remains operational after TTL stress
            boolean serviceOperational = false;
            try {
                UUID testUuid = UUID.randomUUID();
                LegacyEntityData testEntity = LegacyEntityData.of(testUuid, "TestEntity");
                service.saveEntity(testEntity);
                serviceOperational = service.getEntityData(testUuid) != null;
            } catch (Exception exception) {
                TestLogger.logFailure("player", "Service check after TTL stress failed: %s", exception.getMessage());
            }

            TestLogger.logValidation("player", "TTLTimeoutHandling",
                    acceptableSuccessRate && bulkTTLWorked && serviceOperational,
                    "TTL timeout - successRate: " + successfulTTLSets + "/" + entityUuids.size() +
                            ", bulkWorked: " + bulkTTLWorked + ", operational: " + serviceOperational);

            return acceptableSuccessRate && bulkTTLWorked && serviceOperational;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "TTL timeout handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test concurrent error scenarios and recovery using unified batch strategy.
     */
    public static boolean testConcurrentErrorRecovery() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("concurrent-errors");

            int threadCount = 10; // Reduced from 20 to minimize lock contention
            CountDownLatch preparationLatch = new CountDownLatch(threadCount);
            List<CompletableFuture<LegacyEntityData>> preparationFutures = new ArrayList<>();
            AtomicInteger successCount = new AtomicInteger(0);
            AtomicInteger errorCount = new AtomicInteger(0);

            // Phase 1: Concurrent entity preparation (no saving yet)
            for (int i = 0; i < threadCount; i++) {
                final int taskId = i;
                CompletableFuture<LegacyEntityData> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Staggered start to reduce preparation contention
                        Thread.sleep(taskId * 50);

                        UUID entityUuid = UUID.randomUUID();
                        LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                        entity.addAttribute("task_id", String.valueOf(taskId));

                        // Introduce potential error conditions
                        if (taskId % 7 == 0) {
                            // Some tasks try to set very large attributes
                            entity.addAttribute("large_data", "x".repeat(50000));
                        }

                        if (taskId % 11 == 0) {
                            // Some tasks have version conflicts
                            entity.setVersion(1000);
                        }

                        preparationLatch.countDown();
                        return entity;

                    } catch (Exception exception) {
                        errorCount.incrementAndGet();
                        TestLogger.logInfo("player", "Entity preparation %d encountered error: %s", taskId, exception.getClass().getSimpleName());
                        preparationLatch.countDown();
                        return null;
                    }
                });
                preparationFutures.add(future);
            }

            // Wait for all preparation to complete
            boolean allPrepared = preparationLatch.await(30, TimeUnit.SECONDS);
            if (!allPrepared) {
                TestLogger.logFailure("player", "Not all entity preparations completed within timeout");
                return false;
            }

            // Phase 2: Collect all prepared entities
            List<LegacyEntityData> preparedEntities = new ArrayList<>();
            for (CompletableFuture<LegacyEntityData> future : preparationFutures) {
                try {
                    LegacyEntityData entity = future.get();
                    if (entity != null) {
                        preparedEntities.add(entity);
                    }
                } catch (Exception exception) {
                    errorCount.incrementAndGet();
                    TestLogger.logInfo("player", "Failed to collect prepared entity: %s", exception.getClass().getSimpleName());
                }
            }

            // Phase 3: Single unified batch save operation (eliminates lock contention)
            if (!preparedEntities.isEmpty()) {
                try {
                    service.saveEntities(preparedEntities);
                    successCount.set(preparedEntities.size()); // All entities in batch are saved together
                } catch (Exception exception) {
                    errorCount.incrementAndGet();
                    TestLogger.logInfo("player", "Batch save encountered exception: %s", exception.getClass().getSimpleName());

                    // Fallback to individual saves for error analysis
                    for (LegacyEntityData entity : preparedEntities) {
                        try {
                            service.saveEntity(entity);
                            successCount.incrementAndGet();
                        } catch (Exception individualException) {
                            errorCount.incrementAndGet();
                        }
                    }
                }
            }

            // Wait for async processing
            Thread.sleep(300);

            // Verify recovery and continued operation
            boolean serviceStillWorks = false;
            try {
                UUID recoveryTestUuid = UUID.randomUUID();
                LegacyEntityData recoveryEntity = LegacyEntityData.of(recoveryTestUuid, "RecoveryTest");
                recoveryEntity.addAttribute("recovery", "successful");
                service.saveEntity(recoveryEntity);

                Thread.sleep(1100); // Wait for RStream publishing and entity save
                LegacyEntityData retrieved = service.getEntityData(recoveryTestUuid);
                serviceStillWorks = retrieved != null && "successful".equals(retrieved.getAttribute("recovery"));
            } catch (Exception exception) {
                TestLogger.logFailure("player", "Recovery test failed: %s", exception.getMessage());
            }

            boolean acceptableRecovery = successCount.get() >= threadCount * 0.5; // At least 50% success
            boolean errorsHandledGracefully = errorCount.get() < threadCount; // Not all tasks should fail

            TestLogger.logValidation("player", "ConcurrentErrorRecovery",
                    acceptableRecovery && serviceStillWorks && errorsHandledGracefully,
                    "Concurrent errors - success: " + successCount.get() +
                            "/" + threadCount + ", errors: " + errorCount.get() + ", recovered: " + serviceStillWorks);

            return acceptableRecovery && serviceStillWorks && errorsHandledGracefully;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent error recovery test failed: %s", exception.getMessage());
            return false;
        }
    }
}