mod common;

use common::utils::{any_i32, connect_to_server, create_task_request};
use common::TestServer;
use std::fs;
use std::time::Duration;
use task_scheduler::tasks::taskscheduler::task_response::Status;
use task_scheduler::tasks::taskscheduler::task_scheduler_client::TaskSchedulerClient;
use task_scheduler::tasks::taskscheduler::{
    CancelRequest, NodeInfoRequest, ResultRequest, TaskRequest, WaitResultRequest,
};
use tonic::transport::Channel;
use tonic::Request;

const NODE_A: &str = "node-a";
const NODE_B: &str = "node-b";

struct TwoNodeCluster {
    _node_a: TestServer,
    _node_b: TestServer,
    client_a: TaskSchedulerClient<Channel>,
    client_b: TaskSchedulerClient<Channel>,
}

/// Starts two peered nodes. `library_dir_a` / `library_dir_b` override the plugin directories.
async fn start_cluster(library_dir_a: Option<&str>, library_dir_b: Option<&str>) -> TwoNodeCluster {
    let port_a = portpicker::pick_unused_port().expect("No free port for node A");
    let port_b = portpicker::pick_unused_port().expect("No free port for node B");

    let node_args = |node_id: &str, peer_port: u16, library_dir: Option<&str>| {
        let mut args = vec![
            "--node-id".to_string(),
            node_id.to_string(),
            "--peers".to_string(),
            format!("http://127.0.0.1:{}", peer_port),
            "--heartbeat-interval-ms".to_string(),
            "200".to_string(),
        ];
        if let Some(dir) = library_dir {
            args.push("--library-dir".to_string());
            args.push(dir.to_string());
        }
        args
    };

    let node_a = common::spawn_with_args(port_a, &node_args(NODE_A, port_b, library_dir_a));
    let node_b = common::spawn_with_args(port_b, &node_args(NODE_B, port_a, library_dir_b));

    // Give both nodes time to start and exchange a few heartbeats
    tokio::time::sleep(Duration::from_millis(1500)).await;

    let client_a = connect_to_server(&node_a.address()).await;
    let client_b = connect_to_server(&node_b.address()).await;

    TwoNodeCluster {
        _node_a: node_a,
        _node_b: node_b,
        client_a,
        client_b,
    }
}

fn detached(mut request: TaskRequest) -> TaskRequest {
    request.detached = true;
    request
}

async fn wait_result(
    client: &mut TaskSchedulerClient<Channel>,
    task_id: &str,
    timeout_ms: u64,
) -> task_scheduler::tasks::taskscheduler::ResultResponse {
    client
        .wait_result(Request::new(WaitResultRequest {
            task_id: task_id.to_string(),
            timeout_ms,
        }))
        .await
        .expect("WaitResult failed")
        .into_inner()
}

#[tokio::test]
async fn test_cross_node_dependency_with_result_injection() {
    let mut cluster = start_cluster(None, None).await;

    // Dependency B runs on node B
    let dependency = detached(create_task_request(
        "xnode_dep_b",
        "delete",
        vec![any_i32(1), any_i32(2), any_i32(3)],
        vec![],
        false,
    ));
    let accepted = cluster
        .client_b
        .submit_task(Request::new(dependency))
        .await
        .expect("Failed to submit dependency on node B")
        .into_inner();
    assert_eq!(accepted.node_id, NODE_B);
    assert_ne!(
        accepted.status,
        Status::Success as i32,
        "Detached submit should not wait"
    );

    // Task A runs on node A and consumes the result of B
    let mut dependent = create_task_request(
        "xnode_task_a",
        "ping",
        vec![],
        vec!["xnode_dep_b".to_string()],
        false,
    );
    dependent.inject_dependency_results = true;
    let response = cluster
        .client_a
        .submit_task(Request::new(dependent))
        .await
        .expect("Dependent task on node A should succeed")
        .into_inner();

    assert_eq!(response.status, Status::Success as i32);
    assert_eq!(response.node_id, NODE_A);
    assert_eq!(response.result, "pong: Deleted 3 items");
}

