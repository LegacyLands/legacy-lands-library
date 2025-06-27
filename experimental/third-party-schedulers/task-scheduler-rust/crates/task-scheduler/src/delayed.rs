use async_trait::async_trait;
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use std::collections::{BinaryHeap, HashMap};
use std::sync::Arc;
use tracing::{debug, instrument};
use uuid::Uuid;

use crate::traits::{
    ScheduledTask, Scheduler, SchedulerError, SchedulerResult, SchedulerStats, TaskSchedule,
};

/// Delayed task entry for the heap
#[derive(Debug, Clone, Eq, PartialEq)]
struct DelayedTask {
    task_id: Uuid,
    execute_at: DateTime<Utc>,
}

impl Ord for DelayedTask {
    fn cmp(&self, other: &Self) -> std::cmp::Ordering {
        // Reverse order for min-heap behavior
        other.execute_at.cmp(&self.execute_at)
    }
}

impl PartialOrd for DelayedTask {
    fn partial_cmp(&self, other: &Self) -> Option<std::cmp::Ordering> {
        Some(self.cmp(other))
    }
}

/// Delayed execution scheduler
pub struct DelayedScheduler {
    /// Task storage
    tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,

    /// Delayed tasks heap (min-heap by execution time)
    delayed_heap: Arc<RwLock<BinaryHeap<DelayedTask>>>,
}

impl DelayedScheduler {
    /// Create a new delayed scheduler
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(RwLock::new(HashMap::new())),
            delayed_heap: Arc::new(RwLock::new(BinaryHeap::new())),
        }
    }
}

impl Default for DelayedScheduler {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Scheduler for DelayedScheduler {
    #[instrument(skip(self, task))]
    async fn add_task(&self, mut task: ScheduledTask) -> SchedulerResult<()> {
        // Calculate execution time based on schedule
        let execute_at = match &task.schedule {
            TaskSchedule::Immediate => Utc::now(),
            TaskSchedule::At { time } => *time,
            TaskSchedule::Delayed { delay_seconds } => {
                Utc::now() + chrono::Duration::seconds(*delay_seconds as i64)
            }
            TaskSchedule::Cron { .. } | TaskSchedule::Interval { .. } => {
                return Err(SchedulerError::InvalidSchedule(
                    "DelayedScheduler only supports immediate, at, and delayed schedules".into(),
                ));
            }
        };

        task.next_execution_at = Some(execute_at);

        let task_id = task.id;

        // Add to storage
        {
            let mut tasks = self.tasks.write();
            if tasks.contains_key(&task_id) {
                return Err(SchedulerError::TaskAlreadyExists);
            }
            tasks.insert(task_id, task);
        }

        // Add to delayed heap
        self.delayed_heap.write().push(DelayedTask {
            task_id,
            execute_at,
        });

        debug!(
            "Added delayed task {} to execute at {}",
            task_id, execute_at
        );
        Ok(())
    }

    #[instrument(skip(self))]
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let removed = self.tasks.write().remove(&task_id);

        if removed.is_some() {
            // Note: We don't remove from the heap immediately as it's expensive
            // The task will be skipped when popped from the heap
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
        let now = Utc::now();
        let mut ready_tasks = Vec::new();
        let mut heap = self.delayed_heap.write();
        let tasks = self.tasks.read();

        // Pop tasks that are ready
        while ready_tasks.len() < limit {
            if let Some(delayed_task) = heap.peek() {
                if delayed_task.execute_at > now {
                    break; // No more ready tasks
                }

                let delayed_task = heap.pop().unwrap();

                // Check if task still exists and is active
                if let Some(task) = tasks.get(&delayed_task.task_id) {
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
    async fn mark_executed(&self, task_id: Uuid, _success: bool) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();

        if let Some(task) = tasks.get_mut(&task_id) {
            task.last_executed_at = Some(Utc::now());

            // Delayed tasks are typically one-shot, so we don't reschedule
            task.next_execution_at = None;

            debug!("Marked delayed task {} as executed", task_id);
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }

    #[instrument(skip(self))]
    async fn update_schedule(&self, task_id: Uuid, schedule: TaskSchedule) -> SchedulerResult<()> {
        // Validate schedule type
        match &schedule {
            TaskSchedule::Immediate | TaskSchedule::At { .. } | TaskSchedule::Delayed { .. } => {}
            _ => {
                return Err(SchedulerError::InvalidSchedule(
                    "DelayedScheduler only supports immediate, at, and delayed schedules".into(),
                ));
            }
        }

        let mut tasks = self.tasks.write();

        if let Some(task) = tasks.get_mut(&task_id) {
            // Calculate new execution time
            let execute_at = match &schedule {
                TaskSchedule::Immediate => Utc::now(),
                TaskSchedule::At { time } => *time,
                TaskSchedule::Delayed { delay_seconds } => {
                    Utc::now() + chrono::Duration::seconds(*delay_seconds as i64)
                }
                _ => unreachable!(),
            };

            task.schedule = schedule;
            task.next_execution_at = Some(execute_at);

            // Add to heap with new time
            drop(tasks);
            self.delayed_heap.write().push(DelayedTask {
                task_id,
                execute_at,
            });

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
        let heap = self.delayed_heap.read();

        let total_tasks = tasks.len() as u64;
        let active_tasks = tasks.values().filter(|t| t.active).count() as u64;
        let paused_tasks = tasks.values().filter(|t| !t.active).count() as u64;

        let mut tasks_by_type = HashMap::new();
        for task in tasks.values() {
            let type_name = match &task.schedule {
                TaskSchedule::Immediate => "immediate",
                TaskSchedule::At { .. } => "at",
                TaskSchedule::Delayed { .. } => "delayed",
                _ => "other",
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

        // Get next execution from heap
        let next_execution = heap.peek().and_then(|delayed| {
            tasks
                .get(&delayed.task_id)
                .and_then(|t| t.next_execution_at)
        });

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
