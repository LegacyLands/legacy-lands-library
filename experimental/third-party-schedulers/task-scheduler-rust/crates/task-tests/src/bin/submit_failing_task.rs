use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Failing Task Submission Tool ===");
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
    
    // Submit task with method that doesn't exist (will fail during execution)
    println!("\nSubmitting task with non-existent method...");
    
    let failing_methods = vec![
        "method_that_does_not_exist",
        "invalid_plugin_method", 
        "timeout_simulation",
        "network_error_simulation",
        "validation_error",
    ];
    
    for method in failing_methods {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: method.to_string(),
            args: vec![],
            deps: vec![],
            is_async: true,
        };
        
        match client.submit_task(request).await {
            Ok(response) => {
                let inner = response.into_inner();
                println!("✓ Task submitted with method '{}': {}", method, inner.task_id);
            }
            Err(e) => {
                println!("✗ Failed to submit task: {}", e);
            }
        }
        
        // Small delay between submissions
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
    }
    
    println!("\nAll failing tasks submitted. They should fail during execution.");
    Ok(())
}