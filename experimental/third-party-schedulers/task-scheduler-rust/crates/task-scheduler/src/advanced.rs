use async_trait::async_trait;
use chrono::Utc;
use parking_lot::RwLock;
use priority_queue::PriorityQueue;
use std::cmp::Reverse;
use std::collections::{HashMap, HashSet, VecDeque};
use std::sync::Arc;
use tracing::{debug, info, warn};
use uuid::Uuid;

use crate::traits::{
    ScheduledTask, Scheduler, SchedulerError, SchedulerResult, SchedulerStats, TaskSchedule,
};

/// Worker affinity type
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum WorkerAffinity {
    /// Task prefers specific workers
    PreferWorkers(HashSet<String>),
    /// Task must run on specific workers
    RequireWorkers(HashSet<String>),
    /// Task should avoid specific workers
    AvoidWorkers(HashSet<String>),
    /// No affinity constraints
    None,
}

/// Task metadata for advanced scheduling
#[derive(Debug, Clone)]
pub struct TaskMetadata {
    /// Worker affinity rules
    pub affinity: WorkerAffinity,
    /// Estimated resource usage (0.0 - 1.0)
    pub resource_usage: f64,
    /// Whether this task can be stolen
    pub stealable: bool,
    /// Preferred locality (e.g., region, zone)
    pub locality: Option<String>,
}

/// Worker state for load balancing
#[derive(Debug, Clone)]
pub struct WorkerState {
    /// Worker ID
    pub id: String,
    /// Current load (0.0 - 1.0)
    pub load: f64,
    /// Number of tasks currently assigned
    pub task_count: u32,
    /// Worker capabilities/labels
    pub labels: HashSet<String>,
    /// Worker locality
    pub locality: Option<String>,
    /// Last heartbeat time
    pub last_heartbeat: chrono::DateTime<Utc>,
}

/// Advanced scheduler with load balancing and work stealing
pub struct AdvancedScheduler {
    /// Task storage
    tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,
    
    /// Task metadata
    task_metadata: Arc<RwLock<HashMap<Uuid, TaskMetadata>>>,
    
    /// Priority queue for ready tasks
    ready_queue: Arc<RwLock<PriorityQueue<Uuid, i32>>>,
    
    /// Scheduled tasks
    scheduled_queue: Arc<RwLock<PriorityQueue<Uuid, Reverse<i64>>>>,
    
    /// Worker states
    workers: Arc<RwLock<HashMap<String, WorkerState>>>,
    
    /// Task assignments (task_id -> worker_id)
    assignments: Arc<RwLock<HashMap<Uuid, String>>>,
    
    /// Work stealing queue (tasks that can be stolen)
    steal_queue: Arc<RwLock<VecDeque<Uuid>>>,
    
    /// Configuration
    config: SchedulerConfig,
}

/// Scheduler configuration
#[derive(Debug, Clone)]
pub struct SchedulerConfig {
    /// Enable work stealing
    pub enable_work_stealing: bool,
    /// Load threshold for work stealing (0.0 - 1.0)
    pub steal_threshold: f64,
    /// Maximum load imbalance allowed
    pub max_load_imbalance: f64,
    /// Worker timeout (seconds)
    pub worker_timeout_seconds: u64,
}

impl Default for SchedulerConfig {
    fn default() -> Self {
        Self {
            enable_work_stealing: true,
            steal_threshold: 0.8,
            max_load_imbalance: 0.3,
            worker_timeout_seconds: 60,
        }
    }
}

impl AdvancedScheduler {
    /// Create a new advanced scheduler
    pub fn new(config: SchedulerConfig) -> Self {
        Self {
            tasks: Arc::new(RwLock::new(HashMap::new())),
            task_metadata: Arc::new(RwLock::new(HashMap::new())),
            ready_queue: Arc::new(RwLock::new(PriorityQueue::new())),
            scheduled_queue: Arc::new(RwLock::new(PriorityQueue::new())),
            workers: Arc::new(RwLock::new(HashMap::new())),
            assignments: Arc::new(RwLock::new(HashMap::new())),
            steal_queue: Arc::new(RwLock::new(VecDeque::new())),
            config,
        }
    }
    
    /// Register a worker
    pub fn register_worker(&self, worker: WorkerState) -> SchedulerResult<()> {
        let mut workers = self.workers.write();
        let worker_id = worker.id.clone();
        workers.insert(worker_id.clone(), worker);
        info!("Registered worker: {}", worker_id);
        Ok(())
    }
    
