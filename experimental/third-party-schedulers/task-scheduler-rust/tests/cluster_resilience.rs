mod common;

use common::utils::{any_i32, any_string, create_task_request};
use common::TestServer;
use std::fs;
use std::net::TcpListener;
use std::time::{Duration, Instant};
use task_scheduler::tasks::taskscheduler::task_response::Status;
use task_scheduler::tasks::taskscheduler::task_scheduler_client::TaskSchedulerClient;
use task_scheduler::tasks::taskscheduler::{
    NodeInfo, NodeInfoRequest, ResultRequest, ResultResponse, TaskRequest, WaitResultRequest,
};
use tonic::transport::Channel;
use tonic::Request;

fn node_args(node_id: &str, peer_ports: &[u16], extra: &[&str]) -> Vec<String> {
    let mut args = vec![
        "--node-id".to_string(),
        node_id.to_string(),
        "--heartbeat-interval-ms".to_string(),
        "200".to_string(),
    ];
    if !extra.contains(&"--peer-timeout-ms") {
        args.push("--peer-timeout-ms".to_string());
        args.push("2000".to_string());
    }
    if !peer_ports.is_empty() {
        args.push("--peers".to_string());
        args.push(
            peer_ports
                .iter()
                .map(|port| format!("http://127.0.0.1:{}", port))
                .collect::<Vec<_>>()
                .join(","),
        );
    }
    args.extend(extra.iter().map(|arg| arg.to_string()));
    args
}

fn free_port() -> u16 {
    portpicker::pick_unused_port().expect("No free port")
}

