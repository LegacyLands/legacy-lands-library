use clap::Parser;
use task_common::proto::task_scheduler_client::TaskSchedulerClient;
use task_common::proto::{TaskRequest, ResultRequest};
use tokio::time::sleep;
use std::time::Duration;
use prost_types::Any;

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// gRPC server address
    #[arg(short, long, default_value = "http://127.0.0.1:50051")]
    server: String,
    
    /// Task method
    #[arg(short, long, default_value = "echo")]
    method: String,
    
    /// Task arguments
    #[arg(short, long, default_value = "Task Storage Test")]
    args: String,
    
    /// Number of tasks to submit
    #[arg(short, long, default_value_t = 5)]
    count: usize,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();
    
    println!("Connecting to gRPC server at {}", args.server);
    let mut client = TaskSchedulerClient::connect(args.server).await?;
    
    println!("Submitting {} tasks...", args.count);
    let mut task_ids = Vec::new();
    
    for i in 1..=args.count {
        let task_id = uuid::Uuid::new_v4().to_string();
        
        // Create the argument as a JSON string
        let arg_value = serde_json::json!(format!("{} #{}", args.args, i));
        let arg_bytes = serde_json::to_vec(&arg_value)?;
        
        let request = tonic::Request::new(TaskRequest {
            task_id: task_id.clone(),
            method: args.method.clone(),
            args: vec![Any {
                type_url: "type.googleapis.com/google.protobuf.StringValue".to_string(),
                value: arg_bytes,
            }],
            deps: vec![],
            is_async: false,
        });
        
        match client.submit_task(request).await {
            Ok(response) => {
                let resp = response.into_inner();
                println!("✓ Task {} submitted: {}", i, resp.task_id);
                task_ids.push(resp.task_id);
            }
            Err(e) => {
                println!("✗ Failed to submit task {}: {}", i, e);
            }
        }
        
        sleep(Duration::from_millis(100)).await;
    }
    
    println!("\nWaiting for tasks to complete...");
    sleep(Duration::from_secs(3)).await;
    
    println!("\nChecking task results...");
    for (i, task_id) in task_ids.iter().enumerate() {
        let request = tonic::Request::new(ResultRequest {
            task_id: task_id.clone(),
        });
        
        match client.get_result(request).await {
            Ok(response) => {
                let resp = response.into_inner();
                println!("✓ Task {} result: status={}, has_result={}", 
                    i + 1, 
                    resp.status,
                    resp.result.is_some()
                );
            }
            Err(e) => {
                println!("✗ Failed to get result for task {}: {}", i + 1, e);
            }
        }
    }
    
    println!("\nTest completed! Check storage for stored data.");
    Ok(())
}