    /// Update worker state
    pub fn update_worker_state(&self, worker_id: &str, load: f64, task_count: u32) -> SchedulerResult<()> {
        let mut workers = self.workers.write();
        if let Some(worker) = workers.get_mut(worker_id) {
            worker.load = load;
            worker.task_count = task_count;
            worker.last_heartbeat = Utc::now();
            debug!("Updated worker {} state: load={}, tasks={}", worker_id, load, task_count);
            Ok(())
        } else {
            Err(SchedulerError::Internal(format!("Worker {} not found", worker_id)))
        }
    }
    
    /// Remove inactive workers
    fn cleanup_inactive_workers(&self) {
        let timeout = chrono::Duration::seconds(self.config.worker_timeout_seconds as i64);
        let cutoff = Utc::now() - timeout;
        
        let mut workers = self.workers.write();
        let inactive: Vec<String> = workers
            .iter()
            .filter(|(_, w)| w.last_heartbeat < cutoff)
            .map(|(id, _)| id.clone())
            .collect();
        
        for worker_id in inactive {
            workers.remove(&worker_id);
            warn!("Removed inactive worker: {}", worker_id);
        }
    }
    
    /// Find best worker for a task based on affinity and load
    fn find_best_worker(&self, task_id: &Uuid) -> Option<String> {
        let metadata = self.task_metadata.read();
        let workers = self.workers.read();
        
        if workers.is_empty() {
            return None;
        }
        
        let task_meta = metadata.get(task_id);
        
        // Filter workers based on affinity
        let eligible_workers: Vec<(&String, &WorkerState)> = workers
            .iter()
            .filter(|(id, _worker)| {
                if let Some(meta) = task_meta {
                    match &meta.affinity {
                        WorkerAffinity::RequireWorkers(set) => set.contains(*id),
                        WorkerAffinity::PreferWorkers(set) => {
                            // Prefer but don't require
                            set.is_empty() || set.contains(*id)
                        }
                        WorkerAffinity::AvoidWorkers(set) => !set.contains(*id),
                        WorkerAffinity::None => true,
                    }
                } else {
                    true
                }
            })
            .collect();
        
        if eligible_workers.is_empty() {
            return None;
        }
        
        // Find worker with lowest load
        let best_worker = eligible_workers
            .into_iter()
            .min_by(|(_, a), (_, b)| {
                // Consider both load and locality
                let a_score = a.load + if task_meta.and_then(|m| m.locality.as_ref()) == a.locality.as_ref() { 0.0 } else { 0.1 };
                let b_score = b.load + if task_meta.and_then(|m| m.locality.as_ref()) == b.locality.as_ref() { 0.0 } else { 0.1 };
                a_score.partial_cmp(&b_score).unwrap()
            });
        
        best_worker.map(|(id, _)| id.clone())
    }
    
    /// Try to steal tasks from overloaded workers
    fn try_work_stealing(&self) -> Vec<(Uuid, String)> {
        if !self.config.enable_work_stealing {
            return Vec::new();
        }
        
        let workers = self.workers.read();
        let assignments = self.assignments.read();
        let steal_queue = self.steal_queue.read();
        
        // Find overloaded and underloaded workers
        let avg_load: f64 = workers.values().map(|w| w.load).sum::<f64>() / workers.len() as f64;
        
        let overloaded: Vec<_> = workers
            .iter()
            .filter(|(_, w)| w.load > self.config.steal_threshold)
            .map(|(id, _)| id.clone())
            .collect();
        
        let underloaded: Vec<_> = workers
            .iter()
            .filter(|(_, w)| w.load < avg_load - self.config.max_load_imbalance)
            .map(|(id, _)| id.clone())
            .collect();
        
        if overloaded.is_empty() || underloaded.is_empty() {
            return Vec::new();
        }
        
        let mut stolen_tasks = Vec::new();
        
        // Try to steal tasks
        for task_id in steal_queue.iter() {
            if let Some(current_worker) = assignments.get(task_id) {
                if overloaded.contains(current_worker) {
                    // Find an underloaded worker for this task
                    if let Some(new_worker) = underloaded.first() {
                        stolen_tasks.push((*task_id, new_worker.clone()));
                    }
                }
            }
        }
        
        stolen_tasks
    }
    
    /// Check and move scheduled tasks to ready queue
    fn check_scheduled_tasks(&self) {
        let now = Utc::now();
        let mut scheduled = self.scheduled_queue.write();
        let mut ready = self.ready_queue.write();
        let tasks = self.tasks.read();
        
        let mut ready_tasks = Vec::new();
        
        while let Some((task_id, _)) = scheduled.peek() {
            let task_id = *task_id;
            if let Some(task) = tasks.get(&task_id) {
                if let Some(next_exec) = task.next_execution_at {
                    if next_exec <= now {
                        ready_tasks.push((task_id, task.priority));
                    } else {
                        break;
                    }
                } else {
                    ready_tasks.push((task_id, task.priority));
                }
            }
        }
        
        for (task_id, priority) in ready_tasks {
            if scheduled.remove(&task_id).is_some() {
                ready.push(task_id, priority);
            }
        }
    }
    
