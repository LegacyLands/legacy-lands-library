mod common;

use prost::Message;
use prost_types::Any;
use task_scheduler::models::wrappers::{BoolValue, BytesValue, Int32Value, StringValue};
use task_scheduler::tasks::taskscheduler::{
    task_scheduler_client::TaskSchedulerClient, ResultRequest, TaskRequest,
};
use tonic::transport::Channel;

/// Convert i32 to an Any message
fn any_i32(val: i32) -> Any {
    let msg = Int32Value { value: val };
    let mut buf = Vec::new();
    msg.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
        value: buf,
    }
}

/// Convert bool to an Any message
fn any_bool(val: bool) -> Any {
    let msg = BoolValue { value: val };
    let mut buf = Vec::new();
    msg.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/google.protobuf.BoolValue".to_string(),
        value: buf,
    }
}

/// Convert a string to an Any message
fn any_string(val: &str) -> Any {
    let msg = StringValue {
        value: val.to_string(),
    };
    let mut buf = Vec::new();
    msg.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
        value: buf,
    }
}

/// Convert a byte array to an Any message
fn any_bytes(val: &[u8]) -> Any {
    let msg = BytesValue {
        value: val.to_vec(),
    };
    let mut buf = Vec::new();
    msg.encode(&mut buf).unwrap();
    Any {
        type_url: "type.googleapis.com/google.protobuf.BytesValue".to_string(),
        value: buf,
    }
}

async fn connect_to_server() -> TaskSchedulerClient<Channel> {
    let channel = tonic::transport::Channel::from_static("http://[::1]:50051")
        .connect()
        .await
        .expect("Failed to create channel");

    TaskSchedulerClient::new(channel)
}

#[tokio::test]
async fn run_all_tests() {
    let _server = common::setup().await;
    run_test(test_basic_operations()).await;
    run_test(test_async_operations()).await;
    run_test(test_dependencies()).await;
    run_test(test_error_handling()).await;
    run_test(test_collection_conversion()).await;
    run_test(test_bool_conversion()).await;
    run_test(test_string_conversion()).await;
    run_test(test_bytes_conversion()).await;
}

async fn run_test<F>(future: F) -> F::Output
where
    F: std::future::Future,
{
    future.await
}

async fn test_basic_operations() {
    let mut client = connect_to_server().await;

    // Test addition
    let add_task = TaskRequest {
        task_id: "add_1".to_string(),
        method: "task_scheduler::tasks::builtin::add".to_string(),
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
        method: "task_scheduler::tasks::builtin::remove".to_string(),
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

async fn test_async_operations() {
    let mut client = connect_to_server().await;

    let delete_task = TaskRequest {
        task_id: "delete_1".to_string(),
        method: "task_scheduler::tasks::builtin::delete".to_string(),
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

async fn test_dependencies() {
    let mut client = connect_to_server().await;

    let tasks = vec![
        TaskRequest {
            task_id: "add_dep".to_string(),
            method: "task_scheduler::tasks::builtin::add".to_string(),
            args: vec![any_i32(1), any_i32(2), any_i32(3)],
            deps: vec![],
            is_async: false,
        },
        TaskRequest {
            task_id: "remove_dep".to_string(),
            method: "task_scheduler::tasks::builtin::remove".to_string(),
            args: vec![any_i32(10), any_i32(3)],
            deps: vec!["add_dep".to_string()],
            is_async: false,
        },
        TaskRequest {
            task_id: "delete_dep".to_string(),
            method: "task_scheduler::tasks::builtin::delete".to_string(),
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

    assert!(result.is_ready, "Task should be completed");
    assert_eq!(result.result, "Deleted 2 items", "Unexpected result");
}

async fn test_error_handling() {
    let mut client = connect_to_server().await;

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
        method: "task_scheduler::tasks::builtin::remove".to_string(),
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

async fn test_collection_conversion() {
    let mut client = connect_to_server().await;

    use prost::Message;
    use prost_types::Any;
    use std::collections::HashMap;
    use task_scheduler::models::wrappers::{ArrayValue, Int32Value, MapValue};

    fn any_i32(val: i32) -> Any {
        let msg = Int32Value { value: val };
        let mut buf = Vec::new();
        msg.encode(&mut buf).unwrap();
        Any {
            type_url: "type.googleapis.com/google.protobuf.Int32Value".to_string(),
            value: buf,
        }
    }

    fn any_array(items: Vec<Any>) -> Any {
        let array_val = ArrayValue { values: items };
        let mut buf = Vec::new();
        array_val.encode(&mut buf).unwrap();
        Any {
            type_url: "type.googleapis.com/google.protobuf.ArrayValue".to_string(),
            value: buf,
        }
    }

    fn any_map(map: HashMap<String, Any>) -> Any {
        let map_val = MapValue { values: map };
        let mut buf = Vec::new();
        map_val.encode(&mut buf).unwrap();
        Any {
            type_url: "type.googleapis.com/google.protobuf.MapValue".to_string(),
            value: buf,
        }
    }

    // Create array parameter: [10, 20]
    let array_arg = any_array(vec![any_i32(10), any_i32(20)]);

    // Create map parameter: {"key": 42}
    let mut map_input = HashMap::new();
    map_input.insert("key".to_string(), any_i32(42));
    let map_arg = any_map(map_input);

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "collection_test".to_string(),
        method: "task_scheduler::tasks::builtin::process_collection".to_string(),
        args: vec![array_arg, map_arg],
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

async fn test_bool_conversion() {
    let mut client = connect_to_server().await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "bool_test".to_string(),
        method: "task_scheduler::tasks::builtin::echo_bool".to_string(),
        args: vec![any_bool(true), any_bool(false)],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit bool test task");
    let result = response.into_inner();
    assert_eq!(result.result, "true,false", "Unexpected bool echo output");
}

async fn test_string_conversion() {
    let mut client = connect_to_server().await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "string_test".to_string(),
        method: "task_scheduler::tasks::builtin::echo_string".to_string(),
        args: vec![any_string("hello"), any_string("world")],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit string test task");
    let result = response.into_inner();
    assert_eq!(
        result.result, "hello,world",
        "Unexpected string echo output"
    );
}

async fn test_bytes_conversion() {
    let mut client = connect_to_server().await;

    let task = task_scheduler::tasks::taskscheduler::TaskRequest {
        task_id: "bytes_test".to_string(),
        method: "task_scheduler::tasks::builtin::echo_bytes".to_string(),
        args: vec![any_bytes(b"abc"), any_bytes(b"123")],
        deps: vec![],
        is_async: false,
    };

    let response = client
        .submit_task(task)
        .await
        .expect("Failed to submit bytes test task");
    let result = response.into_inner();
    assert_eq!(result.result, "abc,123", "Unexpected bytes echo output");
}
