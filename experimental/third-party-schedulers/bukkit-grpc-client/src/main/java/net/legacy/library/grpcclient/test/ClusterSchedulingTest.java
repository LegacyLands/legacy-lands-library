package net.legacy.library.grpcclient.test;

import io.grpc.Status;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.grpcclient.task.ClusterTaskSchedulerClient;
import net.legacy.library.grpcclient.task.GRPCTaskSchedulerClient;
import net.legacy.library.grpcclient.task.SchedulerNodeInfo;
import net.legacy.library.grpcclient.task.TaskResult;
import net.legacy.library.grpcclient.task.TaskSchedulerException;
import net.legacy.library.grpcclient.task.TaskStatus;
import net.legacy.library.grpcclient.task.TaskSubmission;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;

/**
 * Integration tests for multi-node task scheduling against two running scheduler nodes.
 *
 * <p>The nodes must be started as peers of each other before the tests run, for example:
 * <pre>
 * task-scheduler --addr 127.0.0.1:50051 --node-id node-a --peers http://127.0.0.1:50052
 * task-scheduler --addr 127.0.0.1:50052 --node-id node-b --peers http://127.0.0.1:50051
 * </pre>
 * Addresses can be overridden with the system properties {@code grpcclient.test.node-a}
 * and {@code grpcclient.test.node-b} in {@code host:port} form.
 *
 * <p>Task ids are randomized per run because scheduler nodes keep results and treat ids idempotently.
 *
 * @author qwq-dev
 * @since 2026-10-04 01:19
 */
@ModuleTest(
        testName = "cluster-scheduling-test",
        description = "Tests cross-node dependencies, routing, cancellation and failure propagation of the task scheduler cluster",
        tags = {"grpc", "scheduler", "cluster", "dependency"},
        priority = 1,
        timeout = 60000,
        expectedResult = "SUCCESS"
)
public class ClusterSchedulingTest {

    private static final String MODULE_NAME = "bukkit-grpc-client";
    private static final String RUN_ID = UUID.randomUUID().toString().substring(0, 8);

    private static GRPCTaskSchedulerClient nodeAClient;
    private static GRPCTaskSchedulerClient nodeBClient;
    private static ClusterTaskSchedulerClient clusterClient;
    private static String nodeAId;
    private static String nodeBId;

    /**
     * Connects to both scheduler nodes and learns their ids.
     *
     * @throws TaskSchedulerException if a node cannot be reached
     */
    public static void initialize() throws TaskSchedulerException {
        nodeAClient = createClient(System.getProperty("grpcclient.test.node-a", "127.0.0.1:50051"));
        nodeBClient = createClient(System.getProperty("grpcclient.test.node-b", "127.0.0.1:50052"));
        nodeAId = nodeAClient.getNodeInfo().getNodeId();
        nodeBId = nodeBClient.getNodeInfo().getNodeId();
        clusterClient = new ClusterTaskSchedulerClient(List.of(nodeAClient, nodeBClient));

        TestLogger.logInfo(MODULE_NAME, "Connected to scheduler nodes %s and %s (run id: %s)", nodeAId, nodeBId, RUN_ID);
    }

    /**
     * Shuts down all clients created by {@link #initialize()}.
     */
    public static void shutdown() {
        if (clusterClient != null) {
            clusterClient.shutdown();
        }
    }

    private static GRPCTaskSchedulerClient createClient(String address) throws TaskSchedulerException {
        String[] hostAndPort = address.split(":");
        return new GRPCTaskSchedulerClient(hostAndPort[0], Integer.parseInt(hostAndPort[1]), 5000, 3);
    }

    private static String taskId(String name) {
        return name + "-" + RUN_ID;
    }