    /// Calculate next execution time
    fn calculate_next_execution(
        schedule: &TaskSchedule,
        last_executed: Option<chrono::DateTime<Utc>>,
    ) -> Option<chrono::DateTime<Utc>> {
        match schedule {
            TaskSchedule::Immediate => Some(Utc::now()),
            TaskSchedule::At { time } => {
                if last_executed.is_none() && *time > Utc::now() {
                    Some(*time)
                } else {
                    None
                }
            }
            TaskSchedule::Delayed { delay_seconds } => {
                let base_time = last_executed.unwrap_or_else(Utc::now);
                Some(base_time + chrono::Duration::seconds(*delay_seconds as i64))
            }
            TaskSchedule::Cron { expression, .. } => {
                if let Ok(schedule) = cron::Schedule::from_str(expression) {
                    let after = last_executed.unwrap_or_else(Utc::now);
                    schedule.after(&after).next()
                } else {
                    None
                }
            }
            TaskSchedule::Interval {
                interval_seconds,
                start_time,
            } => {
                let base_time = last_executed.or(*start_time).unwrap_or_else(Utc::now);
                Some(base_time + chrono::Duration::seconds(*interval_seconds as i64))
            }
        }
    }
    
    /// Add task with metadata
    pub async fn add_task_with_metadata(
        &self,
        task: ScheduledTask,
        metadata: TaskMetadata,
    ) -> SchedulerResult<()> {
        let task_id = task.id;
        self.task_metadata.write().insert(task_id, metadata.clone());
        
        if metadata.stealable {
            self.steal_queue.write().push_back(task_id);
        }
        
        self.add_task(task).await
    }
}

impl Default for AdvancedScheduler {
    fn default() -> Self {
        Self::new(SchedulerConfig::default())
    }
}

#[async_trait]
impl Scheduler for AdvancedScheduler {
    async fn add_task(&self, mut task: ScheduledTask) -> SchedulerResult<()> {
        task.next_execution_at = Self::calculate_next_execution(&task.schedule, None);
        
        let task_id = task.id;
        let priority = task.priority;
        
        // Add default metadata if not exists
        self.task_metadata.write().entry(task_id).or_insert(TaskMetadata {
            affinity: WorkerAffinity::None,
            resource_usage: 0.1,
            stealable: true,
            locality: None,
        });
        
        // Add to task storage
        {
            let mut tasks = self.tasks.write();
            if tasks.contains_key(&task_id) {
                return Err(SchedulerError::TaskAlreadyExists);
            }
            tasks.insert(task_id, task.clone());
        }
        
        // Add to appropriate queue
        if let Some(next_exec) = task.next_execution_at {
            if next_exec <= Utc::now() {
                self.ready_queue.write().push(task_id, priority);
            } else {
                let timestamp = next_exec.timestamp_millis();
                self.scheduled_queue.write().push(task_id, Reverse(timestamp));
            }
        } else if matches!(task.schedule, TaskSchedule::Immediate) {
            self.ready_queue.write().push(task_id, priority);
        }
        
        debug!("Added task {} with priority {} to advanced scheduler", task_id, priority);
        Ok(())
    }
    
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let removed = self.tasks.write().remove(&task_id);
        
