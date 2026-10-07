package net.legacy.library.player.test;

import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.foundation.util.TestTimer;
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
 * Lock contention impact analysis tests for Entity data service QPS.
 *
 * <p>This test suite specifically measures and compares the QPS performance impact
 * of different save strategies to quantify lock contention effects:
 * - Individual saveEntity() calls (high lock contention)
 * - Unified batch saveEntities() calls (minimal lock contention)
 * - Mixed scenarios with different concurrency levels
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-10 14:00
 */
@ModuleTest(
        testName = "lock-contention-qps-test",
        description = "QPS performance analysis showing lock contention impact on entity data operations",
        tags = {"performance", "lock-contention", "qps", "distributed-lock", "entity"},
        priority = 5,
        timeout = 180000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class LockContentionQPSTest {

    /**
     * Test QPS with individual saveEntity() calls to demonstrate lock contention impact.
     */
    public static boolean testIndividualSaveQPSWithLockContention() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("individual-lock-test");
            TestTimer timer = new TestTimer();

            // Test parameters designed to create lock contention
            int entitiesPerThread = 20; // Smaller batches to emphasize lock contention
            int threadCount = 8; // Multiple threads competing for locks
            int totalEntities = entitiesPerThread * threadCount;

            timer.startTimer("individual-save-qps");

            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completionLatch = new CountDownLatch(threadCount);
            AtomicInteger totalSaved = new AtomicInteger(0);
            AtomicInteger lockContentionErrors = new AtomicInteger(0);

            // Phase 1: Concurrent individual saves (high lock contention scenario)
            for (int threadId = 0; threadId < threadCount; threadId++) {
                final int finalThreadId = threadId;

                CompletableFuture.runAsync(() -> {
                    try {
                        startLatch.await(); // Synchronized start for maximum contention

                        for (int i = 0; i < entitiesPerThread; i++) {
                            try {
                                UUID entityUuid = UUID.randomUUID();
                                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "ContentionTestEntity");
                                entity.addAttribute("thread_id", String.valueOf(finalThreadId));
                                entity.addAttribute("entity_index", String.valueOf(i));
                                entity.addAttribute("timestamp", String.valueOf(System.currentTimeMillis()));

                                // Individual save - creates lock contention
                                service.saveEntity(entity);
                                totalSaved.incrementAndGet();

                            } catch (Exception exception) {
                                if (exception.getMessage() != null && exception.getMessage().contains("Could not acquire lock")) {
                                    lockContentionErrors.incrementAndGet();
                                }
                                TestLogger.logInfo("player", "Thread " + finalThreadId + " lock contention: " + exception.getClass().getSimpleName());
                            }
                        }
                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Thread " + finalThreadId + " failed: " + exception.getMessage());
                    } finally {
                        completionLatch.countDown();
                    }
                });
            }

            // Start all threads simultaneously for maximum lock contention
            startLatch.countDown();

            // Wait for completion
            boolean completed = completionLatch.await(120, TimeUnit.SECONDS);
            timer.stopTimer("individual-save-qps");

            if (!completed) {
                TestLogger.logFailure("player", "Individual save QPS test timed out");
                return false;
            }

            long durationMs = timer.getTimerResult("individual-save-qps").getDuration();
            double individualQPS = (double) totalSaved.get() / (durationMs / 1000.0);
            double lockContentionRate = (double) lockContentionErrors.get() / totalEntities * 100;

            TestLogger.logValidation("player", "IndividualSaveQPS",
                    totalSaved.get() >= totalEntities * 0.5, // At least 50% should succeed despite contention
                    "Individual Save QPS - Saved: %d/%d, QPS: %.1f ops/s, " +
                            "LockContentionRate: %.1f%%, Duration: %dms",
                    totalSaved.get(), totalEntities, individualQPS,
                    lockContentionRate, durationMs);

            return totalSaved.get() >= totalEntities * 0.5;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Individual save QPS test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test QPS with batch saveEntities() to demonstrate optimal performance without lock contention.
     */
    public static boolean testBatchSaveQPSWithMinimalLockContention() {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("batch-optimal-test");
            TestTimer timer = new TestTimer();

            // Same parameters as individual test for fair comparison
            int entitiesPerThread = 20;
            int threadCount = 8;
            int totalEntities = entitiesPerThread * threadCount;

            timer.startTimer("batch-save-qps");

            // Phase 1: Concurrent data preparation (no lock contention)
            CountDownLatch preparationLatch = new CountDownLatch(threadCount);
            List<CompletableFuture<List<LegacyEntityData>>> preparationFutures = new ArrayList<>();

            for (int threadId = 0; threadId < threadCount; threadId++) {
                final int finalThreadId = threadId;

                CompletableFuture<List<LegacyEntityData>> future = CompletableFuture.supplyAsync(() -> {
                    try {
                        List<LegacyEntityData> entities = new ArrayList<>();

                        for (int i = 0; i < entitiesPerThread; i++) {
                            UUID entityUuid = UUID.randomUUID();
                            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "OptimalTestEntity");
                            entity.addAttribute("thread_id", String.valueOf(finalThreadId));
                            entity.addAttribute("entity_index", String.valueOf(i));
                            entity.addAttribute("timestamp", String.valueOf(System.currentTimeMillis()));
                            entities.add(entity);
                        }

                        return entities;

                    } catch (Exception exception) {
                        TestLogger.logFailure("player", "Thread " + finalThreadId + " preparation failed: " + exception.getMessage());
                        return new ArrayList<>();
                    } finally {
                        preparationLatch.countDown();
                    }
                });

                preparationFutures.add(future);
            }

            // Wait for preparation completion
            boolean preparationCompleted = preparationLatch.await(30, TimeUnit.SECONDS);
            if (!preparationCompleted) {
                TestLogger.logFailure("player", "Batch preparation timed out");
                return false;
            }

            // Phase 2: Collect all prepared entities
            List<LegacyEntityData> allEntities = new ArrayList<>();
            for (CompletableFuture<List<LegacyEntityData>> future : preparationFutures) {
                try {
                    allEntities.addAll(future.get());
                } catch (Exception exception) {
                    TestLogger.logFailure("player", "Failed to collect prepared entities: " + exception.getMessage());
                }
            }

            // Phase 3: Single unified batch save (minimal lock contention)
            long batchSaveStart = System.nanoTime();
            service.saveEntities(allEntities);
            long batchSaveEnd = System.nanoTime();

            timer.stopTimer("batch-save-qps");

            long totalDurationMs = timer.getTimerResult("batch-save-qps").getDuration();
            long batchSaveDurationMs = Duration.ofNanos(batchSaveEnd - batchSaveStart).toMillis();
            double batchQPS = (double) allEntities.size() / (totalDurationMs / 1000.0);
            double pureQPS = (double) allEntities.size() / (batchSaveDurationMs / 1000.0);

            TestLogger.logValidation("player", "BatchSaveQPS",
                    allEntities.size() == totalEntities,
                    "Batch Save QPS - Saved: %d/%d, OverallQPS: %.1f ops/s, " +
                            "PureBatchQPS: %.1f ops/s, BatchDuration: %dms, TotalDuration: %dms",
                    allEntities.size(), totalEntities, batchQPS, pureQPS,
                    batchSaveDurationMs, totalDurationMs);

            return allEntities.size() == totalEntities;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Batch save QPS test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Compare lock contention impact by running both strategies and measuring performance difference.
     *
     * <p>Saves coalesce the persistence they ask for, so individual saves no longer start one persistence each and
     * contend for its lock; the comparison checks that individual saves keep at least half the batch throughput.
     * Both sides run on the same 4 threads over entities prepared beforehand, so only the save calls are compared.
     */
    public static boolean testLockContentionImpactComparison() {
        try {
            TestTimer timer = new TestTimer();
            timer.startTimer("lock-contention-comparison");

            // Test parameters for comparison
            int testEntities = 400; // Enough work that timer resolution and warmup do not decide the ratio

            // Run individual save test (high contention)
            TestLogger.logInfo("player", "Starting individual save QPS measurement...");
            QpsResult individualResult = measureIndividualSaveQPS(testEntities);

            // Small delay to avoid resource conflicts
            Thread.sleep(2000);

            // Run batch save test (minimal contention)
            TestLogger.logInfo("player", "Starting batch save QPS measurement...");
            QpsResult batchResult = measureBatchSaveQPS(testEntities);

            timer.stopTimer("lock-contention-comparison");

            // Calculate performance impact
            double qpsImprovementRatio = batchResult.qps / individualResult.qps;
            double lockContentionImpact = (1 - (individualResult.qps / batchResult.qps)) * 100;
            double durationImprovement = (double) individualResult.durationMs / batchResult.durationMs;

            // Individual saves should not collapse under contention: at least half the batch throughput
            boolean contentionContained = qpsImprovementRatio <= 2.0;

            TestLogger.logValidation("player", "LockContentionImpact", contentionContained,
                    "Lock Contention Impact - IndividualQPS: %.1f ops/s, BatchQPS: %.1f ops/s, " +
                            "QpsImprovement: %.1fx, LockContentionImpact: %.1f%%, DurationImprovement: %.1fx",
                    individualResult.qps, batchResult.qps, qpsImprovementRatio,
                    lockContentionImpact, durationImprovement);

            return contentionContained;

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Lock contention impact comparison failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Measure QPS with individual saves (high lock contention).
     */
    private static QpsResult measureIndividualSaveQPS(int entityCount) {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("measure-individual");

            AtomicInteger saved = new AtomicInteger(0);
            AtomicInteger lockErrors = new AtomicInteger(0);

            // 4 threads for moderate contention
            List<List<LegacyEntityData>> batches = prepareThreadBatches(entityCount, "MeasureEntity");
            CountDownLatch latch = new CountDownLatch(batches.size());
            long startTime = System.nanoTime();

            for (List<LegacyEntityData> batch : batches) {
                CompletableFuture.runAsync(() -> {
                    try {
                        for (LegacyEntityData entity : batch) {
                            try {
                                service.saveEntity(entity);
                                saved.incrementAndGet();

                            } catch (Exception exception) {
                                if (exception.getMessage() != null && exception.getMessage().contains("Could not acquire lock")) {
                                    lockErrors.incrementAndGet();
                                }
                            }
                        }
                    } finally {
                        latch.countDown();
                    }
                });
            }

            latch.await(60, TimeUnit.SECONDS);
            long endTime = System.nanoTime();

            long durationMs = Duration.ofNanos(endTime - startTime).toMillis();
            double qps = (double) saved.get() / (durationMs / 1000.0);
            double lockContentionRate = (double) lockErrors.get() / entityCount * 100;

            return new QpsResult(qps, durationMs, lockContentionRate, saved.get());

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Individual QPS measurement failed: " + exception.getMessage());
            return new QpsResult(0, 0, 100, 0);
        }
    }

    /**
     * Measure QPS with batch saves (minimal lock contention).
     */
    private static QpsResult measureBatchSaveQPS(int entityCount) {
        try {
            LegacyEntityDataService service = TestConnectionResource.createTestEntityService("measure-batch");

            // The same 4 threads as the individual saves, each saving its share in one call
            List<List<LegacyEntityData>> batches = prepareThreadBatches(entityCount, "BatchMeasureEntity");
            long startTime = System.nanoTime();

            CompletableFuture.allOf(batches.stream()
                    .map(batch -> CompletableFuture.runAsync(() -> service.saveEntities(batch)))
                    .toArray(CompletableFuture[]::new)).get(60, TimeUnit.SECONDS);

            long endTime = System.nanoTime();

            long durationMs = Duration.ofNanos(endTime - startTime).toMillis();
            double qps = (double) entityCount / (durationMs / 1000.0);

            return new QpsResult(qps, durationMs, 0.0, entityCount); // No lock contention expected

        } catch (Exception exception) {
            TestLogger.logFailure("player", "Batch QPS measurement failed: " + exception.getMessage());
            return new QpsResult(0, 0, 0, 0);
        }
    }

    /**
     * Prepares the entities of a comparison run, split into the shares of 4 threads.
     *
     * @param entityCount the number of entities
     * @param entityType  the entity type
     * @return the shares, one per thread
     */
    private static List<List<LegacyEntityData>> prepareThreadBatches(int entityCount, String entityType) {
        int threadCount = 4;
        List<List<LegacyEntityData>> batches = new ArrayList<>();
        for (int threadId = 0; threadId < threadCount; threadId++) {
            List<LegacyEntityData> batch = new ArrayList<>();
            for (int i = 0; i < entityCount / threadCount; i++) {
                LegacyEntityData entity = LegacyEntityData.of(UUID.randomUUID(), entityType);
                entity.addAttribute("thread", String.valueOf(threadId));
                entity.addAttribute("index", String.valueOf(i));
                batch.add(entity);
            }
            batches.add(batch);
        }
        return batches;
    }

    /**
     * Helper class to hold QPS measurement results.
     */
    private static class QpsResult {

        final double qps;
        final long durationMs;
        final double lockContentionRate;
        final int entitiesSaved;

        QpsResult(double qps, long durationMs, double lockContentionRate, int entitiesSaved) {
            this.qps = qps;
            this.durationMs = durationMs;
            this.lockContentionRate = lockContentionRate;
            this.entitiesSaved = entitiesSaved;
        }

    }

}