package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Comprehensive performance and QPS stress tests for player and entity data services.
 *
 * <p>This test suite measures throughput, latency, and system behavior under high load
 * using optimized patterns:
 * - Player data: Task-based batch synchronization (L1ToL2PlayerDataSyncTask)
 * - Entity data: Batch operations (saveEntities) + task synchronization (L1ToL2EntityDataSyncTask)
 * - Detailed QPS measurements and performance metrics
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 13:10
 */
@ModuleTest(
        testName = "performance-stress-test",
        description = "QPS stress tests for player and entity services with detailed performance metrics",
        tags = {"performance", "stress", "qps", "benchmark", "load-testing"},
        priority = 5,
        timeout = 120000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class PerformanceStressTest {

    private static final TestTimer globalTimer = new TestTimer();

    /**
     * Test player data service QPS under high load using task-based optimization.
     */
    public static boolean testPlayerDataServiceQPS() {
        try {
            LegacyPlayerDataService service = TestConnectionResource.createTestService("qps-player");
            TestTimer timer = new TestTimer();

            // Test parameters
            int totalPlayers = 2000;
            int concurrentThreads = 8;

            // Phase 1: Concurrent player data preparation (no saving yet)
            AtomicInteger preparedPlayers = new AtomicInteger(0);
            CountDownLatch preparationLatch = new CountDownLatch(concurrentThreads);

            List<CompletableFuture<List<LegacyPlayerData>>> preparationFutures = new ArrayList<>();

            for (int threadId = 0; threadId < concurrentThreads; threadId++) {
                final int startIndex = threadId * (totalPlayers / concurrentThreads);
                final int endIndex = (threadId + 1) * (totalPlayers / concurrentThreads);

                CompletableFuture<List<LegacyPlayerData>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        long threadStartTime = System.nanoTime();

                        // Create batch of players for this thread (preparation only)
                        List<LegacyPlayerData> playerBatch = new ArrayList<>();
                        for (int i = startIndex; i < endIndex; i++) {
                            UUID playerUuid = UUID.randomUUID();
                            LegacyPlayerData player = LegacyPlayerData.of(playerUuid);

                            // Add realistic data
                            player.addData("level", String.valueOf(50 + (i % 100)));
                            player.addData("experience", String.valueOf(i * 1000L));
                            player.addData("playtime", String.valueOf(i * 3600L));
                            player.addData("last_login", String.valueOf(System.currentTimeMillis()));
                            player.addData("server", "stress-test-" + (i % 5));

                            playerBatch.add(player);
                        }

                        preparedPlayers.addAndGet(playerBatch.size());

                        long threadEndTime = System.nanoTime();
                        TestLogger.logInfo("player", "Thread %s prepared %d players in %dms",
                                Thread.currentThread().getName(), playerBatch.size(),
                                Duration.ofNanos(threadEndTime - threadStartTime).toMillis());

                        return playerBatch;

                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Thread preparation failed: %s", exception.getMessage());
                        return new ArrayList<>();
                    } finally {
                        preparationLatch.countDown();
                    }
                });

                preparationFutures.add(future);
            }

            // Wait for all preparation threads to complete
            boolean allPrepared = preparationLatch.await(60, TimeUnit.SECONDS);
            if (!allPrepared) {
                TestLogger.logFailure("player", "Player preparation timed out");
                return false;
            }

            // Collect all prepared player data
            List<LegacyPlayerData> allPlayers = new ArrayList<>();
            for (CompletableFuture<List<LegacyPlayerData>> future : preparationFutures) {
                try {
                    allPlayers.addAll(future.get());
                } catch (Exception exception) {
                    TestLogger.logFailure("player", "Failed to collect prepared data: %s", exception.getMessage());
                }
            }

            // Phase 2: Single unified batch save operation - START TIMER HERE
            TestLogger.logInfo("player", "Starting unified batch save of %d players", allPlayers.size());
            timer.startTimer("player-qps-test");
            long unifiedSaveStart = System.nanoTime();
            service.saveLegacyPlayersData(allPlayers);
            long unifiedSaveEnd = System.nanoTime();
            long unifiedSaveDuration = unifiedSaveEnd - unifiedSaveStart;
            int savedPlayers = allPlayers.size();

            // Phase 3: Batch synchronization using L1ToL2PlayerDataSyncTask
            TestLogger.logInfo("player", "Starting L1 to L2 sync task for %d players", savedPlayers);
            long syncStartTime = System.nanoTime();

            L1ToL2PlayerDataSyncTask syncTask = L1ToL2PlayerDataSyncTask.of(service);
            CompletableFuture<?> syncFuture = syncTask.start();
            syncFuture.get(30, TimeUnit.SECONDS);

            long syncEndTime = System.nanoTime();
            long syncDurationMs = Duration.ofNanos(syncEndTime - syncStartTime).toMillis();

            timer.stopTimer("player-qps-test");
            long totalDurationMs = timer.getTimerResult("player-qps-test").getDuration();

            // Phase 4: QPS calculations and validation
            double saveQPS = (double) savedPlayers / (totalDurationMs / 1000.0);
            double syncQPS = (double) savedPlayers / (syncDurationMs / 1000.0);
            double unifiedBatchLatencyMs = Duration.ofNanos(unifiedSaveDuration).toMillis();

            // Validation: Random sampling verification
            int sampleSize = 50;
            int successfulReads = 0;
            for (int i = 0; i < sampleSize; i++) {
                try {
                    UUID randomUuid = UUID.randomUUID();
                    service.getLegacyPlayerData(randomUuid);
                    // We expect null for random UUIDs, this tests the service health
                    successfulReads++;
                } catch (Exception exception) {
                    TestLogger.logInfo("player", "Sample read failed (expected): %s", exception.getMessage());
                }
            }

            boolean serviceHealthy = successfulReads >= sampleSize * 0.8; // 80% should complete without exceptions
            boolean performanceAcceptable = saveQPS >= 50.0; // Minimum 50 QPS expected
            boolean syncEfficient = syncQPS >= 100.0; // Sync should be faster

            TestLogger.logValidation("player", "PlayerDataServiceQPS",
                    performanceAcceptable && syncEfficient && serviceHealthy,
                    String.format("Player QPS - Save: %.1f ops/s, Sync: %.1f ops/s, UnifiedBatchLatency: %.2fms, " +
                                    "TotalPlayers: %d, Duration: %dms, ServiceHealthy: %s",
                            saveQPS, syncQPS, unifiedBatchLatencyMs, savedPlayers, totalDurationMs, serviceHealthy));

            return performanceAcceptable && syncEfficient && serviceHealthy;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Player QPS test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test entity data service QPS using batch operations and task synchronization.
     */
    public static boolean testEntityDataServiceQPS() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("qps-entity");
            TestTimer timer = new TestTimer();

            // Test parameters
            int batchSize = 500;
            int concurrentBatches = 6;

            // Phase 1: Concurrent entity data preparation (no saving yet)
            AtomicInteger preparedEntities = new AtomicInteger(0);
            CountDownLatch entityPreparationLatch = new CountDownLatch(concurrentBatches);

            List<CompletableFuture<List<LegacyEntityData>>> entityPreparationFutures = new ArrayList<>();

            for (int batchId = 0; batchId < concurrentBatches; batchId++) {
                final int finalBatchId = batchId;

                CompletableFuture<List<LegacyEntityData>> preparationFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<LegacyEntityData> batchEntities = new ArrayList<>();

                        // Create batch of entities (preparation only)
                        for (int i = 0; i < batchSize; i++) {
                            UUID entityUuid = UUID.randomUUID();
                            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "StressEntity");

                            // Add realistic attributes
                            entity.addAttribute("batch_id", String.valueOf(finalBatchId));
                            entity.addAttribute("entity_index", String.valueOf(i));
                            entity.addAttribute("health", String.valueOf(100 + (i % 50)));
                            entity.addAttribute("position_x", String.valueOf((i % 1000) - 500));
                            entity.addAttribute("position_z", String.valueOf((i % 1000) - 500));
                            entity.addAttribute("type", "monster_" + (i % 10));
                            entity.addAttribute("created_at", String.valueOf(System.currentTimeMillis()));

                            batchEntities.add(entity);
                        }

                        preparedEntities.addAndGet(batchEntities.size());

                        TestLogger.logInfo("player", String.format("Batch %d: prepared %d entities",
                                finalBatchId, batchEntities.size()));

                        return batchEntities;

                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Entity preparation batch %d failed: %s", finalBatchId, exception.getMessage());
                        return new ArrayList<>();
                    } finally {
                        entityPreparationLatch.countDown();
                    }
                });

                entityPreparationFutures.add(preparationFuture);
            }

            // Wait for all entity preparation to complete
            boolean allEntityPrepared = entityPreparationLatch.await(60, TimeUnit.SECONDS);
            if (!allEntityPrepared) {
                TestLogger.logFailure("player", "Entity preparation timed out");
                return false;
            }

            // Collect all prepared entity data
            List<LegacyEntityData> allEntities = new ArrayList<>();
            for (CompletableFuture<List<LegacyEntityData>> future : entityPreparationFutures) {
                try {
                    allEntities.addAll(future.get());
                } catch (Exception exception) {
                    TestLogger.logFailure("player", "Failed to collect prepared entity data: %s", exception.getMessage());
                }
            }

            // Phase 2: Single unified batch save operation - START TIMER HERE
            TestLogger.logInfo("player", "Starting unified batch save of %d entities", allEntities.size());
            timer.startTimer("entity-qps-test");
            long unifiedEntitySaveStart = System.nanoTime();
            service.saveEntities(allEntities);
            long unifiedEntitySaveEnd = System.nanoTime();
            long unifiedEntitySaveDuration = unifiedEntitySaveEnd - unifiedEntitySaveStart;
            int totalSaved = allEntities.size();

            // Phase 3: Wait for RStream processing to complete
            TestLogger.logInfo("player", "Waiting for RStream processing for %d entities", totalSaved);
            Thread.sleep(2000); // Allow RStream tasks to process batch saves

            timer.stopTimer("entity-qps-test");
            long totalDurationMs = timer.getTimerResult("entity-qps-test").getDuration();

            // Phase 4: Performance metrics calculation
            double batchQPS = (double) totalSaved / (totalDurationMs / 1000.0);
            double unifiedEntityBatchLatencyMs = Duration.ofNanos(unifiedEntitySaveDuration).toMillis();

            // Phase 4: Validation through sampling
            int sampleSize = 100;
            int successfulRetrievals = 0;
            long retrievalStartTime = System.nanoTime();

            for (int i = 0; i < sampleSize; i++) {
                try {
                    UUID randomUuid = UUID.randomUUID();
                    service.getEntityData(randomUuid);
                    // We expect null for random UUIDs, testing service health
                    successfulRetrievals++;
                } catch (Exception exception) {
                    TestLogger.logInfo("player", "Sample retrieval handled: %s", exception.getClass().getSimpleName());
                }
            }

            long retrievalEndTime = System.nanoTime();
            double retrievalQPS = (double) sampleSize / (Duration.ofNanos(retrievalEndTime - retrievalStartTime).toMillis() / 1000.0);

            boolean batchPerformanceGood = batchQPS >= 100.0; // Minimum 100 QPS for batch ops
            boolean retrievalPerformanceGood = retrievalQPS >= 500.0; // Reads should be fast
            boolean serviceStable = successfulRetrievals >= sampleSize * 0.9; // 90% should complete

            TestLogger.logValidation("player", "EntityDataServiceQPS",
                    batchPerformanceGood && retrievalPerformanceGood && serviceStable,
                    String.format("Entity QPS - Batch: %.1f ops/s, Retrieval: %.1f ops/s, " +
                                    "UnifiedBatchLatency: %.2fms, TotalEntities: %d, Duration: %dms, ServiceStable: %s",
                            batchQPS, retrievalQPS, unifiedEntityBatchLatencyMs, totalSaved, totalDurationMs, serviceStable));

            return batchPerformanceGood && retrievalPerformanceGood && serviceStable;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Entity QPS test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test mixed workload performance with both player and entity operations.
     */
    public static boolean testMixedWorkloadPerformance() {
        try {
            LegacyPlayerDataService playerService = TestConnectionResource.createTestService("mixed-player");
            LegacyEntityDataService entityService = TestConnectionResource.createTestEntityService("mixed-entity");
            TestTimer timer = new TestTimer();

            // Test parameters
            int playersPerThread = 300;
            int mixedThreads = 4;

            AtomicInteger totalOperations = new AtomicInteger(0);
            CountDownLatch mixedLatch = new CountDownLatch(mixedThreads);

            // Mixed workload: data preparation phase (no saving yet)
            List<CompletableFuture<MixedWorkloadData>> preparationFutures = new ArrayList<>();

            for (int threadId = 0; threadId < mixedThreads; threadId++) {
                final int finalThreadId = threadId;

                CompletableFuture<MixedWorkloadData> preparationFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        // Prepare player and entity data
                        List<LegacyPlayerData> playerBatch = new ArrayList<>();
                        List<LegacyEntityData> allEntities = new ArrayList<>();

                        for (int i = 0; i < playersPerThread; i++) {
                            UUID playerUuid = UUID.randomUUID();
                            LegacyPlayerData player = LegacyPlayerData.of(playerUuid);
                            player.addData("thread_id", String.valueOf(finalThreadId));
                            player.addData("operation_index", String.valueOf(i));

                            playerBatch.add(player);

                            // Prepare entity batch every 50 player operations
                            if (i % 50 == 0) {
                                for (int j = 0; j < 20; j++) {
                                    UUID entityUuid = UUID.randomUUID();
                                    LegacyEntityData entity = LegacyEntityData.of(entityUuid, "MixedEntity");
                                    entity.addAttribute("thread_id", String.valueOf(finalThreadId));
                                    entity.addAttribute("batch_index", String.valueOf(i / 50));
                                    allEntities.add(entity);
                                }
                            }
                        }

                        TestLogger.logInfo("player", "Thread %d prepared %d players and %d entities",
                                finalThreadId, playerBatch.size(), allEntities.size());

                        return new MixedWorkloadData(playerBatch, allEntities);

                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Mixed thread %d preparation failed: %s", finalThreadId, exception.getMessage());
                        return new MixedWorkloadData(new ArrayList<>(), new ArrayList<>());
                    } finally {
                        mixedLatch.countDown();
                    }
                });

                preparationFutures.add(preparationFuture);
            }

            // Wait for mixed workload preparation completion
            boolean mixedCompleted = mixedLatch.await(90, TimeUnit.SECONDS);
            if (!mixedCompleted) {
                TestLogger.logFailure("player", "Mixed workload preparation timed out");
                return false;
            }

            // Collect all prepared data
            List<LegacyPlayerData> allMixedPlayers = new ArrayList<>();
            List<LegacyEntityData> allMixedEntities = new ArrayList<>();

            for (CompletableFuture<MixedWorkloadData> future : preparationFutures) {
                try {
                    MixedWorkloadData data = future.get();
                    allMixedPlayers.addAll(data.players);
                    allMixedEntities.addAll(data.entities);
                } catch (Exception exception) {
                    TestLogger.logFailure("player", "Failed to collect mixed workload data: %s", exception.getMessage());
                }
            }

            // Execute unified batch saves - START TIMER HERE
            TestLogger.logInfo("player", "Starting unified saves: %d players, %d entities", allMixedPlayers.size(), allMixedEntities.size());
            timer.startTimer("mixed-workload-test");
            playerService.saveLegacyPlayersData(allMixedPlayers);
            entityService.saveEntities(allMixedEntities);
            totalOperations.set(allMixedPlayers.size() + allMixedEntities.size());

            // Perform player synchronization task (entity data already saved via batch)
            TestLogger.logInfo("player", "Starting player synchronization for mixed workload");
            long syncStartTime = System.nanoTime();

            CompletableFuture<Void> playerSync = L1ToL2PlayerDataSyncTask.of(playerService).start().thenApply(v -> null);
            playerSync.get(60, TimeUnit.SECONDS);

            long syncEndTime = System.nanoTime();
            long syncDurationMs = Duration.ofNanos(syncEndTime - syncStartTime).toMillis();

            timer.stopTimer("mixed-workload-test");
            long totalDurationMs = timer.getTimerResult("mixed-workload-test").getDuration();

            // Performance calculations
            double mixedQPS = (double) totalOperations.get() / (totalDurationMs / 1000.0);
            double playerSyncThroughput = (double) allMixedPlayers.size() / (syncDurationMs / 1000.0);

            boolean mixedPerformanceGood = mixedQPS >= 75.0; // Mixed workload should achieve 75+ QPS
            boolean syncEfficient = playerSyncThroughput >= 100.0; // Player sync should be efficient
            boolean operationCountReasonable = totalOperations.get() >= mixedThreads * (playersPerThread + playersPerThread / 50 * 20);

            TestLogger.logValidation("player", "MixedWorkloadPerformance",
                    mixedPerformanceGood && syncEfficient && operationCountReasonable,
                    String.format("Mixed Workload - QPS: %.1f ops/s, PlayerSyncThroughput: %.1f ops/s, " +
                                    "TotalOps: %d, Duration: %dms, SyncDuration: %dms",
                            mixedQPS, playerSyncThroughput, totalOperations.get(), totalDurationMs, syncDurationMs));

            return mixedPerformanceGood && syncEfficient && operationCountReasonable;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Mixed workload test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test system behavior under memory pressure with large datasets.
     */
    public static boolean testMemoryPressurePerformance() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("memory-pressure");
            TestTimer timer = new TestTimer();

            // Memory pressure parameters
            int largeBatchCount = 5;
            int entitiesPerLargeBatch = 1000;

            long initialMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            TestLogger.logInfo("player", "Initial memory usage: %d MB", (initialMemory / 1024 / 1024));

            // Phase 1: Concurrent entity data preparation (no saving yet)
            List<CompletableFuture<List<LegacyEntityData>>> preparationFutures = new ArrayList<>();
            CountDownLatch preparationLatch = new CountDownLatch(largeBatchCount);

            for (int batchId = 0; batchId < largeBatchCount; batchId++) {
                final int finalBatchId = batchId;

                CompletableFuture<List<LegacyEntityData>> preparationFuture = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<LegacyEntityData> largeEntities = new ArrayList<>();

                        for (int i = 0; i < entitiesPerLargeBatch; i++) {
                            UUID entityUuid = UUID.randomUUID();
                            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "LargeEntity");

                            // Create larger entities to pressure memory
                            entity.addAttribute("large_data_1", "x".repeat(500));
                            entity.addAttribute("large_data_2", "y".repeat(500));
                            entity.addAttribute("metadata", String.format("batch_%d_entity_%d_timestamp_%d",
                                    finalBatchId, i, System.currentTimeMillis()));
                            entity.addAttribute("complex_data", generateComplexData(i));

                            largeEntities.add(entity);
                        }

                        TestLogger.logInfo("player", String.format("Batch %d prepared %d entities",
                                finalBatchId, largeEntities.size()));

                        return largeEntities;

                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Memory pressure batch %d preparation failed: %s", finalBatchId, exception.getMessage());
                        return new ArrayList<>();
                    } finally {
                        preparationLatch.countDown();
                    }
                });

                preparationFutures.add(preparationFuture);
            }

            // Wait for all preparation to complete
            boolean allPrepared = preparationLatch.await(60, TimeUnit.SECONDS);
            if (!allPrepared) {
                TestLogger.logFailure("player", "Entity preparation timed out");
                return false;
            }

            // Phase 2: Collect all prepared entity data
            List<LegacyEntityData> allLargeEntities = new ArrayList<>();
            for (CompletableFuture<List<LegacyEntityData>> future : preparationFutures) {
                try {
                    allLargeEntities.addAll(future.get());
                } catch (Exception exception) {
                    TestLogger.logFailure("player", "Failed to collect prepared entity data: %s", exception.getMessage());
                }
            }

            // Phase 3: Single unified batch save operation - START TIMER HERE
            TestLogger.logInfo("player", "Starting unified batch save of %d large entities", allLargeEntities.size());
            timer.startTimer("memory-pressure-test");
            int processedBatches = 0;
            try {
                service.saveEntities(allLargeEntities);
                processedBatches = largeBatchCount; // All batches processed in unified operation

                // Force garbage collection suggestion
                System.gc();

                long currentMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
                TestLogger.logInfo("player", String.format("Unified batch completed, memory: %d MB",
                        currentMemory / 1024 / 1024));

            } catch (Exception exception) {
                TestLogger.logFailure("player", "Unified batch save failed: %s", exception.getMessage());
            }

            // Allow RStream processing to complete (entity data already saved via batch)
            TestLogger.logInfo("player", "Waiting for RStream processing under memory pressure");
            Thread.sleep(2000);

            timer.stopTimer("memory-pressure-test");
            long totalDurationMs = timer.getTimerResult("memory-pressure-test").getDuration();

            long finalMemory = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory();
            long memoryIncrease = finalMemory - initialMemory;

            // Performance validation under pressure
            int totalEntities = largeBatchCount * entitiesPerLargeBatch;
            double qpsUnderPressure = (double) totalEntities / (totalDurationMs / 1000.0);

            boolean allBatchesProcessed = processedBatches == largeBatchCount;
            boolean performanceUnderPressure = qpsUnderPressure >= 30.0; // Lower threshold due to memory pressure
            boolean memoryUsageReasonable = memoryIncrease < 500 * 1024 * 1024; // Less than 500MB increase

            TestLogger.logValidation("player", "MemoryPressurePerformance",
                    allBatchesProcessed && performanceUnderPressure && memoryUsageReasonable,
                    String.format("Memory Pressure - QPS: %.1f ops/s, MemoryIncrease: %d MB, " +
                                    "ProcessedBatches: %d/%d, TotalEntities: %d, Duration: %dms",
                            qpsUnderPressure, memoryIncrease / 1024 / 1024, processedBatches,
                            largeBatchCount, totalEntities, totalDurationMs));

            return allBatchesProcessed && performanceUnderPressure && memoryUsageReasonable;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Memory pressure test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Comprehensive performance summary test.
     */
    public static boolean testPerformanceSummary() {
        try {
            globalTimer.startTimer("complete-performance-suite");

            // Run all performance tests and collect metrics
            boolean playerQPS = testPlayerDataServiceQPS();
            boolean entityQPS = testEntityDataServiceQPS();
            boolean mixedWorkload = testMixedWorkloadPerformance();
            boolean memoryPressure = testMemoryPressurePerformance();

            globalTimer.stopTimer("complete-performance-suite");
            long totalSuiteDuration = globalTimer.getTimerResult("complete-performance-suite").getDuration();

            // Overall assessment
            int passedTests = 0;
            if (playerQPS) passedTests++;
            if (entityQPS) passedTests++;
            if (mixedWorkload) passedTests++;
            if (memoryPressure) passedTests++;

            boolean overallPerformanceGood = passedTests >= 3; // At least 3/4 tests should pass
            boolean suiteCompletedInTime = totalSuiteDuration < 180000; // Under 3 minutes

            TestLogger.logValidation("player", "PerformanceSummary",
                    overallPerformanceGood && suiteCompletedInTime,
                    String.format("Performance Suite - PassedTests: %d/4, Duration: %dms, " +
                                    "PlayerQPS: %s, EntityQPS: %s, MixedWorkload: %s, MemoryPressure: %s",
                            passedTests, totalSuiteDuration,
                            playerQPS ? "PASS" : "FAIL",
                            entityQPS ? "PASS" : "FAIL",
                            mixedWorkload ? "PASS" : "FAIL",
                            memoryPressure ? "PASS" : "FAIL"));

            return overallPerformanceGood && suiteCompletedInTime;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Performance summary test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Helper method to generate complex data for memory pressure testing.
     */
    private static String generateComplexData(int index) {
        StringBuilder complexData = new StringBuilder();
        complexData.append("complex_").append(index).append("_");

        // Add some varied data patterns
        for (int i = 0; i < 10; i++) {
            complexData.append(String.format("field_%d:%d;", i, index * i));
        }

        return complexData.toString();
    }

    /**
     * Helper class to hold mixed workload data (players and entities).
     */
    private static class MixedWorkloadData {
        final List<LegacyPlayerData> players;
        final List<LegacyEntityData> entities;

        public MixedWorkloadData(List<LegacyPlayerData> players, List<LegacyEntityData> entities) {
            this.players = players;
            this.entities = entities;
        }
    }
}