        if removed.is_some() {
            self.ready_queue.write().remove(&task_id);
            self.scheduled_queue.write().remove(&task_id);
            self.task_metadata.write().remove(&task_id);
            self.assignments.write().remove(&task_id);
            
            let mut steal_queue = self.steal_queue.write();
            steal_queue.retain(|id| *id != task_id);
            
            debug!("Removed task {} from advanced scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    async fn get_task(&self, task_id: Uuid) -> SchedulerResult<Option<ScheduledTask>> {
        Ok(self.tasks.read().get(&task_id).cloned())
    }
    
    async fn list_tasks(&self) -> SchedulerResult<Vec<ScheduledTask>> {
        Ok(self.tasks.read().values().cloned().collect())
    }
    
    async fn get_ready_tasks(&self, limit: usize) -> SchedulerResult<Vec<ScheduledTask>> {
        // Cleanup inactive workers
        self.cleanup_inactive_workers();
        
        // Check scheduled tasks
        self.check_scheduled_tasks();
        
        // Try work stealing
        let stolen_tasks = self.try_work_stealing();
        for (task_id, new_worker) in stolen_tasks {
            self.assignments.write().insert(task_id, new_worker.clone());
            info!("Stole task {} and assigned to worker {}", task_id, new_worker);
        }
        
        let mut ready_tasks = Vec::new();
        let mut ready_queue = self.ready_queue.write();
        let tasks = self.tasks.read();
        
        while ready_tasks.len() < limit {
            if let Some((task_id, _priority)) = ready_queue.pop() {
                if let Some(task) = tasks.get(&task_id) {
                    if task.active {
                        // Try to assign to best worker
                        if let Some(worker_id) = self.find_best_worker(&task_id) {
                            self.assignments.write().insert(task_id, worker_id.clone());
                            debug!("Assigned task {} to worker {}", task_id, worker_id);
                        }
                        
                        ready_tasks.push(task.clone());
                    }
                }
            } else {
                break;
            }
        }
        
        Ok(ready_tasks)
    }
    
    async fn mark_executed(&self, task_id: Uuid, success: bool) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.last_executed_at = Some(Utc::now());
            
            // Remove assignment
            self.assignments.write().remove(&task_id);
            
            // Calculate next execution time
            task.next_execution_at =
                Self::calculate_next_execution(&task.schedule, task.last_executed_at);
            
            // Re-schedule if needed
            if let Some(next_exec) = task.next_execution_at {
                let timestamp = next_exec.timestamp_millis();
                drop(tasks);
                self.scheduled_queue.write().push(task_id, Reverse(timestamp));
            }
            
            debug!("Marked task {} as executed (success={})", task_id, success);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    async fn update_schedule(&self, task_id: Uuid, schedule: TaskSchedule) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.schedule = schedule;
            task.next_execution_at =
                Self::calculate_next_execution(&task.schedule, task.last_executed_at);
            
            drop(tasks);
            self.ready_queue.write().remove(&task_id);
            self.scheduled_queue.write().remove(&task_id);
            
            let tasks = self.tasks.read();
            if let Some(task) = tasks.get(&task_id) {
                if let Some(next_exec) = task.next_execution_at {
                    if next_exec <= Utc::now() {
                        self.ready_queue.write().push(task_id, task.priority);
                    } else {
                        let timestamp = next_exec.timestamp_millis();
                        self.scheduled_queue.write().push(task_id, Reverse(timestamp));
                    }
                }
            }
            
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    async fn pause_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.active = false;
            debug!("Paused task {} in advanced scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    async fn resume_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.active = true;
            debug!("Resumed task {} in advanced scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    async fn get_statistics(&self) -> SchedulerResult<SchedulerStats> {
        let tasks = self.tasks.read();
        let workers = self.workers.read();
        
        let total_tasks = tasks.len() as u64;
        let active_tasks = tasks.values().filter(|t| t.active).count() as u64;
        let paused_tasks = tasks.values().filter(|t| !t.active).count() as u64;
        
        let mut tasks_by_type = HashMap::new();
        for task in tasks.values() {
            let type_name = match &task.schedule {
                TaskSchedule::Immediate => "immediate",
                TaskSchedule::At { .. } => "at",
                TaskSchedule::Delayed { .. } => "delayed",
                TaskSchedule::Cron { .. } => "cron",
                TaskSchedule::Interval { .. } => "interval",
            };
            *tasks_by_type.entry(type_name.to_string()).or_insert(0) += 1;
        }
        
        let one_hour_ago = Utc::now() - chrono::Duration::hours(1);
        let tasks_executed_last_hour = tasks
            .values()
            .filter(|t| {
                t.last_executed_at
                    .map(|exec| exec > one_hour_ago)
                    .unwrap_or(false)
            })
            .count() as u64;
        
        let next_execution = tasks.values().filter_map(|t| t.next_execution_at).min();
        
        // Log advanced scheduler metrics
        info!("Advanced scheduler metrics:");
        info!("  Active workers: {}", workers.len());
        info!("  Assigned tasks: {}", self.assignments.read().len());
        info!("  Stealable tasks: {}", self.steal_queue.read().len());
        if !workers.is_empty() {
            let avg_load: f64 = workers.values().map(|w| w.load).sum::<f64>() / workers.len() as f64;
            info!("  Average worker load: {:.2}", avg_load);
        }
        
        Ok(SchedulerStats {
            total_tasks,
            active_tasks,
            paused_tasks,
            tasks_by_type,
            tasks_executed_last_hour,
            next_execution,
        })
    }
}

// Import for cron parsing
use std::str::FromStr;