package net.legacy.library.cache.test;

import lombok.Getter;
import lombok.Setter;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.cache.service.AbstractLockable;
import net.legacy.library.cache.service.LockableInterface;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Test class for validating AbstractLockable lock management logic.
 *
 * <p>This test class focuses on the critical lock acquisition, timeout handling,
 * and thread interruption logic in {@link AbstractLockable}, including concurrent
 * access scenarios and error conditions.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-07 23:40
 */
@ModuleTest(
        testName = "lock-management-test",
        description = "Tests AbstractLockable lock acquisition and timeout logic",
        tags = {"cache", "lock", "concurrency", "timeout"},
        priority = 2,
        timeout = 5000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class LockManagementTest {
    /**
     * Tests successful lock acquisition and execution.
     */
    public static boolean testSuccessfulLockExecution() {
        try {
            TestResource resource = new TestResource();
            LockableInterface<TestResource> lockable = AbstractLockable.of(resource);

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            String result = lockable.execute(
                    TestResource::getLock,
                    res -> {
                        res.setData("modified");
                        return res.getData();
                    },
                    settings
            );

            boolean success = "modified".equals(result) && "modified".equals(resource.getData());

            TestLogger.logInfo("cache", "Successful lock execution test: success=%s, result=%s",
                    success, result);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Successful lock execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests lock timeout scenario.
     */
    public static boolean testLockTimeout() {
        try {
            TestResource resource = new TestResource();
            LockableInterface<TestResource> lockable = AbstractLockable.of(resource);

            // Hold the lock in another thread to create contention
            Thread lockHolder = new Thread(() -> {
                resource.getLock().lock();
                try {
                    // Hold lock for longer than the test timeout
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    resource.getLock().unlock();
                }
            });

            lockHolder.start();

            // Give the other thread time to acquire the lock
            Thread.sleep(10);

            try {
                LockSettings shortTimeout = new LockSettings(20, -1, TimeUnit.MILLISECONDS);

                // This should timeout and throw RuntimeException
                lockable.execute(
                        TestResource::getLock,
                        res -> {
                            res.setData("should-not-happen");
                            return res.getData();
                        },
                        shortTimeout
                );

                TestLogger.logFailure("cache", "Lock timeout test failed - expected RuntimeException");
                lockHolder.join(); // Clean up
                return false; // Should not reach here

            } catch (RuntimeException exception) {
                boolean isTimeoutException = exception.getMessage().contains("Could not acquire lock");

                TestLogger.logInfo("cache", "Lock timeout test: caught expected exception=%s, message=%s",
                        isTimeoutException, exception.getMessage());

                lockHolder.join(); // Clean up
                return isTimeoutException;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Lock timeout test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests thread interruption during lock acquisition.
     */
    public static boolean testThreadInterruption() {
        try {
            TestResource resource = new TestResource();
            LockableInterface<TestResource> lockable = AbstractLockable.of(resource);

            // Hold the lock to force waiting
            resource.getLock().lock();

            try {
                CountDownLatch latch = new CountDownLatch(1);
                boolean[] testResult = {false};
                boolean[] interruptedCorrectly = {false};

                Thread testThread = new Thread(() -> {
                    try {
                        latch.countDown(); // Signal that thread is ready

                        LockSettings settings = new LockSettings(5, -1, TimeUnit.SECONDS);

                        lockable.execute(
                                TestResource::getLock,
                                res -> "should-not-execute",
                                settings
                        );

                        testResult[0] = false; // Should not reach here
                    } catch (RuntimeException exception) {
                        boolean isInterruptedException = exception.getMessage().contains("Thread interrupted");
                        boolean isThreadInterrupted = Thread.currentThread().isInterrupted();

                        testResult[0] = isInterruptedException;
                        interruptedCorrectly[0] = isThreadInterrupted;

                        TestLogger.logInfo("cache", "Thread interruption caught: isInterruptedException=%s, threadInterrupted=%s",
                                isInterruptedException, isThreadInterrupted);
                    }
                });

                testThread.start();
                latch.await(); // Wait for thread to start

                // Give a moment for the thread to start waiting for lock
                Thread.sleep(100);

                // Interrupt the thread
                testThread.interrupt();
                testThread.join(1000);

                TestLogger.logInfo("cache", "Thread interruption test: result=%s, interruptedCorrectly=%s",
                        testResult[0], interruptedCorrectly[0]);

                return testResult[0] && interruptedCorrectly[0];

            } finally {
                resource.getLock().unlock();
            }
        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Thread interruption test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent access with multiple threads.
     */
    public static boolean testConcurrentAccess() {
        try {
            TestResource resource = new TestResource();
            LockableInterface<TestResource> lockable = AbstractLockable.of(resource);

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            int threadCount = 5;
            CountDownLatch startLatch = new CountDownLatch(1);
            CountDownLatch completeLatch = new CountDownLatch(threadCount);
            boolean[] results = new boolean[threadCount];

            // Create multiple threads that will compete for the lock
            for (int i = 0; i < threadCount; i++) {
                final int threadIndex = i;
                new Thread(() -> {
                    try {
                        startLatch.await(); // Wait for all threads to be ready

                        String result = lockable.execute(
                                TestResource::getLock,
                                res -> {
                                    String current = res.getData();
                                    res.setData(current + "-" + threadIndex);
                                    return res.getData();
                                },
                                settings
                        );

                        results[threadIndex] = result != null && result.contains("-" + threadIndex);

                    } catch (Exception exception) {
                        TestLogger.logFailure("cache", "Concurrent thread %d failed: %s", threadIndex, exception.getMessage());
                        results[threadIndex] = false;
                    } finally {
                        completeLatch.countDown();
                    }
                }).start();
            }

            // Start all threads
            startLatch.countDown();

            // Wait for all threads to complete
            boolean completed = completeLatch.await(5, TimeUnit.SECONDS);

            if (!completed) {
                TestLogger.logFailure("cache", "Concurrent access test timed out");
                return false;
            }

            // Check that all threads succeeded
            boolean allSucceeded = true;
            for (int i = 0; i < threadCount; i++) {
                if (!results[i]) {
                    allSucceeded = false;
                    break;
                }
            }

            // Check that the final data contains all thread modifications
            String finalData = resource.getData();
            boolean containsAllModifications = true;
            for (int i = 0; i < threadCount; i++) {
                if (!finalData.contains("-" + i)) {
                    containsAllModifications = false;
                    break;
                }
            }

            TestLogger.logInfo("cache", "Concurrent access test: allSucceeded=%s, containsAllModifications=%s, finalData=%s",
                    allSucceeded, containsAllModifications, finalData);

            return allSucceeded && containsAllModifications;

        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Concurrent access test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests lock release after exception in function.
     */
    public static boolean testLockReleaseAfterException() {
        try {
            TestResource resource = new TestResource();
            LockableInterface<TestResource> lockable = AbstractLockable.of(resource);

            LockSettings settings = new LockSettings(1000, -1, TimeUnit.MILLISECONDS);

            // Execute function that throws exception
            try {
                lockable.execute(
                        TestResource::getLock,
                        res -> {
                            throw new RuntimeException("Test exception");
                        },
                        settings
                );

                TestLogger.logFailure("cache", "Lock release test failed - expected exception");
                return false; // Should not reach here

            } catch (RuntimeException exception) {
                if (!"Test exception".equals(exception.getMessage())) {
                    TestLogger.logFailure("cache", "Lock release test - unexpected exception: %s", exception.getMessage());
                    return false;
                }
            }

            // Verify that lock was released by trying to acquire it again
            boolean lockReleased = resource.getLock().tryLock(100, TimeUnit.MILLISECONDS);
            if (lockReleased) {
                resource.getLock().unlock();
            }

            TestLogger.logInfo("cache", "Lock release after exception test: lockReleased=%s", lockReleased);

            return lockReleased;

        } catch (Exception exception) {
            TestLogger.logFailure("cache", "Lock release after exception test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test resource wrapper for testing lock functionality.
     */
    @Setter
    @Getter
    private static class TestResource {
        private final ReentrantLock lock = new ReentrantLock();
        private String data = "initial";
    }
}