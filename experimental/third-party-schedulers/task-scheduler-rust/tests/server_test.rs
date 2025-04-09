mod common;

use prost::Message;
use prost_types::Any;
use std::collections::HashMap;
use std::time::Duration;
use task_scheduler::models::wrappers::{BoolValue, BytesValue, Int32Value, StringValue};
use task_scheduler::tasks::taskscheduler::{
    task_scheduler_client::TaskSchedulerClient, ListValue, MapValue, ResultRequest, TaskRequest,
};
use tonic::transport::Channel;
use tonic::Request;

// Helper functions to create Any messages for different types

/// Convert i32 to an Any message
fn any_i32(val: i32) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
        value: Int32Value { value: val }.encode_to_vec(),
    }
}

/// Convert bool to an Any message
fn any_bool(val: bool) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.BoolValue".to_string(),
        value: BoolValue { value: val }.encode_to_vec(),
    }
}

/// Convert String to an Any message
fn any_string(val: &str) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
        value: StringValue {
            value: val.to_string(),
        }
        .encode_to_vec(),
    }
}

/// Convert byte slice to an Any message
fn any_bytes(val: &[u8]) -> Any {
    Any {
        type_url: "type.googleapis.com/google.protobuf.BytesValue".to_string(),
        value: BytesValue {
            value: val.to_vec(),
        }
        .encode_to_vec(),
    }
}

// Renamed from any_array to any_list, uses generated taskscheduler::ListValue
fn any_list(items: Vec<Any>) -> Any {
    // Use the generated ListValue message
    let list_val = ListValue { values: items };
    let mut buf = Vec::new();
    list_val.encode(&mut buf).unwrap();
    Any {
        // Update the type URL to match the new ListValue definition
        type_url: "type.googleapis.com/taskscheduler.ListValue".to_string(),
        value: buf,
    }
}

// Updated to use generated taskscheduler::MapValue
fn any_map(map: HashMap<String, Any>) -> Any {
    // Use the generated MapValue message (note the 'fields' field name)
    let map_val = MapValue { fields: map };
    let mut buf = Vec::new();
    map_val.encode(&mut buf).unwrap();
    Any {
        // Update the type URL to match the new MapValue definition
        type_url: "type.googleapis.com/taskscheduler.MapValue".to_string(),
        value: buf,
    }
}

async fn connect_to_server(server_address: &str) -> TaskSchedulerClient<Channel> {
    let channel = tonic::transport::Channel::from_shared(server_address.to_string())
        .expect("Failed to create shared endpoint")
        .connect()
        .await
        .expect("Failed to create channel");

    TaskSchedulerClient::new(channel)
}

#[tokio::test]
async fn test_basic_operations() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Test addition
    let add_task = TaskRequest {
        task_id: "add_1".to_string(),
        method: "add".to_string(),
        args: vec![any_i32(1), any_i32(2), any_i32(3)],
        deps: vec![],
        is_async: false,
    };
    let response = client
        .submit_task(add_task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "6");

    // Test subtraction
    let remove_task = TaskRequest {
        task_id: "remove_1".to_string(),
        method: "remove".to_string(),
        args: vec![any_i32(10), any_i32(3), any_i32(2)],
        deps: vec![],
        is_async: false,
    };
    let response = client
        .submit_task(remove_task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "5");
}

