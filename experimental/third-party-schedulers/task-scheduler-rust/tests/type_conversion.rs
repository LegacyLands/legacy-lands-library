mod common;

use common::utils::{
    any_bool, any_bytes, any_i32, any_list, any_map, any_string, connect_to_server,
    create_task_request,
};
use std::collections::HashMap;
use tonic::Request;

#[tokio::test]
async fn test_collection_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Use list and map
    let list_arg = any_list(vec![any_i32(10), any_i32(20)]);
    let mut map_input = HashMap::new();
    map_input.insert("key".to_string(), any_i32(42));
    let map_arg = any_map(map_input);

    let task = create_task_request(
        "collection_test",
        "process_collection",
        vec![list_arg, map_arg],
        vec![],
        false,
    );

    let response = client
        .submit_task(Request::new(task))
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

    let task = create_task_request(
        "bool_test",
        "echo_bool",
        vec![any_bool(true), any_bool(false)],
        vec![],
        false,
    );

    let response = client
        .submit_task(Request::new(task))
        .await
        .expect("Failed to submit bool test task");
    let result = response.into_inner();
    assert_eq!(result.result, "true,false", "Unexpected boolean result");
}

#[tokio::test]
async fn test_string_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task = create_task_request(
        "string_test",
        "echo_string",
        vec![any_string("hello"), any_string("world")],
        vec![],
        false,
    );

    let response = client
        .submit_task(Request::new(task))
        .await
        .expect("Failed to submit string test task");
    let result = response.into_inner();
    assert_eq!(result.result, "hello,world", "Unexpected string result");
}

#[tokio::test]
async fn test_bytes_conversion() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    let task = create_task_request(
        "bytes_test",
        "echo_bytes",
        vec![any_bytes(b"abc"), any_bytes(b"123")],
        vec![],
        false,
    );

    let response = client
        .submit_task(Request::new(task))
        .await
        .expect("Failed to submit bytes test task");
    let result = response.into_inner();
    assert_eq!(result.result, "abc,123", "Unexpected bytes result");
}

#[tokio::test]
async fn test_process_nested_list() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Construct nested list
    let nested_list_any = any_list(vec![
        any_list(vec![any_string("a"), any_string("b")]),
        any_list(vec![any_string("c"), any_string("d")]),
    ]);

    let request = Request::new(create_task_request(
        "test-nested-list-1",
        "process_nested_list",
        vec![nested_list_any],
        vec![],
        false,
    ));

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

    // Construct complex map
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

    let request = Request::new(create_task_request(
        "test-complex-map-1",
        "process_complex_map",
        vec![complex_map_any],
        vec![],
        false,
    ));

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

    // Construct person map
    let mut person_map_input = HashMap::new();
    person_map_input.insert("name".to_string(), any_string("Alice"));
    person_map_input.insert("age".to_string(), any_i32(30));
    person_map_input.insert("city".to_string(), any_string("New York"));
    let person_map_any = any_map(person_map_input);

    let request = Request::new(create_task_request(
        "test-person-map-1",
        "process_person_map",
        vec![person_map_any],
        vec![],
        false,
    ));

    let response = client.submit_task(request).await.unwrap().into_inner();
    assert_eq!(response.task_id, "test-person-map-1");
    assert_eq!(response.status, 1);
    assert_eq!(
        response.result.trim(),
        "Processing person: Name=Alice, Age=30",
        "Unexpected person map result"
    );
}
