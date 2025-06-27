use async_trait::async_trait;
use chrono::{DateTime, Utc};
use parking_lot::RwLock;
use std::collections::HashMap;
use std::str::FromStr;
use std::sync::Arc;
use tracing::{debug, error, instrument};
use uuid::Uuid;

use crate::traits::{
    ScheduledTask, Scheduler, SchedulerError, SchedulerResult, SchedulerStats, TaskSchedule,
};

/// Cron-based task scheduler
pub struct CronScheduler {
    /// Task storage
    tasks: Arc<RwLock<HashMap<Uuid, ScheduledTask>>>,
}

impl CronScheduler {
    /// Create a new cron scheduler
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Parse and validate cron expression
    fn validate_cron_expression(expression: &str) -> SchedulerResult<cron::Schedule> {
        cron::Schedule::from_str(expression)
            .map_err(|e| SchedulerError::InvalidSchedule(format!("Invalid cron expression: {}", e)))
    }

    /// Calculate next execution time for a cron schedule
    fn calculate_next_cron_execution(
        expression: &str,
        after: DateTime<Utc>,
    ) -> SchedulerResult<Option<DateTime<Utc>>> {
        let schedule = Self::validate_cron_expression(expression)?;
        Ok(schedule.after(&after).next())
    }
}

impl Default for CronScheduler {
    fn default() -> Self {
        Self::new()
    }
}

#[async_trait]
impl Scheduler for CronScheduler {
    #[instrument(skip(self, task))]
    async fn add_task(&self, mut task: ScheduledTask) -> SchedulerResult<()> {
        // Validate that this is a cron task
        let cron_expr = match &task.schedule {
            TaskSchedule::Cron { expression, .. } => expression.clone(),
            _ => {
                return Err(SchedulerError::InvalidSchedule(
                    "CronScheduler only supports cron schedules".into(),
                ));
            }
        };

        // Validate cron expression
        Self::validate_cron_expression(&cron_expr)?;

        // Calculate next execution
        task.next_execution_at = Self::calculate_next_cron_execution(&cron_expr, Utc::now())?;

        let task_id = task.id;

        // Add to storage
        {
            let mut tasks = self.tasks.write();
            if tasks.contains_key(&task_id) {
                return Err(SchedulerError::TaskAlreadyExists);
            }
            tasks.insert(task_id, task);
        }

        debug!(
            "Added cron task {} with expression '{}'",
            task_id, cron_expr
        );
        Ok(())
    }

    #[instrument(skip(self))]
    async fn remove_task(&self, task_id: Uuid) -> SchedulerResult<()> {
        let removed = self.tasks.write().remove(&task_id);

        if removed.is_some() {
            debug!("Removed cron task {}", task_id);
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
        let tasks = self.tasks.read();

        let mut ready_tasks: Vec<_> = tasks
            .values()
            .filter(|task| {
                task.active
                    && task
                        .next_execution_at
                        .map(|next| next <= now)
                        .unwrap_or(false)
            })
            .take(limit)
            .cloned()
            .collect();

        // Sort by priority (higher first) and then by next execution time
        ready_tasks.sort_by(|a, b| {
            b.priority
                .cmp(&a.priority)
                .then_with(|| a.next_execution_at.cmp(&b.next_execution_at))
        });

        ready_tasks.truncate(limit);
        Ok(ready_tasks)
    }

    #[instrument(skip(self))]
    async fn mark_executed(&self, task_id: Uuid, success: bool) -> SchedulerResult<()> {
        let mut tasks = self.tasks.write();

        if let Some(task) = tasks.get_mut(&task_id) {
            task.last_executed_at = Some(Utc::now());

            // Calculate next execution time
            if let TaskSchedule::Cron { expression, .. } = &task.schedule {
                match Self::calculate_next_cron_execution(expression, Utc::now()) {
                    Ok(next) => task.next_execution_at = next,
                    Err(e) => {
                        error!(
                            "Failed to calculate next execution for task {}: {}",
                            task_id, e
                        );
                        task.next_execution_at = None;
                    }
                }
            }

            debug!(
                "Marked cron task {} as executed (success={})",
                task_id, success
            );
            Ok(())
        } else {
            Err(SchedulerError::TaskNotFound)
        }
    }

    #[instrument(skip(self))]
    async fn update_schedule(&self, task_id: Uuid, schedule: TaskSchedule) -> SchedulerResult<()> {
        // Validate that this is a cron schedule
        let cron_expr = match &schedule {
            TaskSchedule::Cron { expression, .. } => expression.clone(),
            _ => {
                return Err(SchedulerError::InvalidSchedule(
                    "CronScheduler only supports cron schedules".into(),
                ));
            }
        };

        // Validate expression
        Self::validate_cron_expression(&cron_expr)?;

        let mut tasks = self.tasks.write();

        if let Some(task) = tasks.get_mut(&task_id) {
            task.schedule = schedule;
            task.next_execution_at = Self::calculate_next_cron_execution(&cron_expr, Utc::now())?;

            debug!("Updated cron schedule for task {}", task_id);
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
            debug!("Paused cron task {}", task_id);
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

            // Recalculate next execution when resuming
            if let TaskSchedule::Cron { expression, .. } = &task.schedule {
                match Self::calculate_next_cron_execution(expression, Utc::now()) {
                    Ok(next) => task.next_execution_at = next,
                    Err(e) => {
                        error!(
                            "Failed to calculate next execution for task {}: {}",
                            task_id, e
                        );
                        return Err(e);
                    }
                }
            }

            debug!("Resumed cron task {}", task_id);
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
        tasks_by_type.insert("cron".to_string(), total_tasks);

        let one_hour_ago = Utc::now() - chrono::Duration::hours(1);
        let tasks_executed_last_hour = tasks
            .values()
            .filter(|t| {
                t.last_executed_at
                    .map(|exec| exec > one_hour_ago)
                    .unwrap_or(false)
            })
            .count() as u64;

        let next_execution = tasks
            .values()
            .filter(|t| t.active)
            .filter_map(|t| t.next_execution_at)
            .min();

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
