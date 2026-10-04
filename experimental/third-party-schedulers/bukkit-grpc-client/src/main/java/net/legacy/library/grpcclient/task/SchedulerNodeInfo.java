package net.legacy.library.grpcclient.task;

import lombok.Value;
import taskscheduler.TaskSchedulerOuterClass.NodeInfo;

import java.util.List;

/**
 * Description of a scheduler node: its identity, registered methods, load and known peers.
 *
 * @author qwq-dev
 * @since 2026-10-04 12:51
 */
@Value
public class SchedulerNodeInfo {

    /**
     * The cluster-wide unique id of the node.
     */
    String nodeId;

    /**
     * The task methods registered on the node, sorted by name.
     */
    List<String> methods;

    /**
     * The number of local tasks waiting for dependencies or an execution slot.
     */
    int pendingTasks;

    /**
     * The number of local tasks currently executing.
     */
    int runningTasks;

    /**
     * The peers configured on the node.
     */
    List<Peer> peers;

    /**
     * Converts the Protobuf node information.
     *
     * @param nodeInfo the response of {@code GetNodeInfo}
     * @return the converted node information
     */
    static SchedulerNodeInfo fromProto(NodeInfo nodeInfo) {
        List<Peer> peers = nodeInfo.getPeersList().stream()
                .map(peerInfo -> new Peer(peerInfo.getNodeId(), peerInfo.getAddress(), peerInfo.getAlive()))
                .toList();
        return new SchedulerNodeInfo(nodeInfo.getNodeId(), List.copyOf(nodeInfo.getMethodsList()),
                nodeInfo.getPendingTasks(), nodeInfo.getRunningTasks(), peers);
    }

    /**
     * Returns the total number of local tasks that are not finished yet.
     *
     * @return the sum of pending and running tasks
     */
    public int getLoad() {
        return pendingTasks + runningTasks;
    }

    /**
     * A peer as seen by the node.
     */
    @Value
    public static class Peer {

        /**
         * The id of the peer, empty if the peer has never been reached.
         */
        String nodeId;

        /**
         * The configured address of the peer.
         */
        String address;

        /**
         * Whether the last heartbeat to the peer succeeded.
         */
        boolean alive;

    }

}
