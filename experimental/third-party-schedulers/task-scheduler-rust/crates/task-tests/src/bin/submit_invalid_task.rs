use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Invalid Task Submission Tool ===");
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
    
    // Submit task with empty method (should fail validation)
    println!("\nSubmitting task with empty method (should fail)...");
    
    let request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: "".to_string(),  // Empty method should fail
        args: vec![],
        deps: vec![],
        is_async: true,
    };
    
    match client.submit_task(request).await {
        Ok(response) => {
            let inner = response.into_inner();
            println!("✗ Task unexpectedly succeeded!");
            println!("  Task ID: {}", inner.task_id);
        }
        Err(e) => {
            println!("✓ Task failed as expected: {}", e);
        }
    }
    
    // Submit task with invalid method
    println!("\nSubmitting task with invalid method...");
    
    let request = TaskRequest {
        task_id: uuid::Uuid::new_v4().to_string(),
        method: "invalid_method_that_does_not_exist".to_string(),
        args: vec![],
        deps: vec![],
        is_async: true,
    };
    
    match client.submit_task(request).await {
        Ok(response) => {
            let inner = response.into_inner();
            println!("Task submitted (may fail during execution)");
            println!("  Task ID: {}", inner.task_id);
        }
        Err(e) => {
            println!("Task submission failed: {}", e);
        }
    }
    
    Ok(())
}
