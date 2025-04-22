mod common;

use common::utils::{any_i32, connect_to_server, create_task_request};
use tonic::Request;

#[tokio::test]
async fn test_basic_operations() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Test addition
    let add_task = create_task_request(
        "add_1",
        "add",
        vec![any_i32(1), any_i32(2), any_i32(3)],
        vec![],
        false,
    );
    let response = client
        .submit_task(Request::new(add_task))
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "6");

    // Test subtraction
    let remove_task = create_task_request(
        "remove_1",
        "remove",
        vec![any_i32(10), any_i32(3), any_i32(2)],
        vec![],
        false,
    );
    let response = client
        .submit_task(Request::new(remove_task))
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "5");
}

#[tokio::test]
async fn test_async_operations() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let delete_task = create_task_request(
        "delete_1",
        "delete",
        vec![any_i32(1), any_i32(2), any_i32(3)],
        vec![],
        true,
    );
    let response = client
        .submit_task(Request::new(delete_task))
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "Deleted 3 items");
}

#[tokio::test]
async fn test_dependencies() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let tasks = vec![
        create_task_request(
            "add_dep",
            "add",
            vec![any_i32(1), any_i32(2), any_i32(3)],
            vec![],
            false,
        ),
        create_task_request(
            "remove_dep",
            "remove",
            vec![any_i32(10), any_i32(3)],
            vec!["add_dep".to_string()],
            false,
        ),
        create_task_request(
            "delete_dep",
            "delete",
            vec![any_i32(1), any_i32(2)],
            vec!["add_dep".to_string(), "remove_dep".to_string()],
            true,
        ),
    ];

    // Submit tasks sequentially and check if calls succeeded
    for task in tasks {
        let task_id = task.task_id.clone();
        let response_result = client.submit_task(Request::new(task)).await;
        assert!(
            response_result.is_ok(),
            "Failed to submit or execute task '{}'. Status: {:?}",
            task_id,
            response_result.err()
        );
    }

    // Add a brief delay to allow cache writes
    tokio::time::sleep(std::time::Duration::from_millis(100)).await;

    // Verify final task result
    let result_req = task_scheduler::tasks::taskscheduler::ResultRequest {
        task_id: "delete_dep".to_string(),
    };
    let response = client
        .get_result(Request::new(result_req))
        .await
        .expect("Failed to get result for delete_dep");
    let result = response.into_inner();

    // Check status using the correct enum variant
    assert_eq!(
        result.status,
        task_scheduler::tasks::taskscheduler::task_response::Status::Success as i32,
        "Task 'delete_dep' should be completed successfully"
    );
    assert_eq!(
        result.result, "Deleted 2 items",
        "Unexpected result for delete_dep"
    );
}
