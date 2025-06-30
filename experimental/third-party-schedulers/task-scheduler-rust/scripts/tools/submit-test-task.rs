#!/usr/bin/env -S cargo +nightly -Zscript

//! Debug script to submit a test task
//! ```cargo
//! [dependencies]
//! task-common = { path = "../../crates/task-common" }
//! tokio = { version = "1", features = ["full"] }
//! tonic = "0.12"
//! prost = "0.13"
//! prost-types = "0.13"
//! uuid = { version = "1", features = ["v4"] }
//! serde_json = "1"
//! ```

use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};
use prost_types::Any;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("Connecting to task-manager...");
    
    let mut client = TaskSchedulerClient::connect("http://localhost:50051").await?;
    
    println!("Submitting echo task...");
    
    let request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: "echo".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: serde_json::to_vec(&"Hello from debug test!").unwrap(),
        }],
        deps: vec![],
        is_async: true,
    };
    
    let response = client.submit_task(request).await?;
    let inner = response.into_inner();
    
    println!("Task submitted successfully!");
    println!("Task ID: {}", inner.task_id);
    println!("Status: {}", inner.status);
    println!("Result: {}", inner.result);
    
    Ok(())
}