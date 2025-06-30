use clap::Parser;
use serde_json::Value;
use task_common::proto::{taskscheduler::task_scheduler_client::TaskSchedulerClient, TaskRequest};
use base64::Engine;
use bincode;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Task method to execute
    #[arg(short, long, default_value = "echo")]
    method: String,

    /// Task payload (JSON string)
    #[arg(short, long, default_value = "\"Hello from debug test!\"")]
    payload: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    println!("=== Test Task Submission Tool ===");
    println!("Connecting to task-manager at localhost:50051...");

    let mut client = match TaskSchedulerClient::connect("http://localhost:50051").await {
        Ok(c) => {
            println!("✓ Connected successfully!");
            c
        }
        Err(e) => {
            eprintln!("✗ Failed to connect to task-manager: {}", e);
            eprintln!("  Make sure task-manager is running on localhost:50051");
            return Err(e.into());
        }
    };

    println!("\nSubmitting {} task...", args.method);

    // Parse payload as JSON
    let payload_value: Value = serde_json::from_str(&args.payload)?;
    println!("  Payload: {}", payload_value);

    // Serialize the value to bincode and encode as base64
    let encoded_arg = {
        let bytes = bincode::serialize(&payload_value)?;
        base64::engine::general_purpose::STANDARD.encode(&bytes)
    };

    let request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: args.method.clone(),
        args: vec![encoded_arg],
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

            // Query the task result
            println!("Querying task result...");
            let result_request = task_common::proto::ResultRequest {
                task_id: inner.task_id.clone(),
            };

            match client.get_result(result_request).await {
                Ok(result_response) => {
                    let result = result_response.into_inner();
                    println!("  Final Status: {}", result.status);
                    println!("  Final Result: {}", result.result);
                }
                Err(e) => {
                    eprintln!("Failed to get result: {}", e);
                }
            }
        }
        Err(e) => {
            eprintln!("✗ Failed to submit task: {}", e);
            eprintln!("  Error details: {:?}", e);
            return Err(e.into());
        }
    }

    Ok(())
}