#[tokio::test]
async fn test_async_operations() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let delete_task = TaskRequest {
        task_id: "delete_1".to_string(),
        method: "delete".to_string(),
        args: vec![any_i32(1), any_i32(2), any_i32(3)],
        deps: vec![],
        is_async: true,
    };
    let response = client
        .submit_task(delete_task)
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
        TaskRequest {
            task_id: "add_dep".to_string(),
            method: "add".to_string(),
            args: vec![any_i32(1), any_i32(2), any_i32(3)],
            deps: vec![],
            is_async: false,
        },
        TaskRequest {
            task_id: "remove_dep".to_string(),
            method: "remove".to_string(),
            args: vec![any_i32(10), any_i32(3)],
            deps: vec!["add_dep".to_string()],
            is_async: false,
        },
        TaskRequest {
            task_id: "delete_dep".to_string(),
            method: "delete".to_string(),
            args: vec![any_i32(1), any_i32(2)],
            deps: vec!["add_dep".to_string(), "remove_dep".to_string()],
            is_async: true,
        },
    ];

    // Submit tasks sequentially
    for task in tasks {
        let response = client
            .submit_task(task)
            .await
            .expect("Failed to submit task");
        let result = response.into_inner();
        assert_eq!(result.status, 1, "Task execution failed");
    }

    // Verify final task results
    let result = client
        .get_result(ResultRequest {
            task_id: "delete_dep".to_string(),
        })
        .await
        .expect("Failed to get result")
        .into_inner();

    // Check status using the correct enum variant from proto
    assert_eq!(
        result.status,
        task_scheduler::tasks::taskscheduler::task_response::Status::Success as i32,
        "Task should be completed successfully"
    );
    assert_eq!(result.result, "Deleted 2 items", "Unexpected result");
}

#[tokio::test]
async fn test_error_handling() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Test non-existent method
    let task = TaskRequest {
        task_id: "invalid".to_string(),
        method: "nonexistent".to_string(),
        args: vec![any_i32(1), any_i32(2), any_i32(3)],
        deps: vec![],
        is_async: false,
    };
    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.status, 2);
    assert_eq!(result.result, "Error: Method not found");

    // Test insufficient parameters
    let task = TaskRequest {
        task_id: "invalid_args".to_string(),
        method: "remove".to_string(),
        args: vec![any_i32(1)],
        deps: vec![],
        is_async: false,
    };
    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(result.result, "Error: Need at least 2 arguments");
}

#[tokio::test]
async fn test_collection_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Use the updated any_list helper
    let list_arg = any_list(vec![any_i32(10), any_i32(20)]);
    let mut map_input = HashMap::new();
    map_input.insert("key".to_string(), any_i32(42));
    let map_arg = any_map(map_input);

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "collection_test".to_string(),
        method: "process_collection".to_string(),
        args: vec![list_arg, map_arg], // Pass the new list/map Any values
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();
    assert_eq!(
        result.result, "Array: 2 items, Map: 1 items",
        "Unexpected collection output"
    );
}

#[tokio::test]
async fn test_bool_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "bool_test".to_string(),
        method: "echo_bool".to_string(),
        args: vec![any_bool(true), any_bool(false)],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit bool test task");
    let result = response.into_inner();
    assert_eq!(result.result, "true,false", "Unexpected boolean result");
}

#[tokio::test]
async fn test_string_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "string_test".to_string(),
        method: "echo_string".to_string(),
        args: vec![any_string("hello"), any_string("world")],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit string test task");
    let result = response.into_inner();
    assert_eq!(result.result, "hello,world", "Unexpected string result");
}

#[tokio::test]
async fn test_bytes_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "bytes_test".to_string(),
        method: "echo_bytes".to_string(),
        args: vec![any_bytes(b"abc"), any_bytes(b"123")],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit bytes test task");
    let result = response.into_inner();
    assert_eq!(result.result, "abc,123", "Unexpected bytes result");
}

#[tokio::test]
async fn test_echo_boolean() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let bool_arg = Any {
        type_url: "type.googleapis.com/google.protobuf.BoolValue".to_string(),
        value: BoolValue { value: true }.encode_to_vec(),
    };

    let request = Request::new(TaskRequest {
        task_id: "test-bool-1".to_string(),
        method: "echo_bool".to_string(),
        args: vec![bool_arg],
        deps: vec![],
        is_async: false,
    });

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-bool-1");
    assert_eq!(response.status, 1); // SUCCESS
    assert_eq!(response.result, "true", "Unexpected boolean echo result");
}

