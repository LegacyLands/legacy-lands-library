use task_scheduler::{
    AdvancedScheduler, FairScheduler, FifoScheduler, PriorityScheduler,
    ScheduledTask, Scheduler, SchedulerConfig, TaskMetadata, TaskSchedule,
    WorkerAffinity, WorkerState,
};
use chrono::Utc;
use std::collections::{HashMap, HashSet};
use uuid::Uuid;

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    // Initialize logging
    tracing_subscriber::fmt::init();
    
    println!("Advanced Task Scheduler Demonstration");
    println!("=====================================\n");
    
    // Create different scheduler types
    demo_fifo_scheduler().await?;
    demo_priority_scheduler().await?;
    demo_fair_scheduler().await?;
    demo_advanced_scheduler().await?;
    
    Ok(())
}

async fn demo_fifo_scheduler() -> Result<(), Box<dyn std::error::Error>> {
    println!("1. FIFO Scheduler Demo");
    println!("---------------------");
    
    let scheduler = FifoScheduler::new();
    
    // Add tasks with different priorities
    for i in 0..5 {
        let task = ScheduledTask {
            id: Uuid::new_v4(),
            method: format!("task_{}", i),
            args: vec![serde_json::json!({"index": i})],
            priority: (5 - i) * 10, // Higher priority for earlier tasks
            schedule: TaskSchedule::Immediate,
            max_retries: 3,
            timeout_seconds: 60,
            metadata: HashMap::new(),
            active: true,
            created_at: Utc::now(),
            last_executed_at: None,
            next_execution_at: None,
        };
        
        scheduler.add_task(task).await?;
        println!("  Added task_{} with priority {}", i, (5 - i) * 10);
    }
    
    // Get tasks - should be in FIFO order
    println!("\n  Getting tasks (FIFO order):");
    let ready_tasks = scheduler.get_ready_tasks(5).await?;
    for (idx, task) in ready_tasks.iter().enumerate() {
        println!("    {}: {} (priority: {})", idx + 1, task.method, task.priority);
    }
    
    println!();
    Ok(())
}

async fn demo_priority_scheduler() -> Result<(), Box<dyn std::error::Error>> {
    println!("2. Priority Scheduler Demo");
    println!("-------------------------");
    
    let scheduler = PriorityScheduler::new();
    
    // Add tasks with different priorities
    let priorities = [5, 20, 10, 15, 1];
    for (i, &priority) in priorities.iter().enumerate() {
        let task = ScheduledTask {
            id: Uuid::new_v4(),
            method: format!("task_{}", i),
            args: vec![serde_json::json!({"index": i})],
            priority,
            schedule: TaskSchedule::Immediate,
            max_retries: 3,
            timeout_seconds: 60,
            metadata: HashMap::new(),
            active: true,
            created_at: Utc::now(),
            last_executed_at: None,
            next_execution_at: None,
        };
        
        scheduler.add_task(task).await?;
        println!("  Added task_{} with priority {}", i, priority);
    }
    
    // Get tasks - should be in priority order
    println!("\n  Getting tasks (priority order):");
    let ready_tasks = scheduler.get_ready_tasks(5).await?;
    for (idx, task) in ready_tasks.iter().enumerate() {
        println!("    {}: {} (priority: {})", idx + 1, task.method, task.priority);
    }
    
    println!();
    Ok(())
}

async fn demo_fair_scheduler() -> Result<(), Box<dyn std::error::Error>> {
    println!("3. Fair Scheduler Demo");
    println!("---------------------");
    
    let scheduler = FairScheduler::new();
    
    // Add tasks with three different priority levels
    let priority_levels = [5, 10, 15];
    for round in 0..3 {
        for &priority in &priority_levels {
            let task = ScheduledTask {
                id: Uuid::new_v4(),
                method: format!("task_p{}_r{}", priority, round),
                args: vec![serde_json::json!({"priority": priority, "round": round})],
                priority,
                schedule: TaskSchedule::Immediate,
                max_retries: 3,
                timeout_seconds: 60,
                metadata: HashMap::new(),
                active: true,
                created_at: Utc::now(),
                last_executed_at: None,
                next_execution_at: None,
            };
            
            scheduler.add_task(task).await?;
        }
    }
    
    println!("  Added 9 tasks across 3 priority levels (5, 10, 15)");
    
    // Get tasks multiple times to see fair distribution
    println!("\n  Getting tasks with fair distribution:");
    for batch in 0..3 {
        println!("  Batch {}:", batch + 1);
        let ready_tasks = scheduler.get_ready_tasks(3).await?;
        for task in ready_tasks {
            println!("    - {} (priority: {})", task.method, task.priority);
        }
    }
    
    println!();
    Ok(())
}

