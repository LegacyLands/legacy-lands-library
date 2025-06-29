use task_common::proto::{TaskRequest, taskscheduler::task_scheduler_client::TaskSchedulerClient};
use prost_types::Any;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    println!("=== Failing Echo Task Submission ===");
    println!("Connecting to task-manager at localhost:50051...");
    
    let mut client = match TaskSchedulerClient::connect("http://localhost:50051").await {
        Ok(c) => {
            println!("✓ Connected successfully!");
            c
        }
        Err(e) => {
            eprintln!("✗ Failed to connect to task-manager: {}", e);
            return Err(e.into());
        }
    };
    
    // Submit echo task with invalid arguments that will cause it to fail
    println!("\nSubmitting echo tasks that will fail during execution...");
    
    // Create different error scenarios
    let error_scenarios = vec![
        // Echo expects a string argument, let's give it none
        ("echo_no_args", vec![]),
        // Echo with null value
        ("echo_null", vec![Any {
            type_url: "type.googleapis.com/google.protobuf.Value".to_string(),
            value: vec![], // Invalid value
        }]),
        // Echo with too many arguments
        ("echo_too_many", vec![
            Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: serde_json::to_vec(&"arg1").unwrap(),
            },
            Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: serde_json::to_vec(&"arg2").unwrap(),
            },
            Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: serde_json::to_vec(&"arg3").unwrap(),
            },
        ]),
    ];
    
    for (scenario, args) in error_scenarios {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: "echo".to_string(), // Use a known method
            args,
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
    
    // Also submit some tasks with methods that exist but will fail
    let failing_methods = vec![
        ("http_get", vec![]), // http_get without URL
        ("multiply", vec![   // multiply with non-numeric args
            Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: serde_json::to_vec(&"not_a_number").unwrap(),
            }
        ]),
    ];
    
    for (method, args) in failing_methods {
        let request = TaskRequest {
            task_id: uuid::Uuid::new_v4().to_string(),
            method: method.to_string(),
            args,
            deps: vec![],
            is_async: true,
        };
        
        match client.submit_task(request).await {
            Ok(response) => {
                let inner = response.into_inner();
                println!("✓ Task submitted ({}): {}", method, inner.task_id);
            }
            Err(e) => {
                println!("✗ Failed to submit task: {}", e);
            }
        }
        
        tokio::time::sleep(tokio::time::Duration::from_millis(500)).await;
    }
    
    println!("\nAll failing tasks submitted. They should fail during execution.");
    Ok(())
}