#[tokio::test]
async fn test_dependency_submitted_after_dependent() {
    let mut cluster = start_cluster(None, None).await;

    let dependent = detached(create_task_request(
        "late_task_a",
        "add",
        vec![any_i32(1), any_i32(1)],
        vec!["late_dep_b".to_string()],
        false,
    ));
    let accepted = cluster
        .client_a
        .submit_task(Request::new(dependent))
        .await
        .expect("Failed to submit dependent task")
        .into_inner();
    assert_eq!(accepted.status, Status::Pending as i32);

    // The dependency does not exist anywhere yet, the dependent must keep waiting
    tokio::time::sleep(Duration::from_millis(600)).await;
    let still_waiting = cluster
        .client_a
        .get_result(Request::new(ResultRequest {
            task_id: "late_task_a".to_string(),
        }))
        .await
        .expect("GetResult failed")
        .into_inner();
    assert_eq!(still_waiting.status, Status::Pending as i32);

    cluster
        .client_b
        .submit_task(Request::new(create_task_request(
            "late_dep_b",
            "add",
            vec![any_i32(2), any_i32(3)],
            vec![],
            false,
        )))
        .await
        .expect("Failed to submit late dependency");

    let result = wait_result(&mut cluster.client_a, "late_task_a", 10_000).await;
    assert_eq!(result.status, Status::Success as i32);
    assert_eq!(result.result, "2");
}

#[tokio::test]
async fn test_remote_dependency_failure_propagates() {
    let mut cluster = start_cluster(None, None).await;

    // "remove" with a single argument fails on node B
    let failing = create_task_request("failing_dep_b", "remove", vec![any_i32(1)], vec![], false);
    let failure = cluster.client_b.submit_task(Request::new(failing)).await;
    assert!(failure.is_err(), "Dependency itself should fail");

    let dependent = create_task_request(
        "failing_task_a",
        "add",
        vec![any_i32(1)],
        vec!["failing_dep_b".to_string()],
        false,
    );
    let status = cluster
        .client_a
        .submit_task(Request::new(dependent))
        .await
        .expect_err("Dependent task must fail when its dependency failed");
    assert_eq!(status.code(), tonic::Code::FailedPrecondition);
    assert!(status.message().contains("failing_dep_b"));
    assert!(status.message().contains(NODE_B));

    let recorded = cluster
        .client_a
        .get_result(Request::new(ResultRequest {
            task_id: "failing_task_a".to_string(),
        }))
        .await
        .expect("GetResult failed")
        .into_inner();
    assert_eq!(recorded.status, Status::Failed as i32);
}

#[tokio::test]
async fn test_dependency_timeout() {
    let mut cluster = start_cluster(None, None).await;

    let mut dependent = create_task_request(
        "timeout_task_a",
        "add",
        vec![any_i32(1)],
        vec!["never_submitted".to_string()],
        false,
    );
    dependent.dependency_timeout_ms = 500;

    let started = std::time::Instant::now();
    let status = cluster
        .client_a
        .submit_task(Request::new(dependent))
        .await
        .expect_err("Missing dependency must time out");
    assert_eq!(status.code(), tonic::Code::DeadlineExceeded);
    assert!(started.elapsed() < Duration::from_secs(5));
}

#[tokio::test]
async fn test_self_dependency_rejected() {
    let mut cluster = start_cluster(None, None).await;

    let task = create_task_request(
        "self_dep",
        "add",
        vec![any_i32(1)],
        vec!["self_dep".to_string()],
        false,
    );
    let status = cluster
        .client_a
        .submit_task(Request::new(task))
        .await
        .expect_err("Self dependency must be rejected");
    assert_eq!(status.code(), tonic::Code::InvalidArgument);
}

#[tokio::test]
async fn test_forwarding_to_node_with_method() {
    // Only node B loads the example plugin
    let empty_dir = "./cluster_empty_libs";
    let _ = fs::remove_dir_all(empty_dir);
    fs::create_dir_all(empty_dir).expect("Failed to create empty library directory");

    let mut cluster = start_cluster(Some(empty_dir), None).await;

    let task = create_task_request(
        "forward_multiply",
        "plugin_example::multiply",
        vec![any_i32(6), any_i32(7)],
        vec![],
        false,
    );
    let response = cluster
        .client_a
        .submit_task(Request::new(task))
        .await
        .expect("Task should be forwarded to node B")
        .into_inner();
    assert_eq!(response.result, "42");
    assert_eq!(response.node_id, NODE_B);

    // Node A proxies queries for the forwarded task
    let proxied = cluster
        .client_a
        .get_result(Request::new(ResultRequest {
            task_id: "forward_multiply".to_string(),
        }))
        .await
        .expect("GetResult failed")
        .into_inner();
    assert_eq!(proxied.status, Status::Success as i32);
    assert_eq!(proxied.node_id, NODE_B);

    // Forwarding can be disabled per request
    let mut local_only = create_task_request(
        "forward_disabled",
        "plugin_example::multiply",
        vec![any_i32(1)],
        vec![],
        false,
    );
    local_only.disable_forwarding = true;
    let status = cluster
        .client_a
        .submit_task(Request::new(local_only))
        .await
        .expect_err("Disabled forwarding must fail");
    assert_eq!(status.code(), tonic::Code::NotFound);

    let _ = fs::remove_dir_all(empty_dir);
}