async fn demo_advanced_scheduler() -> Result<(), Box<dyn std::error::Error>> {
    println!("4. Advanced Scheduler Demo");
    println!("-------------------------");
    
    // Create scheduler with custom config
    let config = SchedulerConfig {
        enable_work_stealing: true,
        steal_threshold: 0.7,
        max_load_imbalance: 0.3,
        worker_timeout_seconds: 60,
    };
    
    let scheduler = AdvancedScheduler::new(config);
    
    // Register workers with different capabilities
    let gpu_worker = WorkerState {
        id: "gpu-worker-1".to_string(),
        load: 0.3,
        task_count: 2,
        labels: ["gpu", "cuda", "high-memory"].iter().cloned().map(String::from).collect(),
        locality: Some("us-west-2a".to_string()),
        last_heartbeat: Utc::now(),
    };
    
    let cpu_worker = WorkerState {
        id: "cpu-worker-1".to_string(),
        load: 0.6,
        task_count: 5,
        labels: ["cpu", "general"].iter().cloned().map(String::from).collect(),
        locality: Some("us-west-2b".to_string()),
        last_heartbeat: Utc::now(),
    };
    
    let cpu_worker2 = WorkerState {
        id: "cpu-worker-2".to_string(),
        load: 0.9, // Overloaded
        task_count: 10,
        labels: ["cpu", "general"].iter().cloned().map(String::from).collect(),
        locality: Some("us-west-2a".to_string()),
        last_heartbeat: Utc::now(),
    };
    
    scheduler.register_worker(gpu_worker)?;
    scheduler.register_worker(cpu_worker)?;
    scheduler.register_worker(cpu_worker2)?;
    
    println!("  Registered 3 workers:");
    println!("    - gpu-worker-1 (load: 30%, GPU capable)");
    println!("    - cpu-worker-1 (load: 60%, CPU only)");
    println!("    - cpu-worker-2 (load: 90%, CPU only, overloaded)");
    
    // Add tasks with different affinities
    
    // Task 1: GPU-required task
    let gpu_task = ScheduledTask {
        id: Uuid::new_v4(),
        method: "train_model".to_string(),
        args: vec![serde_json::json!({"model": "resnet50"})],
        priority: 20,
        schedule: TaskSchedule::Immediate,
        max_retries: 3,
        timeout_seconds: 3600,
        metadata: HashMap::new(),
        active: true,
        created_at: Utc::now(),
        last_executed_at: None,
        next_execution_at: None,
    };
    
    let gpu_metadata = TaskMetadata {
        affinity: WorkerAffinity::RequireWorkers(
            ["gpu-worker-1".to_string()].iter().cloned().collect()
        ),
        resource_usage: 0.5,
        stealable: false,
        locality: Some("us-west-2a".to_string()),
    };
    
    scheduler.add_task_with_metadata(gpu_task, gpu_metadata).await?;
    println!("\n  Added GPU-required task: train_model");
    
    // Task 2: CPU task with locality preference
    let cpu_task = ScheduledTask {
        id: Uuid::new_v4(),
        method: "process_data".to_string(),
        args: vec![serde_json::json!({"dataset": "users"})],
        priority: 15,
        schedule: TaskSchedule::Immediate,
        max_retries: 3,
        timeout_seconds: 300,
        metadata: HashMap::new(),
        active: true,
        created_at: Utc::now(),
        last_executed_at: None,
        next_execution_at: None,
    };
    
    let cpu_metadata = TaskMetadata {
        affinity: WorkerAffinity::None,
        resource_usage: 0.2,
        stealable: true,
        locality: Some("us-west-2a".to_string()),
    };
    
    scheduler.add_task_with_metadata(cpu_task, cpu_metadata).await?;
    println!("  Added CPU task with locality preference: process_data");
    
    // Task 3: General task that can be stolen
    for i in 0..3 {
        let general_task = ScheduledTask {
            id: Uuid::new_v4(),
            method: format!("general_task_{}", i),
            args: vec![serde_json::json!({"index": i})],
            priority: 10,
            schedule: TaskSchedule::Immediate,
            max_retries: 3,
            timeout_seconds: 60,
            metadata: HashMap::new(),
            active: true,
            created_at: Utc::now(),
            last_executed_at: None,
            next_execution_at: None,
        };
        
        let general_metadata = TaskMetadata {
            affinity: WorkerAffinity::None,
            resource_usage: 0.1,
            stealable: true,
            locality: None,
        };
        
        scheduler.add_task_with_metadata(general_task, general_metadata).await?;
    }
    println!("  Added 3 general tasks that can be stolen");
    
    // Get ready tasks - should show intelligent assignment
    println!("\n  Task assignment with load balancing:");
    let ready_tasks = scheduler.get_ready_tasks(5).await?;
    for task in ready_tasks {
        println!("    - {} (priority: {})", task.method, task.priority);
    }
    
    // Show scheduler statistics
    let stats = scheduler.get_statistics().await?;
    println!("\n  Scheduler Statistics:");
    println!("    Total tasks: {}", stats.total_tasks);
    println!("    Active tasks: {}", stats.active_tasks);
    println!("    Task types: {:?}", stats.tasks_by_type);
    
    // Demonstrate work stealing
    println!("\n  Simulating work stealing scenario:");
    
    // Update worker loads to trigger work stealing
    scheduler.update_worker_state("cpu-worker-2", 0.95, 12)?;
    scheduler.update_worker_state("cpu-worker-1", 0.2, 2)?;
    
    println!("    Updated worker loads:");
    println!("    - cpu-worker-2: 95% (should trigger work stealing)");
    println!("    - cpu-worker-1: 20% (can accept stolen tasks)");
    
    // Get more tasks to trigger work stealing
    let _ = scheduler.get_ready_tasks(3).await?;
    
    println!("\n  Work stealing should redistribute tasks from overloaded workers");
    
    println!();
    Ok(())
}