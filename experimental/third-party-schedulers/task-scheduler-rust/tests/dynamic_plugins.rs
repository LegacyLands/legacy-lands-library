mod common;

use common::utils::{any_i32, any_string, connect_to_server, create_task_request};
use std::fs;
use tonic::Request;

#[tokio::test]
async fn test_plugin_example_multiply() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Create task request
    let args = vec![5, 10]; // 5 * 10 = 50
    let mut task_args = Vec::new();

    for num in args {
        task_args.push(any_i32(num));
    }

    let task_request = create_task_request(
        "plugin_multiply_test",
        "plugin_example::multiply",
        task_args,
        Vec::new(),
        false,
    );

    // Send request
    let response = client
        .submit_task(Request::new(task_request))
        .await
        .expect("RPC failed");

    let task_response = response.into_inner();
    assert_eq!(task_response.result, "50".to_string());
}

#[tokio::test]
async fn test_plugin_example_echo() {
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Create task request
    let test_string = "Hello from dynamic plugin";
    let task_request = create_task_request(
        "plugin_echo_test",
        "plugin_example::delayed_echo",
        vec![any_string(test_string)],
        Vec::new(),
        true,
    );

    // Send request
    let response = client
        .submit_task(Request::new(task_request.clone()))
        .await
        .expect("RPC failed");

    let task_response = response.into_inner();
    assert!(task_response.result.contains("Hello from dynamic plugin"));
}

#[tokio::test]
async fn test_custom_library_path() {
    // Create custom test directory
    let custom_dir = "./custom_libs";
    let _ = fs::create_dir_all(custom_dir);

    // Prepare cleanup
    let _ = fs::remove_dir_all(custom_dir);
    let _ = fs::create_dir_all(custom_dir);

    // Copy existing plugin to custom directory and rename
    fs::copy(
        "libraries/libplugin_example.so",
        format!("{}/libcustom_plugin.so", custom_dir),
    )
    .expect("Failed to copy plugin");

    // Start server with custom library directory
    let server = common::setup_with_options(None, Some(custom_dir)).await;
    let mut client = connect_to_server(&server.address()).await;

    // Test: Verify custom path plugin is loaded with correct name prefix
    let task_request = create_task_request(
        "custom_path_test",
        "custom_plugin::multiply",
        vec![any_i32(7), any_i32(9)],
        Vec::new(),
        false,
    );

    let response = client
        .submit_task(Request::new(task_request))
        .await
        .expect("RPC failed");

    assert_eq!(response.into_inner().result, "63".to_string());

    // Cleanup
    drop(client);
    drop(server);
    let _ = fs::remove_dir_all(custom_dir);
}

#[tokio::test]
async fn test_plugin_unload() {
    // Start server process
    let server = common::setup().await;
    let mut client = connect_to_server(&server.address()).await;

    // Ensure plugin is loaded
    let task_name = "plugin_example::multiply";

    // 1. First test that plugin functionality works normally
    let task_request = create_task_request(
        "plugin_multiply_test_1",
        task_name,
        vec![any_i32(5), any_i32(10)],
        Vec::new(),
        false,
    );

    let response = client
        .submit_task(Request::new(task_request))
        .await
        .expect("RPC failed");

    let task_response = response.into_inner();
    assert_eq!(task_response.result, "50");

    // Close current server
    drop(client);
    drop(server);

    // Start new server with environment variable to prevent loading plugins
    let server = common::setup_with_options(None, Some("./disabled_plugins")).await;
    let mut new_client = connect_to_server(&server.address()).await;

    // 3. Verify plugin method is not available
    let task_request = create_task_request(
        "plugin_multiply_test_2",
        task_name,
        vec![any_i32(5), any_i32(10)],
        Vec::new(),
        false,
    );

    let response = new_client.submit_task(Request::new(task_request)).await;
    assert!(
        response.is_err(),
        "Plugin method still available on server without plugins"
    );

    // Close server
    drop(new_client);
    drop(server);

    // 4. Start server with normal plugin loading
    let server = common::setup_with_options(None, None).await;
    let mut final_client = connect_to_server(&server.address()).await;

    // 5. Verify plugin method is now available
    let task_request = create_task_request(
        "plugin_multiply_test_3",
        task_name,
        vec![any_i32(5), any_i32(10)],
        Vec::new(),
        false,
    );

    let response = final_client
        .submit_task(Request::new(task_request))
        .await
        .expect("RPC failed");

    let task_response = response.into_inner();
    assert_eq!(task_response.result, "50");
}
