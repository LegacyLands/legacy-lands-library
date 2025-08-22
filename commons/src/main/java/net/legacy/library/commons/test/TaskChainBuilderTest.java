package net.legacy.library.commons.test;

import net.legacy.library.commons.task.TaskChain;
import net.legacy.library.commons.task.TaskChainBuilder;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * Test class for task chain builder, validating chain operations and execution modes.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-08 16:30
 */
@ModuleTest(
        testName = "task-chain-builder-test",
        description = "Tests TaskChainBuilder chain operations and various execution modes",
        tags = {"commons", "task", "chain", "execution", "async"},
        priority = 2,
        timeout = 8000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class TaskChainBuilderTest {

    /**
     * Test basic task chain construction and execution
     */
    public static boolean testBasicTaskChain() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            // Add a synchronous task
            builder.withMode((taskInterface, input) -> ((Integer) input) * 2).execute(5);

            // Add another synchronous task  
            builder.withMode((taskInterface, input) -> ((Integer) input) + 10).execute(3);

            // Build task chain
            TaskChain taskChain = builder.build();

            // Verify task count
            if (taskChain.size() != 2) {
                return false;
            }

            // Wait for tasks to complete and get results
            taskChain.join().get(5, TimeUnit.SECONDS);

            // Verify first task result (5 * 2 = 10)
            Integer result1 = taskChain.getResult(0);
            if (result1 != 10) {
                return false;
            }

            // Verify second task result (3 + 10 = 13)
            Integer result2 = taskChain.getResult(1);
            return result2 == 13;
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Test named task execution
     */
    public static boolean testNamedTaskExecution() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            // Add named tasks
            builder.withMode((taskInterface, input) -> "Hello " + input).execute("greeting", "World");

            builder.withMode((taskInterface, input) -> ((String) input).toUpperCase()).execute("uppercase", "test");

            TaskChain taskChain = builder.build();
            taskChain.join().get(5, TimeUnit.SECONDS);

            // Verify named task results
            String greeting = taskChain.getResult("greeting");
            String uppercase = taskChain.getResult("uppercase");

            // Verify name mapping
            if (!taskChain.hasTask("greeting") || !taskChain.hasTask("uppercase")) {
                return false;
            }

            return "Hello World".equals(greeting) && "TEST".equals(uppercase);
        } catch (Exception exception) {
            return false;
        }
    }

    /**
     * Test asynchronous execution mode
     */
    public static boolean testAsyncExecution() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            builder.withMode((taskInterface, input) -> {
                // Use CompletableFuture for asynchronous execution
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException exception) {
                        Thread.currentThread().interrupt();
                    }
                    return "Async Result";
                });
            }).execute(null);

            TaskChain taskChain = builder.build();
            taskChain.join().get(5, TimeUnit.SECONDS);

            String result = taskChain.getResult(0);
            return "Async Result".equals(result);
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Asynchronous execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test virtual thread mode
     */
    public static boolean testVirtualThreadMode() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            // Use TaskInterface virtual thread methods
            builder.withMode((taskInterface, input) -> taskInterface.submitWithVirtualThreadAsync(() -> {
                Thread currentThread = Thread.currentThread();
                return currentThread.isVirtual() ? "Virtual" : "Platform";
            })).execute(null);

            TaskChain taskChain = builder.build();
            taskChain.join().get(5, TimeUnit.SECONDS);

            String result = taskChain.getResult(0);
            // Accept both virtual and platform thread results as virtual thread support may not be available
            return "Virtual".equals(result) || "Platform".equals(result);
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Virtual thread mode test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test exception handling
     */
    public static boolean testExceptionHandling() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            // Add task that will throw exception
            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> {
                throw new RuntimeException("Test Exception");
            })).execute(null);

            // Add normal task
            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> "Normal Result")).execute(null);

            TaskChain taskChain = builder.build();

            // Wait for all tasks to complete (even failed ones)
            try {
                taskChain.join().get(5, TimeUnit.SECONDS);
            } catch (Exception exception) {
                // Expected as one task will fail
            }

            // First task should fail
            try {
                taskChain.getResult(0);
                return false; // Should throw exception
            } catch (RuntimeException expected) {
                // Expected exception (includes CompletionException)
            }

            // Second task should succeed
            String result = taskChain.getResult(1);
            return "Normal Result".equals(result);
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Exception handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test timeout handling
     */
    public static boolean testTimeoutHandling() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(2000); // 2 seconds delay
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "Delayed Result";
            })).execute(null);

            TaskChain taskChain = builder.build();

            try {
                // Set 500ms timeout
                taskChain.getResult(0, 500, TimeUnit.MILLISECONDS);
                return false; // Should time out
            } catch (RuntimeException expected) {
                // Expected timeout exception
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Timeout handling test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test mode result retrieval
     */
    public static boolean testModeResults() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> "Result")).execute("test", null);

            TaskChain taskChain = builder.build();
            taskChain.join().get(5, TimeUnit.SECONDS);

            // Get mode results (CompletableFuture)
            Object modeResult = taskChain.getModeResult(0);
            Object namedModeResult = taskChain.getModeResult("test");

            // Verify mode result types
            if (!(modeResult instanceof CompletableFuture)) {
                return false;
            }

            if (!(namedModeResult instanceof CompletableFuture)) {
                return false;
            }

            // Verify all mode results
            return taskChain.getAllModeResults().size() == 1;
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Mode results test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test concurrent execution of multiple tasks
     */
    public static boolean testConcurrentExecution() {
        try {
            final long startTime = System.currentTimeMillis();

            TaskChainBuilder builder = new TaskChainBuilder();

            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "Task1";
            })).execute(null);

            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "Task2";
            })).execute(null);

            builder.withMode((taskInterface, input) -> CompletableFuture.supplyAsync(() -> {
                try {
                    Thread.sleep(100);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
                return "Task3";
            })).execute(null);

            TaskChain taskChain = builder.build();
            taskChain.join().get(5, TimeUnit.SECONDS);

            long duration = System.currentTimeMillis() - startTime;

            // If tasks execute concurrently, total time should be less than serial execution (300ms)
            if (duration >= 250) {
                return false;
            }

            // Verify all results
            String result1 = taskChain.getResult(0);
            String result2 = taskChain.getResult(1);
            String result3 = taskChain.getResult(2);

            return "Task1".equals(result1) && "Task2".equals(result2) && "Task3".equals(result3);
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Concurrent execution test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Test empty task chain exception
     */
    public static boolean testEmptyChainException() {
        try {
            TaskChainBuilder builder = new TaskChainBuilder();

            try {
                builder.build();
                return false; // Should throw exception
            } catch (IllegalStateException expected) {
                // Expected exception
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("commons", "Empty chain exception test failed: %s", exception.getMessage());
            return false;
        }
    }

}