    /**
     * Tests that both nodes are discovered, see each other as alive peers and expose their methods.
     */
    public static boolean testTopologyDiscovery() {
        try {
            SchedulerNodeInfo nodeAInfo = clusterClient.getNodeInfo(nodeAId).orElseThrow();
            boolean bothDiscovered = clusterClient.getNodeIds().containsAll(List.of(nodeAId, nodeBId));
            boolean methodsExposed = nodeAInfo.getMethods().contains("add");
            boolean peerAlive = nodeAInfo.getPeers().stream()
                    .anyMatch(peerInfo -> peerInfo.getNodeId().equals(nodeBId) && peerInfo.isAlive());

            TestLogger.logInfo(MODULE_NAME, "Topology discovery: bothDiscovered=%s, methodsExposed=%s, peerAlive=%s",
                    bothDiscovered, methodsExposed, peerAlive);

            return bothDiscovered && methodsExposed && peerAlive;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Topology discovery test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests a plain blocking submission and that the executing node is reported.
     */
    public static boolean testBlockingSubmission() {
        try {
            TaskResult result = nodeAClient.submit(TaskSubmission.of(taskId("blocking-add"), "add", 10, 20, 30, -5));

            boolean correctValue = "55".equals(result.getResult());
            boolean executedOnNodeA = nodeAId.equals(result.getNodeId());

            TestLogger.logInfo(MODULE_NAME, "Blocking submission: result=%s, nodeId=%s", result.getResult(), result.getNodeId());

            return result.isSuccess() && correctValue && executedOnNodeA;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Blocking submission test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests the core scenario: task A on node A depends on task B on node B and consumes its result.
     */
    public static boolean testCrossNodeDependencyWithInjection() {
        try {
            String dependencyId = taskId("cross-dep-b");
            TaskResult accepted = clusterClient.submitDetached(nodeBId,
                    TaskSubmission.of(dependencyId, "delete", 1, 2, 3));

            TaskResult result = clusterClient.submit(nodeAId, TaskSubmission.builder()
                    .taskId(taskId("cross-task-a"))
                    .method("ping")
                    .dependency(dependencyId)
                    .injectDependencyResults(true)
                    .build());

            boolean dependencyOnNodeB = nodeBId.equals(accepted.getNodeId());
            boolean dependentOnNodeA = nodeAId.equals(result.getNodeId());
            boolean resultInjected = "pong: Deleted 3 items".equals(result.getResult());

            TestLogger.logInfo(MODULE_NAME, "Cross-node dependency: dependencyNode=%s, dependentNode=%s, result=%s",
                    accepted.getNodeId(), result.getNodeId(), result.getResult());

            return dependencyOnNodeB && dependentOnNodeA && resultInjected;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Cross-node dependency test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that a dependent task waits for a dependency that is submitted later on another node.
     */
    public static boolean testDependencySubmittedLater() {
        try {
            String dependencyId = taskId("late-dep-b");
            CompletableFuture<TaskResult> dependent = nodeAClient.submitAsync(TaskSubmission.builder()
                    .taskId(taskId("late-task-a"))
                    .method("add")
                    .args(List.of(1, 1))
                    .dependency(dependencyId)
                    .dependencyTimeoutMs(10000)
                    .build());

            TimeUnit.MILLISECONDS.sleep(500);
            boolean stillWaiting = !dependent.isDone();

            nodeBClient.submit(TaskSubmission.of(dependencyId, "add", 2, 3));
            TaskResult result = dependent.get(15, TimeUnit.SECONDS);

            TestLogger.logInfo(MODULE_NAME, "Late dependency: stillWaitingBeforeSubmit=%s, result=%s", stillWaiting, result.getResult());

            return stillWaiting && result.isSuccess() && "2".equals(result.getResult());
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Late dependency test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that a failed dependency on node B fails the dependent task on node A with FAILED_PRECONDITION.
     */
    public static boolean testDependencyFailurePropagation() {
        String dependencyId = taskId("failing-dep-b");
        try {
            nodeBClient.submit(TaskSubmission.of(dependencyId, "remove", 1));
            TestLogger.logFailure(MODULE_NAME, "Dependency with invalid arguments unexpectedly succeeded");
            return false;
        } catch (TaskSchedulerException expected) {
            TestLogger.logInfo(MODULE_NAME, "Dependency failed as expected: %s", expected.getMessage());
        }

        try {
            nodeAClient.submit(TaskSubmission.builder()
                    .taskId(taskId("failing-task-a"))
                    .method("add")
                    .arg(1)
                    .dependency(dependencyId)
                    .build());
            TestLogger.logFailure(MODULE_NAME, "Dependent task unexpectedly succeeded");
            return false;
        } catch (TaskSchedulerException exception) {
            boolean preconditionFailed = exception.getStatusCode() == Status.Code.FAILED_PRECONDITION;
            boolean mentionsDependency = exception.getMessage().contains(dependencyId);

            TestLogger.logInfo(MODULE_NAME, "Dependency failure propagation: statusCode=%s, mentionsDependency=%s",
                    exception.getStatusCode(), mentionsDependency);

            return preconditionFailed && mentionsDependency;
        }
    }

    /**
     * Tests that a dependency that never appears fails the dependent task with DEADLINE_EXCEEDED.
     */
    public static boolean testDependencyTimeout() {
        long startTime = System.currentTimeMillis();
        try {
            nodeAClient.submit(TaskSubmission.builder()
                    .taskId(taskId("timeout-task-a"))
                    .method("add")
                    .arg(1)
                    .dependency(taskId("never-submitted"))
                    .dependencyTimeoutMs(500)
                    .build());
            TestLogger.logFailure(MODULE_NAME, "Task with missing dependency unexpectedly succeeded");
            return false;
        } catch (TaskSchedulerException exception) {
            long elapsedMs = System.currentTimeMillis() - startTime;
            boolean timedOut = exception.getStatusCode() == Status.Code.DEADLINE_EXCEEDED;

            TestLogger.logInfo(MODULE_NAME, "Dependency timeout: statusCode=%s, elapsed=%dms", exception.getStatusCode(), elapsedMs);

            return timedOut && elapsedMs < 5000;
        }
    }

    /**
     * Tests that a waiting task on node A can be cancelled through node B.
     */
    public static boolean testCancelThroughOtherNode() {
        try {
            String waitingId = taskId("cancel-task-a");
            nodeAClient.submitDetached(TaskSubmission.builder()
                    .taskId(waitingId)
                    .method("add")
                    .arg(1)
                    .dependency(taskId("never-arrives"))
                    .dependencyTimeoutMs(60000)
                    .build());

            boolean cancelled = nodeBClient.cancelTask(waitingId);
            TaskResult state = nodeAClient.getResult(waitingId);
            boolean cancelledAgain = nodeAClient.cancelTask(waitingId);

            TestLogger.logInfo(MODULE_NAME, "Cancellation: cancelled=%s, status=%s, cancelledAgain=%s",
                    cancelled, state.getStatus(), cancelledAgain);

            return cancelled && state.getStatus() == TaskStatus.CANCELLED && !cancelledAgain;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Cancellation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that submitting a known task id returns the first result instead of executing again.
     */
    public static boolean testIdempotentResubmission() {
        try {
            String idempotentId = taskId("idempotent");
            TaskResult first = nodeAClient.submit(TaskSubmission.of(idempotentId, "add", 1, 2));
            TaskResult second = nodeAClient.submit(TaskSubmission.of(idempotentId, "add", 100, 200));

            TestLogger.logInfo(MODULE_NAME, "Idempotency: first=%s, second=%s", first.getResult(), second.getResult());

            return "3".equals(first.getResult()) && "3".equals(second.getResult());
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Idempotency test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that async task failures complete the future exceptionally with the final task status.
     */
    public static boolean testAsyncFailureReported() {
        try {
            nodeAClient.submitAsync(TaskSubmission.of(taskId("fib-negative"), "fibonacci", -5)).get(10, TimeUnit.SECONDS);
            TestLogger.logFailure(MODULE_NAME, "Negative fibonacci unexpectedly succeeded");
            return false;
        } catch (ExecutionException exception) {
            boolean failedOnServer = exception.getCause() instanceof TaskSchedulerException schedulerException
                    && schedulerException.getTaskStatus() == TaskStatus.FAILED;

            TestLogger.logInfo(MODULE_NAME, "Async failure: cause=%s", exception.getCause().getMessage());

            return failedOnServer;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Async failure test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests cluster-wide lookup, routed submission and the NOT_FOUND state.
     */
    public static boolean testClusterLookupAndRouting() {
        try {
            String nodeBTaskId = taskId("lookup-b");
            nodeBClient.submit(TaskSubmission.of(nodeBTaskId, "ping", "lookup"));

            TaskResult found = clusterClient.getResult(nodeBTaskId);
            TaskResult missing = clusterClient.getResult(taskId("unknown"));
            TaskResult routed = clusterClient.submit(TaskSubmission.of(taskId("routed"), "add", 4, 5));

            boolean foundOnNodeB = found.isSuccess() && nodeBId.equals(found.getNodeId());
            boolean missingReported = missing.isNotFound();
            boolean routedExecuted = routed.isSuccess() && "9".equals(routed.getResult());

            TestLogger.logInfo(MODULE_NAME, "Cluster lookup: foundOnNodeB=%s, missingReported=%s, routedNode=%s",
                    foundOnNodeB, missingReported, routed.getNodeId());

            return foundOnNodeB && missingReported && routedExecuted;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Cluster lookup test failed: %s", exception.getMessage());
            return false;
        }
    }

}
