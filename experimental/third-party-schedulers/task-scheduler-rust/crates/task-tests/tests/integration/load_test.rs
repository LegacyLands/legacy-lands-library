use std::sync::Arc;
use std::time::{Duration, Instant};
use tokio::sync::{Mutex, RwLock};
use task_common::{
    queue::QueueManager,
    models::{TaskInfo, TaskMetadata, TaskStatus},
    error::TaskResult,
};
use uuid::Uuid;
use rand::Rng;

/// Simple in-process load test
#[tokio::test]
async fn test_concurrent_task_submission() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = Arc::new(QueueManager::new(&nats_url).await?);
    
    const NUM_TASKS: usize = 100;
    const CONCURRENT_SUBMITTERS: usize = 10;
    
    let start = Instant::now();
    let submitted_tasks = Arc::new(RwLock::new(Vec::new()));
    
    // Spawn concurrent submitters
    let mut handles = vec![];
    for i in 0..CONCURRENT_SUBMITTERS {
        let queue = queue.clone();
        let submitted_tasks = submitted_tasks.clone();
        
        let handle = tokio::spawn(async move {
            let tasks_per_submitter = NUM_TASKS / CONCURRENT_SUBMITTERS;
            let mut local_tasks = vec![];
            
            for j in 0..tasks_per_submitter {
                let task_id = Uuid::new_v4();
                let task = TaskInfo {
                    id: task_id,
                    method: format!("test.load.{}", j % 3),
                    args: vec![serde_json::json!({
                        "submitter": i,
                        "index": j,
                        "data": "x".repeat(1024), // 1KB payload
                    })],
                    metadata: TaskMetadata {
                        priority: rand::thread_rng().gen_range(1..100),
                        ..Default::default()
                    },
                };
                
                if let Ok(()) = queue.submit_task(task.clone()).await {
                    local_tasks.push(task_id);
                }
            }
            
            submitted_tasks.write().await.extend(local_tasks);
        });
        
        handles.push(handle);
    }
    
    // Wait for all submitters
    for handle in handles {
        handle.await.unwrap();
    }
    
    let submission_duration = start.elapsed();
    let task_ids = submitted_tasks.read().await;
    let submission_rate = task_ids.len() as f64 / submission_duration.as_secs_f64();
    
    println!("Submitted {} tasks in {:?}", task_ids.len(), submission_duration);
    println!("Submission rate: {:.2} tasks/second", submission_rate);
    
    assert_eq!(task_ids.len(), NUM_TASKS, "All tasks should be submitted");
    assert!(submission_rate > 50.0, "Should submit at least 50 tasks/second");
    
    Ok(())
}

/// Test system behavior under sustained load
#[tokio::test]
async fn test_sustained_load() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = Arc::new(QueueManager::new(&nats_url).await?);
    
    const TARGET_RPS: u64 = 50;
    const DURATION_SECS: u64 = 10;
    
    let start = Instant::now();
    let stop_time = start + Duration::from_secs(DURATION_SECS);
    let interval = Duration::from_millis(1000 / TARGET_RPS);
    
    let mut total_submitted = 0;
    let mut next_submission = Instant::now();
    
    while Instant::now() < stop_time {
        // Submit task
        let task = TaskInfo {
            id: Uuid::new_v4(),
            method: "test.sustained".to_string(),
            args: vec![serde_json::json!({
                "timestamp": chrono::Utc::now().to_rfc3339(),
                "index": total_submitted,
            })],
            metadata: TaskMetadata::default(),
        };
        
        if queue.submit_task(task).await.is_ok() {
            total_submitted += 1;
        }
        
        // Maintain target rate
        next_submission += interval;
        let now = Instant::now();
        if next_submission > now {
            tokio::time::sleep(next_submission - now).await;
        }
    }
    
    let actual_duration = start.elapsed();
    let actual_rps = total_submitted as f64 / actual_duration.as_secs_f64();
    
    println!("Sustained load test results:");
    println!("  Target RPS: {}", TARGET_RPS);
    println!("  Actual RPS: {:.2}", actual_rps);
    println!("  Total submitted: {}", total_submitted);
    println!("  Duration: {:?}", actual_duration);
    
    // Allow 10% deviation from target
    assert!(
        (actual_rps - TARGET_RPS as f64).abs() / TARGET_RPS as f64 < 0.1,
        "Actual RPS should be within 10% of target"
    );
    
    Ok(())
}

