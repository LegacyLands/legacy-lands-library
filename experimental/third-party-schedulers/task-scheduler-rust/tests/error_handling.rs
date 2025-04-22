mod common;

use common::utils::{any_i32, connect_to_server, create_task_request};
use tonic::Request;

#[tokio::test]
async fn test_error_handling() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Test non-existent method
    let task = create_task_request(
        "invalid",
        "nonexistent",
        vec![any_i32(1), any_i32(2), any_i32(3)],
        vec![],
        false,
    );
    let response_result = client.submit_task(Request::new(task)).await;
    assert!(response_result.is_err());
    if let Err(status) = response_result {
        assert_eq!(status.code(), tonic::Code::NotFound);
        assert!(status.message().contains("Method not found: nonexistent"));
    } else {
        panic!("Expected an error response for non-existent method");
    }

    // Test insufficient arguments
    let task_invalid_args = create_task_request(
        "invalid_args",
        "remove",
        vec![any_i32(1)], // Only one argument
        vec![],
        false,
    );
    let response_result_invalid = client.submit_task(Request::new(task_invalid_args)).await;
    assert!(
        response_result_invalid.is_err(),
        "Expected submit_task to return Err for invalid args"
    );
    if let Err(status) = response_result_invalid {
        assert_eq!(
            status.code(),
            tonic::Code::InvalidArgument,
            "Expected InvalidArgument status code"
        );
        assert!(status.message().contains("Need at least 2 arguments"));
    } else {
        panic!("Expected Err status but got Ok");
    }
}
