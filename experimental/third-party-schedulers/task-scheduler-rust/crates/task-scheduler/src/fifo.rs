use async_trait::async_trait;
use chrono::Utc;
use parking_lot::RwLock;
use std::collections::{HashMap, VecDeque};
use std::sync::Arc;
use tracing::{debug, instrument};
use uuid::Uuid;

use crate::traits::{
    ScheduledTask, Scheduler, SchedulerError, SchedulerResult, SchedulerStats, TaskSchedule,
};

/// First-In-First-Out (FIFO) task scheduler
/// Tasks are executed in the order they are added, regardless of priority
pub struct FifoScheduler {
    /// Task storage
    tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,
    
    /// FIFO queue for ready tasks
    ready_queue: Arc<RwLock<VecDeque<Uuid>>>,
    
    /// Scheduled tasks waiting for their time
    scheduled_tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,
}

impl FifoScheduler {
    /// Create a new FIFO scheduler
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(RwLock::new(HashMap::new())),
            ready_queue: Arc::new(RwLock::new(VecDeque::new())),
            scheduled_tasks: Arc::new(RwLock::new(HashMap::new())),
        }
    }
    
    /// Check and move scheduled tasks to ready queue
    fn check_scheduled_tasks(&self) {
        let now = Utc::now();
        let mut scheduled = self.scheduled_tasks.write();
        let mut ready_queue = self.ready_queue.write();
        
        let mut ready_tasks = Vec::new();
        
        // Find tasks that are ready to execute
        for (task_id, task) in scheduled.iter() {
            if let Some(next_exec) = task.next_execution_at {
                if next_exec <= now && task.active {
                    ready_tasks.push(*task_id);
                }
            }
        }
        
        // Move tasks to ready queue in FIFO order
        for task_id in ready_tasks {
            if scheduled.remove(&task_id).is_some() {
                ready_queue.push_back(task_id);
            }
        }
    }
    
    /// Calculate next execution time for a task
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
}

impl Default for FifoScheduler {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Scheduler for FifoScheduler {
    #[instrument(skip(self, task))]
    async fn add_task(&self, mut task: ScheduledTask) -> SchedulerResult<()> {
        task.next_execution_at = Self::calculate_next_execution(&task.schedule, None);
        
        let task_id = task.id;
        
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
                self.ready_queue.write().push_back(task_id);
            } else {
                self.scheduled_tasks.write().insert(task_id, task);
            }
        } else if matches!(task.schedule, TaskSchedule::Immediate) {
            self.ready_queue.write().push_back(task_id);
        }
        
        debug!("Added task {} to FIFO queue", task_id);
        Ok(())
    }
    
    #[instrument(skip(self))]
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let removed = self.tasks.write().remove(&task_id);
        
        if removed.is_some() {
            // Remove from ready queue
            let mut ready_queue = self.ready_queue.write();
            ready_queue.retain(|id| *id != task_id);
            
            // Remove from scheduled tasks
            self.scheduled_tasks.write().remove(&task_id);
            
            debug!("Removed task {} from FIFO scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    #[instrument(skip(self))]
    async fn get_task(&self, task_id: Uuid) -> SchedulerResult<Option<ScheduledTask>> {
        Ok(self.tasks.read().get(&task_id).cloned())
    }
    
    #[instrument(skip(self))]
    async fn list_tasks(&self) -> SchedulerResult<Vec<ScheduledTask>> {
        Ok(self.tasks.read().values().cloned().collect())
    }
    
    #[instrument(skip(self))]
    async fn get_ready_tasks(&self, limit: usize) -> SchedulerResult<Vec<ScheduledTask>> {
        // First check scheduled tasks
        self.check_scheduled_tasks();
        
        let mut ready_tasks = Vec::new();
        let mut ready_queue = self.ready_queue.write();
        let tasks = self.tasks.read();
        
        // Get tasks from ready queue in FIFO order
        while ready_tasks.len() < limit && !ready_queue.is_empty() {
            if let Some(task_id) = ready_queue.pop_front() {
                if let Some(task) = tasks.get(&task_id) {
                    if task.active {
                        ready_tasks.push(task.clone());
                    }
                }
            }
        }
        
        Ok(ready_tasks)
    }
    
    #[instrument(skip(self))]
    async fn mark_executed(&self, task_id: Uuid, success: bool) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.last_executed_at = Some(Utc::now());
            
            // Calculate next execution time
            task.next_execution_at =
                Self::calculate_next_execution(&task.schedule, task.last_executed_at);
            
            // Re-schedule if needed
            if let Some(next_exec) = task.next_execution_at {
                let task_clone = task.clone();
                drop(tasks);
                
                if next_exec <= Utc::now() {
                    self.ready_queue.write().push_back(task_id);
                } else {
                    self.scheduled_tasks.write().insert(task_id, task_clone);
                }
            }
            
            debug!("Marked task {} as executed (success={})", task_id, success);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    #[instrument(skip(self))]
    async fn update_schedule(&self, task_id: Uuid, schedule: TaskSchedule) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.schedule = schedule;
            task.next_execution_at =
                Self::calculate_next_execution(&task.schedule, task.last_executed_at);
            
            let task_clone = task.clone();
            drop(tasks);
            
            // Remove from current location
            let mut ready_queue = self.ready_queue.write();
            ready_queue.retain(|id| *id != task_id);
            drop(ready_queue);
            
            self.scheduled_tasks.write().remove(&task_id);
            
            // Re-add to appropriate queue
            if let Some(next_exec) = task_clone.next_execution_at {
                if next_exec <= Utc::now() {
                    self.ready_queue.write().push_back(task_id);
                } else {
                    self.scheduled_tasks.write().insert(task_id, task_clone);
                }
            }
            
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    #[instrument(skip(self))]
    async fn pause_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.active = false;
            debug!("Paused task {} in FIFO scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    #[instrument(skip(self))]
    async fn resume_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();
        
        if let Some(task) = tasks.get_mut(&task_id) {
            task.active = true;
            debug!("Resumed task {} in FIFO scheduler", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }
    
    #[instrument(skip(self))]
    async fn get_statistics(&self) -> SchedulerResult<SchedulerStats> {
        let tasks = self.tasks.read();
        
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