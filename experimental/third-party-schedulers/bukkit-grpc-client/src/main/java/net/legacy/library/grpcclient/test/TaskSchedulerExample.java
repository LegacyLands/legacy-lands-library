package net.legacy.library.grpcclient.test;

import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.legacy.library.grpcclient.task.GRPCTaskSchedulerClient;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

@RequiredArgsConstructor
public class TaskSchedulerExample {
    public void runDemonstrationLogic(GRPCTaskSchedulerClient scheduler) {
        Log.info("--- Running Task Scheduler Demonstration Logic ---");

        List<CompletableFuture<?>> futures = new ArrayList<>(); // Only for ASYNC tasks

        try {
            // --- Synchronous Task Examples ---
            Log.info("--- Running Synchronous Tasks ---");

            // Generate unique Task IDs for each call
            String addTaskId = "add-task-" + UUID.randomUUID();
            String addResult = scheduler.submitTaskBlocking(addTaskId, "add", 10, 20, 30, -5);
            Log.info("[TaskID: %s] Sync task result: add(10, 20, 30, -5) = %s", addTaskId, addResult);

            String removeTaskId = "remove-task-" + UUID.randomUUID();
            String removeResult = scheduler.submitTaskBlocking(removeTaskId, "remove", 100, 10, 5);
            Log.info("[TaskID: %s] Sync task result: remove(100, 10, 5) = %s", removeTaskId, removeResult);

            String ping1TaskId = "ping1-task-" + UUID.randomUUID();
            String pingResult1 = scheduler.submitTaskBlocking(ping1TaskId, "ping");
            Log.info("[TaskID: %s] Sync task result: ping() = %s", ping1TaskId, pingResult1);

            String ping2TaskId = "ping2-task-" + UUID.randomUUID();
            String pingResult2 = scheduler.submitTaskBlocking(ping2TaskId, "ping", "Java Client");
            Log.info("[TaskID: %s] Sync task result: ping(\"Java Client\") = %s", ping2TaskId, pingResult2);

            List<Integer> numbers = Arrays.asList(1, 2, 3, 4, 5);
            String sumTaskId = "sum-task-" + UUID.randomUUID();
            String sumResult = scheduler.submitTaskBlocking(sumTaskId, "sum_list", numbers);
            Log.info("[TaskID: %s] Sync List sum result: sum(%s) = %s", sumTaskId, numbers, sumResult);

            Map<String, Object> person = new HashMap<>();
            person.put("name", "Jane Doe");
            person.put("age", 28);
            person.put("isStudent", true);
            person.put("scores", Arrays.asList(88.0, 95.5));
            String personTaskId = "person-task-" + UUID.randomUUID();
            String personResult = scheduler.submitTaskBlocking(personTaskId, "process_person_map", person);
            Log.info("[TaskID: %s] Sync Processing Person Map result: %s", personTaskId, personResult);

            String boolTaskId = "bool-task-" + UUID.randomUUID();
            String boolResult = scheduler.submitTaskBlocking(boolTaskId, "echo_bool", true, false, true);
            Log.info("[TaskID: %s] Sync Echo Boolean (multiple) result: %s", boolTaskId, boolResult);

            byte[] bytesToSend1 = "Hello Rust Bytes!".getBytes(StandardCharsets.UTF_8);
            byte[] bytesToSend2 = "More bytes?".getBytes(StandardCharsets.UTF_8);
            String bytesTaskId = "bytes-task-" + UUID.randomUUID();
            String bytesResult = scheduler.submitTaskBlocking(bytesTaskId, "echo_bytes", bytesToSend1, bytesToSend2);
            Log.info("[TaskID: %s] Sync Echo Bytes (multiple) result: %s", bytesTaskId, bytesResult);

            String stringTaskId = "string-task-" + UUID.randomUUID();
            String multiStringResult = scheduler.submitTaskBlocking(stringTaskId, "echo_string", "Sync", " ", "Test");
            Log.info("[TaskID: %s] Sync Echo String (multiple) result: %s", stringTaskId, multiStringResult);

            // --- Expected Failure Synchronous Tasks ---
            Log.info("--- Running Synchronous Tasks (Expected Failures) ---");

            List<List<String>> nestedList = Arrays.asList(
                    Arrays.asList("sync_a", "sync_b"),
                    Arrays.asList("sync_c", "sync_d", "sync_e")
            );
            String nestedListTaskId = "nested-list-task-" + UUID.randomUUID();
            try {
                String nestedListResult = scheduler.submitTaskBlocking(nestedListTaskId, "process_nested_list", nestedList);
                Log.info("[TaskID: %s] Sync Process Nested List result: %s", nestedListTaskId, nestedListResult);
            } catch (Exception exception) {
                Log.warn("[TaskID: %s] Sync Process Nested List (potentially expected) failure: %s", nestedListTaskId, exception.getMessage());
            }

            Map<String, Object> complexMap = new HashMap<>();
            complexMap.put("id", 98765L);
            complexMap.put("active", false);
            Map<String, String> metadata = new HashMap<>();
            metadata.put("source", "sync_java_client");
            metadata.put("timestamp", String.valueOf(System.currentTimeMillis()));
            complexMap.put("metadata", metadata);
            complexMap.put("tags", Arrays.asList("sync_grpc", "sync_java", "sync_rust"));
            String complexMapTaskId = "complex-map-task-" + UUID.randomUUID();
            try {
                String complexMapResult = scheduler.submitTaskBlocking(complexMapTaskId, "process_complex_map", complexMap);
                Log.info("[TaskID: %s] Sync Process Complex Map result: %s", complexMapTaskId, complexMapResult);
            } catch (Exception exception) {
                Log.warn("[TaskID: %s] Sync Process Complex Map (potentially expected) failure: %s", complexMapTaskId, exception.getMessage());
            }

            List<String> collectionList = Arrays.asList("sync_item1", "sync_item2");
            Map<String, Integer> collectionMap = Map.of("sync_key1", 10, "sync_key2", 20);
            String collectionTaskId = "collection-task-" + UUID.randomUUID();
            try {
                String collectionResult = scheduler.submitTaskBlocking(collectionTaskId, "process_collection", collectionList, collectionMap);
                Log.info("[TaskID: %s] Sync Process Collection result: %s", collectionTaskId, collectionResult);
            } catch (Exception exception) {
                Log.warn("[TaskID: %s] Sync Process Collection (potentially expected) failure: %s", collectionTaskId, exception.getMessage());
            }

            // --- Asynchronous Task Examples --- (Keep using submitTaskAsync)
            Log.info("--- Submitting Asynchronous Tasks ---");

            String fibTaskId = "fib-task-" + UUID.randomUUID();
            CompletableFuture<String> fibFuture = scheduler.submitTaskAsync(fibTaskId, "fibonacci", 12);
            fibFuture.thenAccept(fibResult ->
                    Log.info("[TaskID: %s] Async task result: fibonacci(12) = %s", fibTaskId, fibResult)
            ).exceptionally(ex -> {
                Log.error("[TaskID: %s] Async fibonacci task failed", fibTaskId, ex);
                return null;
            });
            futures.add(fibFuture);

            String deleteTaskId = "delete-task-" + UUID.randomUUID();
            CompletableFuture<String> deleteFuture = scheduler.submitTaskAsync(deleteTaskId, "delete", 1, 2, 3, 4, 5);
            deleteFuture.thenAccept(deleteResult ->
                    Log.info("[TaskID: %s] Async task result: delete(1..5) = %s", deleteTaskId, deleteResult)
            ).exceptionally(ex -> {
                Log.error("[TaskID: %s] Async delete task failed", deleteTaskId, ex);
                return null;
            });
            futures.add(deleteFuture);

            // --- Async Task with Expected Failure (e.g., negative Fibonacci) ---
            String fibFailTaskId = "fib-fail-task-" + UUID.randomUUID();
            CompletableFuture<String> fibFailFuture = scheduler.submitTaskAsync(fibFailTaskId, "fibonacci", -5);
            fibFailFuture.thenAccept(fibResult ->
                    Log.warn("[TaskID: %s] Async task fibonacci(-5) unexpectedly succeeded: %s", fibFailTaskId, fibResult)
            ).exceptionally(ex -> {
                Log.warn("[TaskID: %s] Async fibonacci(-5) task completed exceptionally (unexpected): %s", fibFailTaskId, ex.getMessage());
                return null;
            });
            futures.add(fibFailFuture);

            Log.info("Waiting for all %d async tasks (gRPC calls) to complete...", futures.size());
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            Log.info("All async tasks completed.");
        } catch (Exception exception) {
            Log.error("An error occurred during task scheduler demonstration logic", exception);
        }
    }
} 