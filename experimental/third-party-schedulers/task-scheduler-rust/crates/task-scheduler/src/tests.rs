#[cfg(test)]
mod scheduler_tests {
    use crate::advanced::{AdvancedScheduler, SchedulerConfig, TaskMetadata, WorkerAffinity, WorkerState};
    use crate::fair::FairScheduler;
    use crate::fifo::FifoScheduler;
    use crate::priority::PriorityScheduler;
    use crate::traits::{ScheduledTask, Scheduler, TaskSchedule};
    use chrono::Utc;
    use std::collections::{HashMap, HashSet};
    use uuid::Uuid;

    async fn create_test_task(priority: i32) -> ScheduledTask {
        ScheduledTask {
            id: Uuid::new_v4(),
            method: "test_method".to_string(),
            args: vec![serde_json::json!({"test": true})],
            priority,
            schedule: TaskSchedule::Immediate,
            max_retries: 3,
            timeout_seconds: 60,
            metadata: HashMap::new(),
            active: true,
            created_at: Utc::now(),
            last_executed_at: None,
            next_execution_at: None,
        }
    }

    #[tokio::test]
    async fn test_fifo_scheduler() {
        let scheduler = FifoScheduler::new();
        
        // Add tasks with different priorities
        let task1 = create_test_task(10).await;
        let task2 = create_test_task(5).await;
        let task3 = create_test_task(15).await;
        
        let id1 = task1.id;
        let id2 = task2.id;
        let id3 = task3.id;
        
        scheduler.add_task(task1).await.unwrap();
        scheduler.add_task(task2).await.unwrap();
        scheduler.add_task(task3).await.unwrap();
        
        // Get ready tasks - should be in FIFO order regardless of priority
        let ready = scheduler.get_ready_tasks(3).await.unwrap();
        assert_eq!(ready.len(), 3);
        assert_eq!(ready[0].id, id1);
        assert_eq!(ready[1].id, id2);
        assert_eq!(ready[2].id, id3);
    }

    #[tokio::test]
    async fn test_priority_scheduler() {
        let scheduler = PriorityScheduler::new();
        
        // Add tasks with different priorities
        let task1 = create_test_task(10).await;
        let task2 = create_test_task(5).await;
        let task3 = create_test_task(15).await;
        
        let id1 = task1.id;
        let id2 = task2.id;
        let id3 = task3.id;
        
        scheduler.add_task(task1).await.unwrap();
        scheduler.add_task(task2).await.unwrap();
        scheduler.add_task(task3).await.unwrap();
        
        // Get ready tasks - should be in priority order (highest first)
        let ready = scheduler.get_ready_tasks(3).await.unwrap();
        assert_eq!(ready.len(), 3);
        assert_eq!(ready[0].id, id3); // priority 15
        assert_eq!(ready[1].id, id1); // priority 10
        assert_eq!(ready[2].id, id2); // priority 5
    }

    #[tokio::test]
    async fn test_fair_scheduler() {
        let scheduler = FairScheduler::new();
        
        // Add multiple tasks with different priorities
        let mut tasks = vec![];
        for i in 0..3 {
            for priority in [5, 10, 15] {
                let mut task = create_test_task(priority).await;
                task.method = format!("method_{}_{}", priority, i);
                tasks.push((task.id, priority));
                scheduler.add_task(task).await.unwrap();
            }
        }
        
        // Get ready tasks multiple times
        let mut priority_counts: HashMap<i32, u32> = HashMap::new();
        
        for _ in 0..9 {
            let ready = scheduler.get_ready_tasks(1).await.unwrap();
            if let Some(task) = ready.first() {
                *priority_counts.entry(task.priority).or_insert(0) += 1;
            }
        }
        
        // Check that all priority levels got some tasks (fairness)
        assert!(priority_counts.contains_key(&5));
        assert!(priority_counts.contains_key(&10));
        assert!(priority_counts.contains_key(&15));
        
        // Higher priority should still get more tasks, but not all
        assert!(priority_counts[&15] >= priority_counts[&5]);
    }

