use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};
use prost_types::Any;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Testing Synchronous Task Submission ===");
    println!("Connecting to task-manager at localhost:50052...");
    
    let mut client = TaskSchedulerClient::connect("http://localhost:50052").await?;
    println!("✓ Connected successfully!");
    
    // Test 1: Async task (should work)
    println!("\n1. Testing ASYNC task...");
    let async_request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: "echo".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: serde_json::to_vec(&"Async Hello").unwrap(),
        }],
        deps: vec![],
        is_async: true,
    };
    
    let response = client.submit_task(async_request).await?;
    let inner = response.into_inner();
    println!("  Task ID: {}", inner.task_id);
    println!("  Status: {}", inner.status);
    println!("  Result: {}", inner.result);
    
    // Test 2: Sync task (problematic)
    println!("\n2. Testing SYNC task...");
    let sync_request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: "echo".to_string(),
        args: vec![Any {
            type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
            value: serde_json::to_vec(&"Sync Hello").unwrap(),
        }],
        deps: vec![],
        is_async: false,  // This is the key difference
    };
    
    println!("  Submitting synchronous task (will wait up to 30s for result)...");
    let start = std::time::Instant::now();
    
    match client.submit_task(sync_request).await {
        Ok(response) => {
            let elapsed = start.elapsed();
            let inner = response.into_inner();
            println!("  Response received after {:.2}s", elapsed.as_secs_f64());
            println!("  Task ID: {}", inner.task_id);
            println!("  Status: {}", inner.status);
            println!("  Result: {}", inner.result);
            
            if inner.status == 0 {
                println!("  ⚠️  Task still shows as Pending (status=0) after sync submission!");
            }
        }
        Err(e) => {
            eprintln!("  ✗ Failed: {}", e);
        }
    }
    
    Ok(())
}