/// Test queue behavior under overload
#[tokio::test]
async fn test_overload_conditions() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = Arc::new(QueueManager::new(&nats_url).await?);
    
    // Submit a burst of high-priority tasks
    let mut high_priority_tasks = vec![];
    for i in 0..50 {
        let task_id = Uuid::new_v4();
        let task = TaskInfo {
            id: task_id,
            method: "test.priority".to_string(),
            args: vec![serde_json::json!({"index": i})],
            metadata: TaskMetadata {
                priority: 90 + (i % 10) as i32, // Priority 90-99
                ..Default::default()
            },
        };
        
        queue.submit_task(task).await?;
        high_priority_tasks.push(task_id);
    }
    
    // Then submit low-priority tasks
    let mut low_priority_tasks = vec![];
    for i in 0..50 {
        let task_id = Uuid::new_v4();
        let task = TaskInfo {
            id: task_id,
            method: "test.priority".to_string(),
            args: vec![serde_json::json!({"index": i})],
            metadata: TaskMetadata {
                priority: 10 + (i % 10) as i32, // Priority 10-19
                ..Default::default()
            },
        };
        
        queue.submit_task(task).await?;
        low_priority_tasks.push(task_id);
    }
    
    // Track completion order
    let completion_order = Arc::new(Mutex::new(Vec::new()));
    let queue_clone = queue.clone();
    let completion_order_clone = completion_order.clone();
    
    tokio::spawn(async move {
        let mut all_tasks = high_priority_tasks.clone();
        all_tasks.extend(low_priority_tasks.clone());
        
        for task_id in all_tasks {
            loop {
                if let Ok(status) = queue_clone.get_task_status(&task_id).await {
                    if matches!(status, TaskStatus::Completed { .. }) {
                        completion_order_clone.lock().await.push(task_id);
                        break;
                    }
                }
                tokio::time::sleep(Duration::from_millis(100)).await;
            }
        }
    });
    
    // Give some time for processing
    tokio::time::sleep(Duration::from_secs(10)).await;
    
    let completed = completion_order.lock().await;
    println!("Completed {} tasks under overload", completed.len());
    
    // Verify that some high-priority tasks completed before low-priority ones
    let high_priority_completed = completed.iter()
        .take(25)
        .filter(|id| high_priority_tasks.contains(id))
        .count();
    
    assert!(
        high_priority_completed > 15,
        "Most early completions should be high-priority tasks"
    );
    
    Ok(())
}

/// Benchmark task throughput
#[tokio::test]
async fn benchmark_task_throughput() -> TaskResult<()> {
    let nats_url = std::env::var("NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
    let queue = QueueManager::new(&nats_url).await?;
    
    // Warm up
    for _ in 0..10 {
        let task = TaskInfo {
            id: Uuid::new_v4(),
            method: "test.warmup".to_string(),
            args: vec![serde_json::json!({})],
            metadata: TaskMetadata::default(),
        };
        queue.submit_task(task).await?;
    }
    
    // Measure submission throughput
    const BATCH_SIZE: usize = 1000;
    let start = Instant::now();
    
    for i in 0..BATCH_SIZE {
        let task = TaskInfo {
            id: Uuid::new_v4(),
            method: "test.benchmark".to_string(),
            args: vec![serde_json::json!({"index": i})],
            metadata: TaskMetadata::default(),
        };
        queue.submit_task(task).await?;
    }
    
    let duration = start.elapsed();
    let throughput = BATCH_SIZE as f64 / duration.as_secs_f64();
    
    println!("Benchmark results:");
    println!("  Tasks submitted: {}", BATCH_SIZE);
    println!("  Duration: {:?}", duration);
    println!("  Throughput: {:.2} tasks/second", throughput);
    println!("  Average latency: {:.2} ms/task", duration.as_millis() as f64 / BATCH_SIZE as f64);
    
    // Basic performance assertion
    assert!(
        throughput > 100.0,
        "Should be able to submit at least 100 tasks/second"
    );
    
    Ok(())
}