#[tokio::test]
async fn test_cancel_waiting_task_from_other_node() {
    let mut cluster = start_cluster(None, None).await;

    let mut waiting = detached(create_task_request(
        "cancel_task_a",
        "add",
        vec![any_i32(1)],
        vec!["never_arrives".to_string()],
        false,
    ));
    waiting.dependency_timeout_ms = 60_000;
    cluster
        .client_a
        .submit_task(Request::new(waiting))
        .await
        .expect("Failed to submit waiting task");

    // Cancel through node B, which has to locate the task on node A
    let response = cluster
        .client_b
        .cancel_task(Request::new(CancelRequest {
            task_id: "cancel_task_a".to_string(),
        }))
        .await
        .expect("CancelTask failed")
        .into_inner();
    assert!(response.cancelled);
    assert_eq!(response.status, Status::Cancelled as i32);

    let second = cluster
        .client_a
        .cancel_task(Request::new(CancelRequest {
            task_id: "cancel_task_a".to_string(),
        }))
        .await
        .expect("CancelTask failed")
        .into_inner();
    assert!(
        !second.cancelled,
        "Terminal tasks cannot be cancelled again"
    );
}

#[tokio::test]
async fn test_idempotent_resubmission() {
    let mut cluster = start_cluster(None, None).await;

    let first = cluster
        .client_a
        .submit_task(Request::new(create_task_request(
            "idempotent_task",
            "add",
            vec![any_i32(1), any_i32(2)],
            vec![],
            false,
        )))
        .await
        .expect("First submit failed")
        .into_inner();

    let second = cluster
        .client_a
        .submit_task(Request::new(create_task_request(
            "idempotent_task",
            "add",
            vec![any_i32(100), any_i32(200)],
            vec![],
            false,
        )))
        .await
        .expect("Second submit failed")
        .into_inner();

    assert_eq!(first.result, "3");
    assert_eq!(
        second.result, "3",
        "Resubmission must reuse the first result"
    );
}

#[tokio::test]
async fn test_node_info_and_not_found() {
    let mut cluster = start_cluster(None, None).await;

    let info = cluster
        .client_a
        .get_node_info(Request::new(NodeInfoRequest {}))
        .await
        .expect("GetNodeInfo failed")
        .into_inner();
    assert_eq!(info.node_id, NODE_A);
    assert!(info.methods.iter().any(|method| method == "add"));
    assert_eq!(info.peers.len(), 1);
    assert!(info.peers[0].alive, "Peer should be alive after heartbeats");
    assert_eq!(info.peers[0].node_id, NODE_B);

    let unknown = cluster
        .client_b
        .get_result(Request::new(ResultRequest {
            task_id: "does_not_exist".to_string(),
        }))
        .await
        .expect("GetResult failed")
        .into_inner();
    assert_eq!(unknown.status, Status::NotFound as i32);
}

#[tokio::test]
async fn test_execution_timeout_and_async_failure() {
    let mut cluster = start_cluster(None, None).await;

    // "delete" sleeps 100ms, a 10ms limit must time out
    let mut slow = create_task_request("exec_timeout", "delete", vec![any_i32(1)], vec![], false);
    slow.execution_timeout_ms = 10;
    let status = cluster
        .client_a
        .submit_task(Request::new(slow))
        .await
        .expect_err("Execution timeout must fail the task");
    assert_eq!(status.code(), tonic::Code::DeadlineExceeded);

    // Async tasks can now report failures instead of returning error strings
    let negative = create_task_request(
        "fib_negative",
        "fibonacci",
        vec![any_i32(-5)],
        vec![],
        false,
    );
    let status = cluster
        .client_a
        .submit_task(Request::new(negative))
        .await
        .expect_err("Negative fibonacci must fail");
    assert_eq!(status.code(), tonic::Code::InvalidArgument);
}
