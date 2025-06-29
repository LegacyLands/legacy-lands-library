use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};
use prost_types::Any;
use clap::Parser;
use serde_json::Value;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Task method to execute
    #[arg(short, long, default_value = "echo")]
    method: String,
    
    /// Task payload (JSON string)
    #[arg(short, long, default_value = "\"Hello from debug test!\"")]
    payload: String,
    
    /// Server address
    #[arg(short, long, default_value = "http://localhost:50052")]
    server: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    println!("=== Test Task Submission Tool ===");
    println!("Connecting to task-manager at {}...", args.server);
    
    let mut client = match TaskSchedulerClient::connect(args.server).await {
        Ok(c) => {
            println!("✓ Connected successfully!");
            c
        }
        Err(e) => {
            eprintln!("✗ Failed to connect to task-manager: {}", e);
            eprintln!("  Make sure task-manager is running");
            return Err(e.into());
        }
    };
    
    println!("\nSubmitting {} task...", args.method);
    
    // Parse payload as JSON
    let payload_value: Value = serde_json::from_str(&args.payload)?;
    
    // Convert to Any based on type
    let any_value = match &payload_value {
        Value::String(s) => Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: serde_json::to_vec(s)?,
        },
        Value::Number(n) if n.is_i64() => Any {
            type_url: "type.googleapis.com/google.protobuf.Int64Value".to_string(),
            value: serde_json::to_vec(&n.as_i64().unwrap())?,
        },
        Value::Array(arr) => Any {
            type_url: "type.googleapis.com/google.protobuf.ListValue".to_string(),
            value: serde_json::to_vec(arr)?,
        },
        _ => Any {
            type_url: "type.googleapis.com/google.protobuf.Value".to_string(),
            value: serde_json::to_vec(&payload_value)?,
        },
    };
    
    let request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: args.method.clone(),
        args: vec![any_value],
        deps: vec![],
        is_async: true,
    };
    
    match client.submit_task(request).await {
        Ok(response) => {
            let inner = response.into_inner();
            println!("✓ Task submitted successfully!");
            println!("  Task ID: {}", inner.task_id);
            println!("  Status: {}", inner.status);
            println!("  Result: {}", inner.result);
            
            // Wait a bit to see if task gets executed
            println!("\nWaiting 3 seconds for task execution...");
            tokio::time::sleep(tokio::time::Duration::from_secs(3)).await;
            
            // Try to get result
            println!("\nChecking task result...");
            let result_request = task_common::proto::ResultRequest {
                task_id: inner.task_id.clone(),
            };
            
            match client.get_result(result_request).await {
                Ok(result_response) => {
                    let result = result_response.into_inner();
                    println!("✓ Got task result!");
                    println!("  Status: {}", result.status);
                    println!("  Result: {}", result.result);
                }
                Err(e) => {
                    println!("✗ Failed to get task result: {}", e);
                }
            }
        }
        Err(e) => {
            eprintln!("✗ Failed to submit task: {}", e);
            eprintln!("  Error details: {:?}", e);
        }
    }
    
    Ok(())
}