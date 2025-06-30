use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};
use base64::Engine;
use bincode;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Fail Task Submission Tool ===");
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
    
    println!("\nSubmitting tasks that will fail intentionally...");
    
    // Different failure scenarios
    let failure_scenarios = vec![
        ("default", ""),
        ("timeout", "timeout error"),
        ("network", "network connection failed"),
        ("validation", "validation error occurred"),
        ("custom", "custom error message"),
    ];
    
    for (scenario, reason) in failure_scenarios {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "fail".to_string(),
            args: if reason.is_empty() {
                vec![]
            } else {
                let value = bincode::serialize(&reason).unwrap();
                vec![base64::engine::general_purpose::STANDARD.encode(&value)]
            },
            deps: vec![],
            is_async: true,
        };
        
        match client.submit_task(request).await {
            Ok(response) => {
                let inner = response.into_inner();
                println!("✓ Task submitted ({}): {}", scenario, inner.task_id);
            }
            Err(e) => {
                println!("✗ Failed to submit task: {}", e);
            }
        }
        
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
    }
    
    println!("\nAll fail tasks submitted. They should fail during execution.");
    
    // Wait a bit to let them process
    println!("Waiting 5 seconds for tasks to be processed...");
    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
    
    println!("Check the metrics at http://localhost:9000/metrics");
    println!("Look for task_manager_tasks_status_total{{status=\"failed\"}}");
    
    Ok(())
}