use async_trait::async_trait;
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use priority_queue::PriorityQueue;
use std::cmp::Reverse;
use std::collections::HashMap;
use std::sync::Arc;
use tracing::{debug, instrument};
use uuid::Uuid;

use crate::traits::{
    ScheduledTask, Scheduler, SchedulerError, SchedulerResult, SchedulerStats, TaskSchedule,
};

/// Priority-based task scheduler
pub struct PriorityScheduler {
    /// Task storage
    tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,

    /// Priority queue for ready tasks
    ready_queue: Arc<RwLock<PriorityQueue<Uuid, i32>>>,

    /// Scheduled tasks (sorted by next execution time)
    scheduled_queue: Arc<RwLock<PriorityQueue<Uuid, Reverse<i64>>>>,
}

impl PriorityScheduler {
    /// Create a new priority scheduler
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(RwLock::new(HashMap::new())),
            ready_queue: Arc::new(RwLock::new(PriorityQueue::new())),
            scheduled_queue: Arc::new(RwLock::new(PriorityQueue::new())),
        }
    }

    /// Check and move scheduled tasks to ready queue
    fn check_scheduled_tasks(&self) {
        let now = Utc::now();
        let mut scheduled = self.scheduled_queue.write();
        let mut ready = self.ready_queue.write();
        let tasks = self.tasks.read();

        // Find tasks that are ready to execute
        let mut ready_tasks = Vec::new();
        let mut tasks_to_remove = Vec::new();

        while let Some((task_id, _)) = scheduled.peek() {
            let task_id = *task_id; // Copy the task_id to avoid borrow issues
            if let Some(task) = tasks.get(&task_id) {
                if let Some(next_exec) = task.next_execution_at {
                    if next_exec <= now {
                        ready_tasks.push(task_id);
                    } else {
                        break; // Queue is sorted, so we can stop here
                    }
                } else {
                    ready_tasks.push(task_id);
                }
            } else {
                // Task was removed - collect for later removal
                tasks_to_remove.push(task_id);
            }
        }

        // Remove tasks that no longer exist
        for task_id in tasks_to_remove {
            scheduled.remove(&task_id);
        }

        // Move tasks to ready queue
        for task_id in ready_tasks {
            if let Some((id, _)) = scheduled.remove(&task_id) {
                if let Some(task) = tasks.get(&id) {
                    ready.push(id, task.priority);
                }
            }
        }
    }

    /// Calculate next execution time for a task
    fn calculate_next_execution(
        schedule: &TaskSchedule,
        last_executed: Option<DateTime<Utc>>,
    ) -> Option<DateTime<Utc>> {
        match schedule {
            TaskSchedule::Immediate => Some(Utc::now()),
            TaskSchedule::At { time } => {
                if last_executed.is_none() && *time > Utc::now() {
                    Some(*time)
                } else {
                    None // One-time task already executed
                }
            }
            TaskSchedule::Delayed { delay_seconds } => {
                let base_time = last_executed.unwrap_or_else(Utc::now);
                Some(base_time + chrono::Duration::seconds(*delay_seconds as i64))
            }
            TaskSchedule::Cron { expression, .. } => {
                // Parse cron expression and calculate next execution
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

impl Default for PriorityScheduler {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Scheduler for PriorityScheduler {
    #[instrument(skip(self, task))]
    async fn add_task(&self, mut task: ScheduledTask) -> SchedulerResult<()> {
        // Calculate next execution time
        task.next_execution_at = Self::calculate_next_execution(&task.schedule, None);

        let task_id = task.id;
        let priority = task.priority;

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
                self.scheduled_queue
                    .write()
                    .push(task_id, Reverse(timestamp));
            }
        } else if matches!(task.schedule, TaskSchedule::Immediate) {
            self.ready_queue.write().push(task_id, priority);
        }

        debug!("Added task {} with priority {}", task_id, priority);
        Ok(())
    }

    #[instrument(skip(self))]
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let removed = self.tasks.write().remove(&task_id);

        if removed.is_some() {
            self.ready_queue.write().remove(&task_id);
            self.scheduled_queue.write().remove(&task_id);
            debug!("Removed task {}", task_id);
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

        // Get tasks from ready queue
        while ready_tasks.len() < limit {
            if let Some((task_id, _priority)) = ready_queue.pop() {
                if let Some(task) = tasks.get(&task_id) {
                    if task.active {
                        ready_tasks.push(task.clone());
                    }
                }
            } else {
                break;
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
                let timestamp = next_exec.timestamp_millis();
                drop(tasks); // Release lock before acquiring another
                self.scheduled_queue
                    .write()
                    .push(task_id, Reverse(timestamp));
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

            // Remove from current queue
            drop(tasks); // Release lock
            self.ready_queue.write().remove(&task_id);
            self.scheduled_queue.write().remove(&task_id);

            // Re-add to appropriate queue
            let tasks = self.tasks.read();
            if let Some(task) = tasks.get(&task_id) {
                if let Some(next_exec) = task.next_execution_at {
                    if next_exec <= Utc::now() {
                        self.ready_queue.write().push(task_id, task.priority);
                    } else {
                        let timestamp = next_exec.timestamp_millis();
                        self.scheduled_queue
                            .write()
                            .push(task_id, Reverse(timestamp));
                    }
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
            debug!("Paused task {}", task_id);
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
            debug!("Resumed task {}", task_id);
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

// Fix missing import
use std::str::FromStr;
