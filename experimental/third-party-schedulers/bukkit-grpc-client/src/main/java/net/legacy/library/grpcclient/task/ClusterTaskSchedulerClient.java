package net.legacy.library.grpcclient.task;

import io.fairyproject.log.Log;
import org.apache.commons.lang3.Validate;

import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * A client for a cluster of Task Scheduler nodes.
 *
 * <p>Scheduler nodes resolve dependencies across the cluster on their own, so a task submitted to one node
 * may depend on tasks executed on any other node. This client adds node addressing on top of that:
 * tasks can be submitted to a specific node by its id ({@link #submit(String, TaskSubmission)}), or routed
 * to the least loaded node that provides the method ({@link #submit(TaskSubmission)}).
 *
 * <p>The topology (node ids, methods and load) is learned through {@code GetNodeInfo} and refreshed by
 * {@link #refreshTopology()}. Load figures are only as fresh as the last refresh.
 *
 * <p>This class is thread-safe. It owns the given node clients and shuts them down in {@link #shutdown()}.
 *
 * @author qwq-dev
 * @since 2026-10-04 01:18
 */
public class ClusterTaskSchedulerClient {

    private final List<GRPCTaskSchedulerClient> clients;
    private final Map<String, GRPCTaskSchedulerClient> clientsByNodeId = new ConcurrentHashMap<>();
    private final Map<String, SchedulerNodeInfo> nodeInfos = new ConcurrentHashMap<>();

    /**
     * Creates a cluster client and performs an initial topology refresh.
     * Unreachable nodes are logged and ignored until a later refresh reaches them.
     *
     * @param clients the clients of the scheduler nodes, must not be empty
     * @throws IllegalArgumentException if {@code clients} is empty
     */
    public ClusterTaskSchedulerClient(List<GRPCTaskSchedulerClient> clients) {
        Validate.notEmpty(clients, "At least one scheduler client is required.");
        this.clients = List.copyOf(clients);
        refreshTopology();
    }

    /**
     * Queries every node for its id, methods and load, replacing the known topology.
     *
     * @return the number of reachable nodes
     */
    public int refreshTopology() {
        Map<String, GRPCTaskSchedulerClient> refreshedClients = new ConcurrentHashMap<>();
        Map<String, SchedulerNodeInfo> refreshedInfos = new ConcurrentHashMap<>();

        clients.forEach(client -> {
            try {
                SchedulerNodeInfo nodeInfo = client.getNodeInfo();
                GRPCTaskSchedulerClient duplicate = refreshedClients.putIfAbsent(nodeInfo.getNodeId(), client);
                if (duplicate != null) {
                    Log.warn("Scheduler nodes %s:%d and %s:%d report the same node id %s, only the first one is addressable. Node ids must be unique (--node-id).",
                            duplicate.getHost(), duplicate.getPort(), client.getHost(), client.getPort(), nodeInfo.getNodeId());
                    return;
                }
                refreshedInfos.put(nodeInfo.getNodeId(), nodeInfo);
            } catch (TaskSchedulerException exception) {
                Log.warn("Scheduler node %s:%d is unreachable: %s", client.getHost(), client.getPort(), exception.getMessage());
            }
        });

        clientsByNodeId.keySet().retainAll(refreshedClients.keySet());
        clientsByNodeId.putAll(refreshedClients);
        nodeInfos.keySet().retainAll(refreshedInfos.keySet());
        nodeInfos.putAll(refreshedInfos);
        return refreshedClients.size();
    }

    /**
     * Returns the ids of all nodes reached by the last topology refresh.
     *
     * @return an immutable set of node ids
     */
    public Set<String> getNodeIds() {
        return Set.copyOf(clientsByNodeId.keySet());
    }

    /**
     * Returns the information of a node as of the last topology refresh.
     *
     * @param nodeId the id of the node
     * @return the node information, or empty if the node is unknown
     */
    public Optional<SchedulerNodeInfo> getNodeInfo(String nodeId) {
        return Optional.ofNullable(nodeInfos.get(nodeId));
    }

    /**
     * Returns the client connected to a node.
     *
     * @param nodeId the id of the node
     * @return the client, or empty if the node is unknown
     */
    public Optional<GRPCTaskSchedulerClient> getClient(String nodeId) {
        return Optional.ofNullable(clientsByNodeId.get(nodeId));
    }

    /**
     * Submits a task to a specific node and blocks until it finishes.
     *
     * @param nodeId     the id of the node that receives the task
     * @param submission the task to submit
     * @return the successful result of the task
     * @throws TaskSchedulerException   if the submission fails or the task does not succeed
     * @throws IllegalArgumentException if the node is unknown
     * @see GRPCTaskSchedulerClient#submit(TaskSubmission)
     */
    public TaskResult submit(String nodeId, TaskSubmission submission) throws TaskSchedulerException {
        return requireClient(nodeId).submit(submission);
    }

    /**
     * Submits a task to a specific node asynchronously.
     *
     * @param nodeId     the id of the node that receives the task
     * @param submission the task to submit
     * @return a future completed with the successful result of the task
     * @throws IllegalArgumentException if the node is unknown
     * @see GRPCTaskSchedulerClient#submitAsync(TaskSubmission)
     */
    public CompletableFuture<TaskResult> submitAsync(String nodeId, TaskSubmission submission) {
        return requireClient(nodeId).submitAsync(submission);
    }

    /**
     * Submits a task to a specific node without waiting for it.
     *
     * @param nodeId     the id of the node that receives the task
     * @param submission the task to submit
     * @return the state of the task right after submission
     * @throws TaskSchedulerException   if the submission is rejected
     * @throws IllegalArgumentException if the node is unknown
     * @see GRPCTaskSchedulerClient#submitDetached(TaskSubmission)
     */
    public TaskResult submitDetached(String nodeId, TaskSubmission submission) throws TaskSchedulerException {
        return requireClient(nodeId).submitDetached(submission);
    }

    /**
     * Submits a task to the least loaded node providing its method and blocks until it finishes.
     *
     * @param submission the task to submit
     * @return the successful result of the task
     * @throws TaskSchedulerException if the submission fails or the task does not succeed
     * @throws IllegalStateException  if no node is reachable
     */
    public TaskResult submit(TaskSubmission submission) throws TaskSchedulerException {
        return selectClient(submission.getMethod()).submit(submission);
    }

    /**
     * Submits a task to the least loaded node providing its method asynchronously.
     *
     * @param submission the task to submit
     * @return a future completed with the successful result of the task
     * @throws IllegalStateException if no node is reachable
     */
    public CompletableFuture<TaskResult> submitAsync(TaskSubmission submission) {
        return selectClient(submission.getMethod()).submitAsync(submission);
    }

    /**
     * Finds a task on any node and returns its current state.
     *
     * @param taskId the id of the task
     * @return the state reported by the first node knowing the task, or a {@code NOT_FOUND} result
     */
    public TaskResult getResult(String taskId) {
        return clientsByNodeId.values().stream()
                .map(client -> {
                    try {
                        return client.getResult(taskId);
                    } catch (TaskSchedulerException exception) {
                        Log.warn("Failed to query task %s on %s:%d: %s", taskId, client.getHost(), client.getPort(), exception.getMessage());
                        return null;
                    }
                })
                .filter(result -> result != null && !result.isNotFound())
                .findFirst()
                .orElseGet(() -> new TaskResult(taskId, TaskStatus.NOT_FOUND, "", ""));
    }

    /**
     * Cancels a task wherever it runs. Any reachable node can locate the owner, so the first
     * node that answers decides the outcome.
     *
     * @param taskId the id of the task
     * @return {@code true} if the task was cancelled, {@code false} if it was unknown or already finished
     * @throws TaskSchedulerException if no node could process the request
     */
    public boolean cancelTask(String taskId) throws TaskSchedulerException {
        TaskSchedulerException lastException = null;
        for (GRPCTaskSchedulerClient client : clientsByNodeId.values()) {
            try {
                return client.cancelTask(taskId);
            } catch (TaskSchedulerException exception) {
                lastException = exception;
            }
        }
        throw new TaskSchedulerException("No scheduler node could cancel task " + taskId, lastException);
    }

    /**
     * Shuts down all node clients.
     */
    public void shutdown() {
        clients.forEach(GRPCTaskSchedulerClient::shutdown);
    }

    private GRPCTaskSchedulerClient requireClient(String nodeId) {
        GRPCTaskSchedulerClient client = clientsByNodeId.get(nodeId);
        Validate.isTrue(client != null, "Unknown scheduler node: %s, known nodes: %s", nodeId, clientsByNodeId.keySet());
        return client;
    }

    /**
     * Picks the least loaded node providing {@code method}, falling back to any reachable node,
     * which then forwards the task to a capable peer.
     */
    private GRPCTaskSchedulerClient selectClient(String method) {
        if (clientsByNodeId.isEmpty()) {
            refreshTopology();
        }

        return nodeInfos.values().stream()
                .filter(nodeInfo -> nodeInfo.getMethods().contains(method))
                .min(Comparator.comparingInt(SchedulerNodeInfo::getLoad))
                .map(nodeInfo -> clientsByNodeId.get(nodeInfo.getNodeId()))
                .or(() -> clientsByNodeId.values().stream().findFirst())
                .orElseThrow(() -> new IllegalStateException("No reachable scheduler node"));
    }

}