    #[tokio::test]
    async fn test_advanced_scheduler_affinity() {
        let config = SchedulerConfig::default();
        let scheduler = AdvancedScheduler::new(config);
        
        // Register workers
        let worker1 = WorkerState {
            id: "worker1".to_string(),
            load: 0.2,
            task_count: 1,
            labels: ["gpu", "high-memory"].iter().cloned().map(String::from).collect(),
            locality: Some("us-west".to_string()),
            last_heartbeat: Utc::now(),
        };
        
        let worker2 = WorkerState {
            id: "worker2".to_string(),
            load: 0.5,
            task_count: 3,
            labels: ["cpu-only"].iter().cloned().map(String::from).collect(),
            locality: Some("us-east".to_string()),
            last_heartbeat: Utc::now(),
        };
        
        scheduler.register_worker(worker1).unwrap();
        scheduler.register_worker(worker2).unwrap();
        
        // Add task with worker affinity
        let task = create_test_task(10).await;
        let task_id = task.id;
        
        let metadata = TaskMetadata {
            affinity: WorkerAffinity::RequireWorkers(["worker1".to_string()].iter().cloned().collect()),
            resource_usage: 0.3,
            stealable: true,
            locality: Some("us-west".to_string()),
        };
        
        scheduler.add_task_with_metadata(task, metadata).await.unwrap();
        
        // Task should be assigned to worker1 despite worker2 having lower load
        let ready = scheduler.get_ready_tasks(1).await.unwrap();
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, task_id);
    }

    #[tokio::test]
    async fn test_advanced_scheduler_work_stealing() {
        let mut config = SchedulerConfig::default();
        config.enable_work_stealing = true;
        config.steal_threshold = 0.7;
        
        let scheduler = AdvancedScheduler::new(config);
        
        // Register workers with different loads
        let worker1 = WorkerState {
            id: "worker1".to_string(),
            load: 0.9, // Overloaded
            task_count: 10,
            labels: HashSet::new(),
            locality: None,
            last_heartbeat: Utc::now(),
        };
        
        let worker2 = WorkerState {
            id: "worker2".to_string(),
            load: 0.2, // Underloaded
            task_count: 2,
            labels: HashSet::new(),
            locality: None,
            last_heartbeat: Utc::now(),
        };
        
        scheduler.register_worker(worker1).unwrap();
        scheduler.register_worker(worker2).unwrap();
        
        // Add stealable tasks
        for _i in 0..5 {
            let task = create_test_task(10).await;
            let metadata = TaskMetadata {
                affinity: WorkerAffinity::None,
                resource_usage: 0.1,
                stealable: true,
                locality: None,
            };
            scheduler.add_task_with_metadata(task, metadata).await.unwrap();
        }
        
        // Get tasks - work stealing should distribute them
        let ready = scheduler.get_ready_tasks(5).await.unwrap();
        assert_eq!(ready.len(), 5);
    }

    #[tokio::test]
    async fn test_scheduler_pause_resume() {
        let scheduler = PriorityScheduler::new();
        
        let task = create_test_task(10).await;
        let task_id = task.id;
        
        scheduler.add_task(task).await.unwrap();
        
        // Pause task
        scheduler.pause_task(task_id).await.unwrap();
        
        // Should not get paused tasks
        let ready = scheduler.get_ready_tasks(10).await.unwrap();
        assert_eq!(ready.len(), 0);
        
        // Resume task
        scheduler.resume_task(task_id).await.unwrap();
        
        // Should now get the task
        let ready = scheduler.get_ready_tasks(10).await.unwrap();
        assert_eq!(ready.len(), 1);
        assert_eq!(ready[0].id, task_id);
    }

    #[tokio::test]
    async fn test_scheduler_statistics() {
        let scheduler = FairScheduler::new();
        
        // Add various types of tasks
        let mut task = create_test_task(10).await;
        scheduler.add_task(task.clone()).await.unwrap();
        
        task.id = Uuid::new_v4();
        task.schedule = TaskSchedule::Delayed { delay_seconds: 60 };
        scheduler.add_task(task.clone()).await.unwrap();
        
        task.id = Uuid::new_v4();
        task.schedule = TaskSchedule::Cron {
            expression: "0 0 * * *".to_string(),
            timezone: None,
        };
        scheduler.add_task(task).await.unwrap();
        
        // Get statistics
        let stats = scheduler.get_statistics().await.unwrap();
        
        assert_eq!(stats.total_tasks, 3);
        assert_eq!(stats.active_tasks, 3);
        assert_eq!(stats.paused_tasks, 0);
        assert!(stats.tasks_by_type.contains_key("immediate"));
        assert!(stats.tasks_by_type.contains_key("delayed"));
        assert!(stats.tasks_by_type.contains_key("cron"));
    }
}