mod common;

use common::utils::{
    any_bool, any_i32, any_map, any_string, connect_to_server, create_task_request,
};
use std::collections::HashMap;
use tokio::time::sleep;
use tokio::time::Duration;
use tonic::Request;

#[tokio::test]
async fn test_comprehensive_features() {
    // Start regular server
    let server = common::setup_with_options(None, None).await;
    let mut client = connect_to_server(&server.address()).await;

    // 1. Test built-in sync functions
    let add_task = create_task_request(
        "comprehensive_test_add",
        "add",
        vec![any_i32(10), any_i32(20), any_i32(30)],
        vec![],
        false,
    );
    let response = client
        .submit_task(Request::new(add_task))
        .await
        .expect("Failed to submit sync task");
    let result = response.into_inner();
    assert_eq!(result.result, "60", "Sync addition calculation error");

    // 2. Test built-in async functions
    let async_task = create_task_request(
        "comprehensive_test_async",
        "delete",
        vec![any_i32(5)],
        vec![],
        true,
    );
    let response = client
        .submit_task(Request::new(async_task))
        .await
        .expect("Failed to submit async task");
    let result = response.into_inner();
    assert_eq!(
        result.result, "Deleted 1 items",
        "Async function execution error"
    );

    // 3. Test dependency relationships
    let tasks = vec![
        create_task_request(
            "comprehensive_dep_1",
            "add",
            vec![any_i32(5), any_i32(5)],
            vec![],
            false,
        ),
        create_task_request(
            "comprehensive_dep_2",
            "remove",
            vec![any_i32(15), any_i32(5)],
            vec!["comprehensive_dep_1".to_string()],
            false,
        ),
    ];

    for task in tasks {
        let _response = client
            .submit_task(Request::new(task))
            .await
            .expect("Failed to submit dependency task");
    }

    let result_req = task_scheduler::tasks::taskscheduler::ResultRequest {
        task_id: "comprehensive_dep_2".to_string(),
    };
    let response = client
        .get_result(Request::new(result_req))
        .await
        .expect("Failed to get dependency task result");
    assert_eq!(
        response.into_inner().result,
        "10",
        "Dependency task execution result error"
    );

    // 4. Test complex data type handling
    let mut complex_map = HashMap::new();
    complex_map.insert("name".to_string(), any_string("Test User"));
    complex_map.insert("age".to_string(), any_i32(30));
    complex_map.insert("is_active".to_string(), any_bool(true));

    let complex_task = create_task_request(
        "comprehensive_complex_data",
        "process_person_map",
        vec![any_map(complex_map)],
        vec![],
        false,
    );
    let response = client
        .submit_task(Request::new(complex_task))
        .await
        .expect("Failed to submit complex data task");
    assert!(
        response
            .into_inner()
            .result
            .contains("Name=Test User, Age=30"),
        "Complex data processing result error"
    );

    // 5. Test non-existent method
    let nonexistent_task = create_task_request(
        "test_nonexistent",
        "nonexistent_method",
        vec![any_i32(1)],
        vec![],
        false,
    );
    let response = client.submit_task(Request::new(nonexistent_task)).await;
    assert!(
        response.is_err(),
        "Calling non-existent method should return an error"
    );
}

#[tokio::test]
async fn test_simplified_dynamic_library() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Test dynamic library sync function call
    let dynamic_sync_task = create_task_request(
        "test_dynamic_sync",
        "plugin_example::multiply",
        vec![any_i32(6), any_i32(7)],
        vec![],
        false,
    );
    let response = client
        .submit_task(Request::new(dynamic_sync_task))
        .await
        .expect("Failed to submit dynamic sync task");
    assert_eq!(
        response.into_inner().result,
        "42",
        "Dynamic sync function result error"
    );

    // Test dynamic library async function call
    let dynamic_async_task = create_task_request(
        "test_dynamic_async",
        "plugin_example::delayed_echo",
        vec![any_string("Test async echo")],
        vec![],
        true,
    );
    let response = client
        .submit_task(Request::new(dynamic_async_task))
        .await
        .expect("Failed to submit dynamic async task");
    assert!(
        response.into_inner().result.contains("Test async echo"),
        "Dynamic async function result error"
    );
}

#[tokio::test]
async fn test_async_task_completion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task_id = "async_task_completion_1".to_string();
    let task = create_task_request(&task_id, "delete", vec![any_i32(5)], vec![], true);

    // Submit async task
    let submit_response = client.submit_task(Request::new(task)).await;
    assert!(
        submit_response.is_ok(),
        "Failed to submit async task: {:?}",
        submit_response.err()
    );

    // Wait for async task to complete and be cached
    sleep(Duration::from_secs(1)).await;

    // Add a small additional delay
    sleep(Duration::from_millis(100)).await;

    // Try to get the result
    let result_req = task_scheduler::tasks::taskscheduler::ResultRequest {
        task_id: task_id.clone(),
    };
    let get_result_response = client.get_result(Request::new(result_req)).await;

    // Check if getting the result succeeded
    match get_result_response {
        Ok(response) => {
            let result = response.into_inner();
            assert_eq!(
                result.status,
                task_scheduler::tasks::taskscheduler::task_response::Status::Success as i32,
                "Async task should have completed successfully"
            );
            // Assuming 'delete' task with one arg returns this string
            assert_eq!(result.result, "Deleted 1 items");
        }
        Err(status) => {
            // If it still fails, the issue might be elsewhere
            panic!("Failed to get task result for '{}': {:?}", task_id, status);
        }
    }
}
