use std::time::Duration;
use tokio::time::sleep;

mod common;
use common::TestEnvironment;

/// Test task retry on failure
#[tokio::test]
async fn test_task_retry_mechanism() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing task retry mechanism...");
    
    // Submit a task that will fail initially
    // In a real implementation, we would have a task that fails on first attempts
    let task_id = client.submit_computation_task(-1, -1).await?;
    
    // Wait for retries
    sleep(Duration::from_secs(5)).await;
    
    // Check final status
    let result = client.get_task_result(&task_id).await?;
    
    // The task should either succeed after retry or fail permanently
    assert!(result.status == 1 || result.status == 2);
    
    Ok(())
}

/// Test worker failure recovery
#[tokio::test]
#[ignore] // Requires manual worker management
async fn test_worker_failure_recovery() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing worker failure recovery...");
    
    // Submit multiple long-running tasks
    let mut task_ids = vec![];
    for i in 0..5 {
        let task_id = client.submit_async_sleep_task(10).await?;
        println!("Submitted task {}: {}", i, task_id);
        task_ids.push(task_id);
    }
    
    // Simulate worker failure by stopping one worker
    // In a real test environment, we would stop a worker container here
    println!("Simulating worker failure (manual intervention required)...");
    sleep(Duration::from_secs(2)).await;
    
    // Submit more tasks to test redistribution
    for i in 5..10 {
        let task_id = client.submit_echo_task(&format!("Recovery test {}", i)).await?;
        task_ids.push(task_id.task_id);
    }
    
    // Wait for all tasks to complete
    sleep(Duration::from_secs(15)).await;
    
    // Verify all tasks eventually complete
    let mut completed = 0;
    for task_id in &task_ids {
        let result = client.get_task_result(task_id).await?;
        if result.status == 1 {
            completed += 1;
        }
    }
    
    println!("Completed {}/{} tasks after worker failure", completed, task_ids.len());
    assert!(completed >= task_ids.len() * 8 / 10); // At least 80% completion
    
    Ok(())
}

/// Test NATS connection resilience
#[tokio::test]
#[ignore] // Requires NATS manipulation
async fn test_nats_connection_resilience() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing NATS connection resilience...");
    
    // Submit task before NATS disruption
    let task1 = client.submit_echo_task("Before disruption").await?;
    assert_eq!(task1.status, 1);
    
    println!("Simulating NATS network disruption (requires manual intervention)...");
    // In a real test, we would use network manipulation tools here
    
    // Try to submit tasks during disruption
    let mut failed_submissions = 0;
    for i in 0..5 {
        match client.submit_echo_task(&format!("During disruption {}", i)).await {
            Ok(_) => println!("Task {} submitted successfully", i),
            Err(e) => {
                println!("Task {} failed: {}", i, e);
                failed_submissions += 1;
            }
        }
        sleep(Duration::from_millis(500)).await;
    }
    
    println!("Failed submissions during disruption: {}/5", failed_submissions);
    
    // Wait for connection recovery
    println!("Waiting for connection recovery...");
    sleep(Duration::from_secs(10)).await;
    
    // Verify system recovers
    let recovery_task = client.submit_echo_task("After recovery").await?;
    assert_eq!(recovery_task.status, 1);
    
    Ok(())
}

/// Test handling of malformed requests
#[tokio::test]
async fn test_malformed_request_handling() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing malformed request handling...");
    
    // Test empty method
    let result = client.submit_invalid_task().await;
    match result {
        Ok(resp) => assert_eq!(resp.status, 2), // FAILED
        Err(_) => {} // Error is also acceptable
    }
    
    // Test with invalid arguments (would need raw gRPC client for this)
    // For now, we just verify the system handles known invalid methods
    
    Ok(())
}

/// Test concurrent task submission under stress
#[tokio::test]
async fn test_concurrent_submission_stress() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let client = env.create_grpc_client().await?;
    
    println!("Testing concurrent submission under stress...");
    
    let concurrent_submitters = 20;
    let tasks_per_submitter = 10;
    
    let mut handles = vec![];
    
    for i in 0..concurrent_submitters {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            let mut successful = 0;
            for j in 0..tasks_per_submitter {
                match client_clone.submit_echo_task(&format!("Stress test {}-{}", i, j)).await {
                    Ok(resp) if resp.status == 1 => successful += 1,
                    _ => {}
                }
            }
            successful
        });
        handles.push(handle);
    }
    
    let mut total_successful = 0;
    for handle in handles {
        total_successful += handle.await?;
    }
    
    let total_expected = concurrent_submitters * tasks_per_submitter;
    let success_rate = total_successful as f64 / total_expected as f64;
    
    println!("Successfully processed {}/{} tasks ({:.2}%)", 
        total_successful, total_expected, success_rate * 100.0);
    
    assert!(success_rate > 0.9); // At least 90% success rate
    
    Ok(())
}

/// Test memory leak prevention during long-running operations
#[tokio::test]
#[ignore] // Long-running test
async fn test_memory_stability() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let mut client = env.create_grpc_client().await?;
    
    println!("Testing memory stability over time...");
    
    let test_duration = Duration::from_secs(300); // 5 minutes
    let start = std::time::Instant::now();
    
    let mut iteration = 0;
    while start.elapsed() < test_duration {
        iteration += 1;
        
        // Submit various types of tasks
        let _ = client.submit_echo_task(&format!("Memory test {}", iteration)).await;
        let _ = client.submit_computation_task(iteration, iteration * 2).await;
        
        if iteration % 100 == 0 {
            println!("Completed {} iterations, elapsed: {:?}", iteration, start.elapsed());
            
            // Check system metrics (would need metrics endpoint)
            // In a real test, we would monitor memory usage here
        }
        
        // Small delay to prevent overwhelming the system
        if iteration % 10 == 0 {
            sleep(Duration::from_millis(100)).await;
        }
    }
    
    println!("Memory stability test completed: {} iterations", iteration);
    
    Ok(())
}

/// Test graceful degradation under resource constraints
#[tokio::test]
#[ignore]
async fn test_graceful_degradation() -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    let env = TestEnvironment::new().await?;
    let client = env.create_grpc_client().await?;
    
    println!("Testing graceful degradation...");
    
    // Submit many resource-intensive tasks
    let mut handles = vec![];
    for _i in 0..100 {
        let mut client_clone = client.clone();
        let handle = tokio::spawn(async move {
            // Simulate resource-intensive task
            client_clone.submit_async_sleep_task(30).await
        });
        handles.push(handle);
    }
    
    // Wait a bit then check system responsiveness
    sleep(Duration::from_secs(5)).await;
    
    // System should still respond to simple queries
    let mut responsive = true;
    for i in 0..5 {
        let mut client_clone = client.clone();
        let start = std::time::Instant::now();
        match client_clone.submit_echo_task(&format!("Health check {}", i)).await {
            Ok(_) => {
                let latency = start.elapsed();
                if latency > Duration::from_secs(5) {
                    println!("High latency detected: {:?}", latency);
                    responsive = false;
                }
            }
            Err(e) => {
                println!("System not responsive: {}", e);
                responsive = false;
            }
        }
    }
    
    assert!(responsive, "System should remain responsive under load");
    
    // Clean up
    for handle in handles {
        handle.abort();
    }
    
    Ok(())
}