package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.service.LegacyEntityDataService;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for optimistic locking mechanism in LegacyEntityDataService.
 *
 * <p>This test suite validates the version-based optimistic locking functionality
 * including version conflict detection, change merging, and concurrent update handling.
 * Tests ensure data consistency and conflict resolution in distributed environments.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 11:00
 */
@ModuleTest(
        testName = "optimistic-locking-integration-test",
        description = "Integration tests for optimistic locking in LegacyEntityDataService",
        tags = {"entity", "optimistic-locking", "concurrency", "version-control"},
        priority = 1,
        timeout = 45000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class OptimisticLockingIntegrationTest {

    /**
     * Test version conflict detection when saving entities with different versions.
     */
    public static boolean testVersionConflictDetection() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("version-conflict");

            UUID entityUuid = UUID.randomUUID();

            // Create original entity
            LegacyEntityData originalEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            originalEntity.addAttribute("value", "original");
            service.saveEntity(originalEntity);

            // Wait for persistence
            Thread.sleep(1100);

            // Simulate concurrent modifications with different versions
            LegacyEntityData entity1 = service.getEntityData(entityUuid);
            LegacyEntityData entity2 = service.getEntityData(entityUuid);

            // Individual saves required for version conflict testing
            // Modify entity1 and save (this will increment version)
            entity1.addAttribute("field1", "modified1");
            service.saveEntity(entity1); // Must be individual to test version increment
            Thread.sleep(1100);

            // Get current version after first save
            LegacyEntityData currentEntity = service.getEntityData(entityUuid);
            long currentVersion = currentEntity.getVersion();

            // Try to save entity2 with old version (should trigger merge)
            entity2.addAttribute("field2", "modified2");
            service.saveEntity(entity2); // Must be individual to test version conflict resolution
            Thread.sleep(1100);

            // Verify final state contains both changes
            LegacyEntityData finalEntity = service.getEntityData(entityUuid);
            boolean hasField1 = "modified1".equals(finalEntity.getAttribute("field1"));
            boolean hasField2 = "modified2".equals(finalEntity.getAttribute("field2"));
            boolean versionIncreased = finalEntity.getVersion() > currentVersion;

            TestLogger.logValidation("player", "VersionConflictDetection",
                    hasField1 && hasField2 && versionIncreased,
                    "Conflict Detection - field1: " + hasField1 + ", field2: " + hasField2 +
                            ", versionIncreased: " + versionIncreased + " (from " + currentVersion + " to " + finalEntity.getVersion() + ")");

            return hasField1 && hasField2 && versionIncreased;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Version conflict detection test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test lost update prevention through optimistic locking.
     */
    public static boolean testLostUpdatePrevention() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("lost-update");

            UUID entityUuid = UUID.randomUUID();

            // Create and save initial entity
            LegacyEntityData initialEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            initialEntity.addAttribute("counter", "0");
            service.saveEntity(initialEntity);
            Thread.sleep(1100);

            // Simulate two concurrent clients reading the same entity
            LegacyEntityData client1Entity = service.getEntityData(entityUuid);
            LegacyEntityData client2Entity = service.getEntityData(entityUuid);

            // Both clients modify the counter
            client1Entity.addAttribute("counter", "1");
            client1Entity.addAttribute("client1", "update");

            client2Entity.addAttribute("counter", "2");
            client2Entity.addAttribute("client2", "update");

            // Save client1 changes first
            service.saveEntity(client1Entity);
            Thread.sleep(1100);

            // Save client2 changes (should merge with client1 changes)
            service.saveEntity(client2Entity);
            Thread.sleep(1100);

            // Verify both client changes are preserved
            LegacyEntityData finalEntity = service.getEntityData(entityUuid);
            boolean hasClient1Update = "update".equals(finalEntity.getAttribute("client1"));
            boolean hasClient2Update = "update".equals(finalEntity.getAttribute("client2"));
            boolean hasLatestCounter = "2".equals(finalEntity.getAttribute("counter"));

            TestLogger.logValidation("player", "LostUpdatePrevention",
                    hasClient1Update && hasClient2Update && hasLatestCounter,
                    "Lost Update Prevention - client1: " + hasClient1Update + ", client2: " + hasClient2Update +
                            ", latestCounter: " + hasLatestCounter);

            return hasClient1Update && hasClient2Update && hasLatestCounter;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Lost update prevention test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test concurrent modifications with multiple threads using unified batch strategy.
     */
    public static boolean testConcurrentModifications() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("concurrent-mods");

            UUID entityUuid = UUID.randomUUID();

            // Create initial entity
            LegacyEntityData initialEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            initialEntity.addAttribute("initial", "value");
            service.saveEntity(initialEntity);
            Thread.sleep(1100); // Wait for RStream publishing and entity save

            int threadCount = 5;
            CountDownLatch preparationLatch = new CountDownLatch(threadCount);
            List<CompletableFuture<LegacyEntityData>> preparationFutures = new ArrayList<>();

            // Phase 1: Concurrent entity preparation (no saving yet)
            for (int i = 0; i < threadCount; i++) {
                final int threadId = i;
                CompletableFuture<LegacyEntityData> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Staggered start to reduce contention during preparation
                        Thread.sleep(threadId * 50);

                        LegacyEntityData entity = service.getEntityData(entityUuid);
                        entity.addAttribute("thread" + threadId, "value" + threadId);

                        preparationLatch.countDown();
                        return entity;
                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Thread " + threadId + " preparation failed: " + exception.getMessage());
                        preparationLatch.countDown();
                        return null;
                    }
                });
                preparationFutures.add(future);
            }

            // Wait for all preparation to complete
            boolean allPrepared = preparationLatch.await(10, TimeUnit.SECONDS);
            if (!allPrepared) {
                TestLogger.logFailure("player", "Not all threads completed preparation within timeout");
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
                    TestLogger.logFailure("player", "Failed to collect prepared entity: " + exception.getMessage());
                }
            }

            // Phase 3: Single unified batch save operation
            if (!preparedEntities.isEmpty()) {
                service.saveEntities(preparedEntities);
            }

            // Wait for async persistence
            Thread.sleep(1100);

            // Verify all thread modifications are present
            LegacyEntityData finalEntity = service.getEntityData(entityUuid);
            int foundThreadUpdates = 0;
            for (int i = 0; i < threadCount; i++) {
                if (("value" + i).equals(finalEntity.getAttribute("thread" + i))) {
                    foundThreadUpdates++;
                }
            }

            boolean allEntitiesPrepared = preparedEntities.size() == threadCount;
            boolean allUpdatesPresent = foundThreadUpdates == threadCount;

            TestLogger.logValidation("player", "ConcurrentModifications",
                    allEntitiesPrepared && allUpdatesPresent,
                    "Concurrent Modifications - entitiesPrepared: " + preparedEntities.size() + "/" + threadCount +
                            ", updatesPresent: " + foundThreadUpdates + "/" + threadCount);

            return allEntitiesPrepared && allUpdatesPresent;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent modifications test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test relationship merge during version conflicts using unified batch strategy.
     */
    public static boolean testRelationshipMergeConflicts() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("relationship-merge");

            UUID entityUuid = UUID.randomUUID();
            UUID relatedEntity1 = UUID.randomUUID();
            UUID relatedEntity2 = UUID.randomUUID();

            // Create initial entity
            LegacyEntityData initialEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            service.saveEntity(initialEntity);
            Thread.sleep(1100); // Wait for RStream publishing and entity save

            // Phase 1: Prepare both relationship modifications concurrently
            LegacyEntityData entity1 = service.getEntityData(entityUuid);
            LegacyEntityData entity2 = service.getEntityData(entityUuid);

            // Entity1 adds relationship to relatedEntity1
            entity1.addRelationship("friends", relatedEntity1);

            // Entity2 adds relationship to relatedEntity2 (should merge)
            entity2.addRelationship("friends", relatedEntity2);

            // Phase 2: Single unified batch save operation
            List<LegacyEntityData> entitiesToSave = new ArrayList<>();
            entitiesToSave.add(entity1);
            entitiesToSave.add(entity2);

            service.saveEntities(entitiesToSave);
            Thread.sleep(1100); // Wait for RStream publishing and entity save

            // Verify both relationships are present
            LegacyEntityData finalEntity = service.getEntityData(entityUuid);
            boolean hasRelation1 = finalEntity.hasRelationship("friends", relatedEntity1);
            boolean hasRelation2 = finalEntity.hasRelationship("friends", relatedEntity2);
            int relationshipCount = finalEntity.countRelationships("friends");

            TestLogger.logValidation("player", "RelationshipMergeConflicts",
                    hasRelation1 && hasRelation2 && relationshipCount == 2,
                    "Relationship Merge - relation1: " + hasRelation1 + ", relation2: " + hasRelation2 +
                            ", count: " + relationshipCount);

            return hasRelation1 && hasRelation2 && relationshipCount == 2;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Relationship merge conflicts test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test timestamp-based conflict resolution when versions are equal.
     */
    public static boolean testTimestampConflictResolution() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("timestamp-resolution");

            UUID entityUuid = UUID.randomUUID();

            // Create initial entity and first entity in sequence
            LegacyEntityData initialEntity = LegacyEntityData.of(entityUuid, "TestEntity");
            initialEntity.addAttribute("initial", "value");
            service.saveEntity(initialEntity);
            Thread.sleep(1100);

            // Create entity with initial attribute
            LegacyEntityData entity1 = LegacyEntityData.of(entityUuid, "TestEntity");
            entity1.addAttribute("source", "first");
            entity1.addAttribute("initial", "value");
            service.saveEntity(entity1);
            Thread.sleep(1100);

            // Get the saved entity to establish it in cache
            LegacyEntityData cachedEntity = service.getEntityData(entityUuid);
            long baseVersion = cachedEntity.getVersion();

            // Create entity2 with same version but different timestamp (simulate concurrent update)
            LegacyEntityData entity2 = LegacyEntityData.of(entityUuid, "TestEntity");
            entity2.addAttribute("source", "second");
            entity2.addAttribute("initial", "value");
            entity2.setVersion(baseVersion);
            entity2.updateLastModifiedTime();
            long newerTimestamp = entity2.getLastModifiedTime();

            // The key test: when entity2 is saved, it should replace the cached entity
            // because entity2 has a newer timestamp and same version
            service.saveEntity(entity2);
            Thread.sleep(1100);

            LegacyEntityData finalEntity = service.getEntityData(entityUuid);
            boolean newerWon = "second".equals(finalEntity.getAttribute("source"));
            boolean timestampDifferent = newerTimestamp > cachedEntity.getLastModifiedTime();

            TestLogger.logValidation("player", "TimestampConflictResolution",
                    newerWon && timestampDifferent,
                    "Timestamp Resolution - newerWon: " + newerWon + ", timestampDiff: " + timestampDifferent +
                            " (" + cachedEntity.getLastModifiedTime() + " vs " + newerTimestamp + ")");

            return newerWon && timestampDifferent;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Timestamp conflict resolution test failed: " + exception.getMessage());
            return false;
        }
    }
}