#[tokio::test]
async fn test_echo_bytes() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let bytes_arg = Any {
        type_url: "type.googleapis.com/google.protobuf.BytesValue".to_string(),
        value: BytesValue {
            value: b"hello bytes".to_vec(),
        }
        .encode_to_vec(),
    };

    let request = Request::new(TaskRequest {
        task_id: "test-bytes-1".to_string(),
        method: "echo_bytes".to_string(),
        args: vec![bytes_arg],
        deps: vec![],
        is_async: false,
    });

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-bytes-1");
    assert_eq!(response.status, 1); // SUCCESS
    assert_eq!(
        response.result, "hello bytes",
        "Unexpected bytes echo result"
    );
}

#[tokio::test]
async fn test_process_nested_list() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Construct nested list using the updated any_list helper
    let nested_list_any = any_list(vec![
        any_list(vec![any_string("a"), any_string("b")]),
        any_list(vec![any_string("c"), any_string("d")]),
    ]);

    let request = Request::new(TaskRequest {
        task_id: "test-nested-list-1".to_string(),
        method: "process_nested_list".to_string(),
        args: vec![nested_list_any], // Pass the new list Any value
        deps: vec![],
        is_async: false,
    });

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-nested-list-1");
    assert_eq!(response.status, 1);
    assert_eq!(
        response.result.trim(),
        "Received nested list with 2 inner lists: [List 0 (2 items): a, b] [List 1 (2 items): c, d]",
        "Unexpected nested list result"
    );
}

#[tokio::test]
async fn test_process_complex_map() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Construct complex map using the updated any_map and any_list helpers
    let mut complex_map_input = HashMap::new();
    complex_map_input.insert("key1".to_string(), any_string("value1"));
    complex_map_input.insert(
        "key2".to_string(),
        any_list(vec![any_i32(1), any_i32(2), any_i32(3)]),
    );
    let mut nested_map = HashMap::new();
    nested_map.insert("nested_key".to_string(), any_string("nested_value"));
    complex_map_input.insert("key3".to_string(), any_map(nested_map));

    let complex_map_any = any_map(complex_map_input);

    let request = Request::new(TaskRequest {
        task_id: "test-complex-map-1".to_string(),
        method: "process_complex_map".to_string(),
        args: vec![complex_map_any], // Pass the new map Any value
        deps: vec![],
        is_async: false,
    });

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-complex-map-1");
    assert_eq!(response.status, 1);
    assert_eq!(
        response.result.trim(),
        "Received complex map:",
        "Unexpected complex map result"
    );
}

#[tokio::test]
async fn test_process_person_map() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Construct person map using the updated any_map helper
    let mut person_map_input = HashMap::new();
    person_map_input.insert("name".to_string(), any_string("Alice"));
    person_map_input.insert("age".to_string(), any_i32(30));
    person_map_input.insert("city".to_string(), any_string("New York"));
    let person_map_any = any_map(person_map_input);

    let request = Request::new(TaskRequest {
        task_id: "test-person-map-1".to_string(),
        method: "process_person_map".to_string(),
        args: vec![person_map_any], // Pass the new map Any value
        deps: vec![],
        is_async: false,
    });

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-person-map-1");
    assert_eq!(response.status, 1);
    assert_eq!(
        response.result.trim(),
        "Processing person: Name=Alice, Age=30",
        "Unexpected person map result"
    );
}

#[tokio::test]
async fn test_async_task_completion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task_id = "async_task_completion_1".to_string();
    let task = TaskRequest {
        task_id: task_id.clone(),
        method: "delete".to_string(),
        args: vec![any_i32(1), any_i32(2), any_i32(3)],
        deps: vec![],
        is_async: true,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit task");
    let result = response.into_inner();

    assert_eq!(result.status, 1, "Task should be submitted successfully");

    tokio::time::sleep(Duration::from_secs(1)).await;

    let result_request = ResultRequest {
        task_id: task_id.clone(),
    };
    let response = client
        .get_result(tonic::Request::new(result_request))
        .await
        .expect("Failed to get task result");

    let result = response.into_inner();

    assert_eq!(
        result.status,
        task_scheduler::tasks::taskscheduler::task_response::Status::Success as i32,
        "Task should be completed successfully"
    );

    assert_eq!(
        result.result, "Deleted 3 items",
        "Result value is unexpected for delete task"
    );
}