/// Retries connecting until the freshly spawned node accepts connections.
async fn connect_when_ready(server: &TestServer) -> TaskSchedulerClient<Channel> {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        if let Ok(channel) = tonic::transport::Channel::from_shared(server.address())
            .unwrap()
            .connect()
            .await
        {
            return TaskSchedulerClient::new(channel);
        }
        assert!(Instant::now() < deadline, "Node did not start in time");
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

/// Waits until the node sees all its peers alive.
async fn wait_peers_alive(client: &mut TaskSchedulerClient<Channel>) -> NodeInfo {
    let deadline = Instant::now() + Duration::from_secs(10);
    loop {
        let info = client
            .get_node_info(Request::new(NodeInfoRequest {}))
            .await
            .expect("GetNodeInfo failed")
            .into_inner();
        if info.peers.iter().all(|peer| peer.alive) {
            return info;
        }
        assert!(
            Instant::now() < deadline,
            "Peers did not become alive in time"
        );
        tokio::time::sleep(Duration::from_millis(100)).await;
    }
}

fn detached(mut request: TaskRequest) -> TaskRequest {
    request.detached = true;
    request
}

async fn wait_terminal(
    client: &mut TaskSchedulerClient<Channel>,
    task_id: &str,
    max_wait: Duration,
) -> ResultResponse {
    let deadline = Instant::now() + max_wait;
    loop {
        let response = client
            .wait_result(Request::new(WaitResultRequest {
                task_id: task_id.to_string(),
                timeout_ms: 2000,
            }))
            .await
            .expect("WaitResult failed")
            .into_inner();
        let terminal = [
            Status::Success as i32,
            Status::Failed as i32,
            Status::Cancelled as i32,
        ];
        if terminal.contains(&response.status) || Instant::now() >= deadline {
            return response;
        }
    }
}

async fn start_pair(extra_a: &[&str], extra_b: &[&str]) -> (TestServer, TestServer, u16, u16) {
    let port_a = free_port();
    let port_b = free_port();
    let node_a = common::spawn_with_args(port_a, &node_args("node-a", &[port_b], extra_a));
    let node_b = common::spawn_with_args(port_b, &node_args("node-b", &[port_a], extra_b));
    (node_a, node_b, port_a, port_b)
}

#[tokio::test]
async fn test_alternating_dependency_chain_submitted_in_reverse() {
    let (node_a, node_b, _, _) = start_pair(&[], &[]).await;
    let mut client_a = connect_when_ready(&node_a).await;
    let mut client_b = connect_when_ready(&node_b).await;
    wait_peers_alive(&mut client_a).await;
    wait_peers_alive(&mut client_b).await;

    const LENGTH: usize = 20;
    // Submit the tail first so every task has to wait for a dependency that does not exist yet
    for index in (0..LENGTH).rev() {
        let deps = if index == 0 {
            vec![]
        } else {
            vec![format!("chain_{}", index - 1)]
        };
        let mut request = detached(create_task_request(
            &format!("chain_{}", index),
            "ping",
            vec![],
            deps,
            false,
        ));
        request.inject_dependency_results = true;
        let client = if index % 2 == 0 {
            &mut client_a
        } else {
            &mut client_b
        };
        client
            .submit_task(Request::new(request))
            .await
            .expect("Failed to submit chain task");
    }

    let last = format!("chain_{}", LENGTH - 1);
    // The last index is odd, so it lives on node B
    let result = wait_terminal(&mut client_b, &last, Duration::from_secs(30)).await;
    assert_eq!(result.status, Status::Success as i32, "{}", result.result);
    assert_eq!(result.node_id, "node-b");
    let expected = format!("{}pong", "pong: ".repeat(LENGTH - 1));
    assert_eq!(result.result, expected);
}

#[tokio::test]
async fn test_fan_in_from_both_nodes_keeps_dependency_order() {
    let (node_a, node_b, _, _) = start_pair(&[], &[]).await;
    let mut client_a = connect_when_ready(&node_a).await;
    let mut client_b = connect_when_ready(&node_b).await;
    wait_peers_alive(&mut client_a).await;

    const WIDTH: usize = 40;
    let deps: Vec<String> = (0..WIDTH).map(|index| format!("fan_{}", index)).collect();

    // The aggregator is submitted before any of its dependencies
    let mut aggregator = detached(create_task_request(
        "fan_aggregator",
        "echo_string",
        vec![],
        deps.clone(),
        false,
    ));
    aggregator.inject_dependency_results = true;
    client_a
        .submit_task(Request::new(aggregator))
        .await
        .expect("Failed to submit aggregator");

    for (index, dep) in deps.iter().enumerate() {
        let request = detached(create_task_request(
            dep,
            "ping",
            vec![any_string(&index.to_string())],
            vec![],
            false,
        ));
        let client = if index % 2 == 0 {
            &mut client_a
        } else {
            &mut client_b
        };
        client
            .submit_task(Request::new(request))
            .await
            .expect("Failed to submit fan-in dependency");
    }

    let result = wait_terminal(&mut client_a, "fan_aggregator", Duration::from_secs(30)).await;
    assert_eq!(result.status, Status::Success as i32, "{}", result.result);
    let expected = (0..WIDTH)
        .map(|index| format!("pong: {}", index))
        .collect::<Vec<_>>()
        .join(",");
    assert_eq!(result.result, expected);
}

#[tokio::test]
async fn test_concurrent_cross_node_load() {
    let (node_a, node_b, _, _) = start_pair(&[], &[]).await;
    let mut client_a = connect_when_ready(&node_a).await;
    let client_b = connect_when_ready(&node_b).await;
    wait_peers_alive(&mut client_a).await;

    const TASKS: i32 = 200;
    let started = Instant::now();
    let handles: Vec<_> = (0..TASKS)
        .map(|index| {
            let mut dependency_client = if index % 2 == 0 {
                client_b.clone()
            } else {
                client_a.clone()
            };
            let mut dependent_client = if index % 2 == 0 {
                client_a.clone()
            } else {
                client_b.clone()
            };
            tokio::spawn(async move {
                let dep_id = format!("load_dep_{}", index);
                let mut dependent = create_task_request(
                    &format!("load_task_{}", index),
                    "add",
                    vec![any_i32(index)],
                    vec![dep_id.clone()],
                    false,
                );
                dependent.dependency_timeout_ms = 30_000;

                // Dependent and dependency race each other on different nodes
                let dependent_call = dependent_client.submit_task(Request::new(dependent));
                let dependency_call = dependency_client.submit_task(Request::new(
                    create_task_request(&dep_id, "add", vec![any_i32(1)], vec![], false),
                ));
                let (dependent_result, dependency_result) =
                    tokio::join!(dependent_call, dependency_call);
                dependency_result.expect("Dependency failed");
                let response = dependent_result.expect("Dependent failed").into_inner();
                assert_eq!(response.result, index.to_string());
            })
        })
        .collect();

    for handle in handles {
        handle.await.expect("Load task panicked");
    }
    let elapsed = started.elapsed();
    println!(
        "{} concurrent cross-node task pairs took {:?}",
        TASKS, elapsed
    );
    assert!(elapsed < Duration::from_secs(30));

    let info = client_a
        .get_node_info(Request::new(NodeInfoRequest {}))
        .await
        .expect("GetNodeInfo failed")
        .into_inner();
    assert_eq!(info.pending_tasks, 0);
    assert_eq!(info.running_tasks, 0);
}

#[tokio::test]
async fn test_peer_crash_and_recovery() {
    let (node_a, node_b, _, port_b) = start_pair(&[], &[]).await;
    let mut client_a = connect_when_ready(&node_a).await;
    let mut client_b = connect_when_ready(&node_b).await;
    wait_peers_alive(&mut client_a).await;

    // A task on node A waits for a dependency that only node B will ever know
    let mut waiting = detached(create_task_request(
        "crash_waiting",
        "add",
        vec![any_i32(1)],
        vec!["crash_dep".to_string()],
        false,
    ));
    waiting.dependency_timeout_ms = 2000;
    let submitted_at = Instant::now();
    client_a
        .submit_task(Request::new(waiting))
        .await
        .expect("Failed to submit waiting task");

    client_b
        .submit_task(Request::new(detached(create_task_request(
            "crash_unrelated",
            "add",
            vec![any_i32(1)],
            vec!["never".to_string()],
            false,
        ))))
        .await
        .expect("Failed to submit on node B");

    drop(client_b);
    drop(node_b);

    // The waiting task must fail on its own deadline although its peer is gone
    let result = wait_terminal(&mut client_a, "crash_waiting", Duration::from_secs(10)).await;
    let elapsed = submitted_at.elapsed();
    assert_eq!(result.status, Status::Failed as i32);
    assert!(
        result.result.contains("did not complete in time"),
        "{}",
        result.result
    );
    assert!(
        elapsed < Duration::from_millis(4000),
        "Dependency deadline overshot: {:?}",
        elapsed
    );

    // Node A keeps serving local work while its peer is down
    let local = client_a
        .submit_task(Request::new(create_task_request(
            "crash_local",
            "add",
            vec![any_i32(2), any_i32(3)],
            vec![],
            false,
        )))
        .await
        .expect("Node A must keep working")
        .into_inner();
    assert_eq!(local.result, "5");

    let info = client_a
        .get_node_info(Request::new(NodeInfoRequest {}))
        .await
        .expect("GetNodeInfo failed")
        .into_inner();
    assert!(
        !info.peers[0].alive,
        "Crashed peer must be reported as down"
    );

    // Restart node B on the same port, the cluster must heal through heartbeats
    let port_a = node_a.port();
    let restarted_b = common::spawn_with_args(port_b, &node_args("node-b", &[port_a], &[]));
    let mut client_b = connect_when_ready(&restarted_b).await;
    wait_peers_alive(&mut client_a).await;
    wait_peers_alive(&mut client_b).await;

    client_b
        .submit_task(Request::new(create_task_request(
            "recovered_dep",
            "add",
            vec![any_i32(10)],
            vec![],
            false,
        )))
        .await
        .expect("Restarted node B must accept tasks");
    let mut dependent = create_task_request(
        "recovered_task",
        "ping",
        vec![],
        vec!["recovered_dep".to_string()],
        false,
    );
    dependent.inject_dependency_results = true;
    let response = client_a
        .submit_task(Request::new(dependent))
        .await
        .expect("Cross-node dependency must work after recovery")
        .into_inner();
    assert_eq!(response.result, "pong: 10");
}

#[tokio::test]
async fn test_resubmission_after_failed_forward() {
    let empty_dir = "./resilience_empty_libs";
    let _ = fs::remove_dir_all(empty_dir);
    fs::create_dir_all(empty_dir).expect("Failed to create empty library directory");

    let (node_a, node_b, port_a, port_b) = start_pair(&["--library-dir", empty_dir], &[]).await;
    let mut client_a = connect_when_ready(&node_a).await;
    wait_peers_alive(&mut client_a).await;

    // Node B owns the plugin method and goes down right before the forward
    drop(node_b);
    let request = create_task_request(
        "forward_retry",
        "plugin_example::multiply",
        vec![any_i32(6), any_i32(7)],
        vec![],
        false,
    );
    let failure = client_a.submit_task(Request::new(request.clone())).await;
    assert!(failure.is_err(), "Forwarding to a dead peer must fail");

    // No stale record may be left behind on node A
    let state = client_a
        .get_result(Request::new(ResultRequest {
            task_id: "forward_retry".to_string(),
        }))
        .await
        .expect("GetResult failed")
        .into_inner();
    assert_eq!(state.status, Status::NotFound as i32);

    let restarted_b = common::spawn_with_args(port_b, &node_args("node-b", &[port_a], &[]));
    let _client_b = connect_when_ready(&restarted_b).await;
    wait_peers_alive(&mut client_a).await;

    let response = client_a
        .submit_task(Request::new(request))
        .await
        .expect("Resubmission must be forwarded again")
        .into_inner();
    assert_eq!(response.result, "42");
    assert_eq!(response.node_id, "node-b");

    let _ = fs::remove_dir_all(empty_dir);
}

#[tokio::test]
async fn test_dependency_deadline_with_unresponsive_peer() {
    // A peer that accepts connections but never answers
    let black_hole = TcpListener::bind("127.0.0.1:0").expect("Failed to bind black hole");
    let black_hole_port = black_hole.local_addr().unwrap().port();
    std::thread::spawn(move || {
        let mut connections = Vec::new();
        for stream in black_hole.incoming().flatten() {
            connections.push(stream);
        }
    });

    let port = free_port();
    let node = common::spawn_with_args(
        port,
        &node_args("node-a", &[black_hole_port], &["--peer-timeout-ms", "5000"]),
    );
    let mut client = connect_when_ready(&node).await;

    let mut request = create_task_request(
        "black_hole_task",
        "add",
        vec![any_i32(1)],
        vec!["black_hole_dep".to_string()],
        false,
    );
    request.dependency_timeout_ms = 1000;

    let started = Instant::now();
    let status = client
        .submit_task(Request::new(request))
        .await
        .expect_err("Dependency must time out");
    let elapsed = started.elapsed();
    assert_eq!(status.code(), tonic::Code::DeadlineExceeded);
    assert!(
        elapsed < Duration::from_millis(2500),
        "Deadline must not wait for the 5s peer timeout, took {:?}",
        elapsed
    );
}

#[tokio::test]
async fn test_result_ttl_eviction() {
    let port = free_port();
    let node = common::spawn_with_args(
        port,
        &node_args("node-ttl", &[], &["--result-ttl-secs", "1"]),
    );
    let mut client = connect_when_ready(&node).await;

    client
        .submit_task(Request::new(create_task_request(
            "ttl_task",
            "add",
            vec![any_i32(1)],
            vec![],
            false,
        )))
        .await
        .expect("Submit failed");

    let query = |client: &mut TaskSchedulerClient<Channel>| {
        let mut client = client.clone();
        async move {
            client
                .get_result(Request::new(ResultRequest {
                    task_id: "ttl_task".to_string(),
                }))
                .await
                .expect("GetResult failed")
                .into_inner()
                .status
        }
    };

    assert_eq!(query(&mut client).await, Status::Success as i32);
    tokio::time::sleep(Duration::from_millis(3500)).await;
    assert_eq!(query(&mut client).await, Status::NotFound as i32);
}

#[tokio::test]
async fn test_max_concurrent_tasks() {
    let port = free_port();
    let node = common::spawn_with_args(
        port,
        &node_args("node-limit", &[], &["--max-concurrent-tasks", "1"]),
    );
    let mut client = connect_when_ready(&node).await;

    let started = Instant::now();
    for index in 0..4 {
        client
            .submit_task(Request::new(detached(create_task_request(
                &format!("limited_{}", index),
                "delete",
                vec![any_i32(index)],
                vec![],
                false,
            ))))
            .await
            .expect("Submit failed");
    }

    let mut max_running = 0;
    loop {
        let info = client
            .get_node_info(Request::new(NodeInfoRequest {}))
            .await
            .expect("GetNodeInfo failed")
            .into_inner();
        max_running = max_running.max(info.running_tasks);
        if info.pending_tasks == 0 && info.running_tasks == 0 {
            break;
        }
        tokio::time::sleep(Duration::from_millis(10)).await;
    }

    // Each "delete" sleeps 100ms, so four serialized executions need at least 400ms
    assert_eq!(max_running, 1);
    assert!(started.elapsed() >= Duration::from_millis(400));
    for index in 0..4 {
        let result = wait_terminal(
            &mut client,
            &format!("limited_{}", index),
            Duration::from_secs(5),
        )
        .await;
        assert_eq!(result.status, Status::Success as i32);
    }
}

#[tokio::test]
async fn test_default_node_id_is_unique_for_unspecified_address() {
    let port = free_port();
    let process = std::process::Command::new("target/debug/task-scheduler")
        .arg("--addr")
        .arg(format!("0.0.0.0:{}", port))
        .stdout(std::process::Stdio::null())
        .stderr(std::process::Stdio::null())
        .spawn()
        .expect("Failed to start server");
    let server = TestServer::from_process(process, port);
    let mut client = connect_when_ready(&server).await;

    let info = client
        .get_node_info(Request::new(NodeInfoRequest {}))
        .await
        .expect("GetNodeInfo failed")
        .into_inner();
    assert!(
        !info.node_id.starts_with("0.0.0.0"),
        "Default node id must not be the unspecified address: {}",
        info.node_id
    );
    assert!(info.node_id.ends_with(&format!(":{}", port)));
}
