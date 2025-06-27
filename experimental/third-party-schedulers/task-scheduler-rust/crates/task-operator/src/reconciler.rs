use k8s_openapi::api::batch::v1::Job;
use kube::{
    api::{Api, DeleteParams, Patch, PatchParams},
    runtime::controller::Action,
    Client, ResourceExt,
};
use serde_json::json;
use std::sync::Arc;
use task_common::{
    crd::{Task, TaskCondition, TaskPhase},
    events::TaskEvent,
    queue::{QueueManager, QueuedTask},
    Uuid,
};
use tokio::time::Duration;
use tracing::{debug, error, info, instrument, warn};

/// Context for reconciliation
pub struct ReconcileContext {
    pub client: Client,
    pub namespace: String,
    pub queue: Arc<QueueManager>,
}

/// Task reconciler handles the actual reconciliation logic
pub struct TaskReconciler {
    client: Client,
    namespace: String,
    queue: Arc<QueueManager>,
}

impl TaskReconciler {
    /// Create a new task reconciler
    pub fn new(client: Client, namespace: String, queue: Arc<QueueManager>) -> Self {
        Self {
            client,
            namespace,
            queue,
        }
    }

    /// Reconcile a pending task
    #[instrument(skip(self), fields(task_name = %task.name_any()))]
    pub async fn reconcile_pending(
        &self,
        task: &Task,
    ) -> Result<Action, crate::controller::ReconcileError> {
        info!("Reconciling pending task");

        // Validate dependencies
        if !task.spec.dependencies.is_empty() {
            debug!("Checking {} dependencies", task.spec.dependencies.len());

            let api: Api<Task> = Api::namespaced(self.client.clone(), &self.namespace);

            for dep_name in &task.spec.dependencies {
                match api.get(dep_name).await {
                    Ok(dep_task) => {
                        let dep_phase = dep_task
                            .status
                            .as_ref()
                            .map(|s| &s.phase)
                            .unwrap_or(&TaskPhase::Pending);

                        if dep_phase != &TaskPhase::Succeeded {
                            debug!(
                                "Dependency {} is in phase {:?}, waiting",
                                dep_name, dep_phase
                            );

                            // Update status to show waiting for dependencies
                            self.update_status(
                                task,
                                TaskPhase::Pending,
                                Some(format!("Waiting for dependency: {}", dep_name)),
                            )
                            .await?;

                            // Requeue to check again later
                            return Ok(Action::requeue(Duration::from_secs(30)));
                        }
                    }
                    Err(e) => {
                        warn!("Dependency {} not found: {}", dep_name, e);

                        // Update status to show missing dependency
                        self.update_status(
                            task,
                            TaskPhase::Failed,
                            Some(format!("Dependency not found: {}", dep_name)),
                        )
                        .await?;

                        return Ok(Action::await_change());
                    }
                }
            }

            info!("All dependencies satisfied");
        }

        // Queue the task
        info!("Starting to queue task {}", task.name_any());

        let task_id =
            Uuid::parse_str(&task.uid().unwrap_or_default()).unwrap_or_else(|_| Uuid::new_v4());
        info!("Task ID: {}", task_id);

        // Convert dependency names to UUIDs (for now, we'll use empty vec)
        // TODO: Implement proper dependency resolution from names to UUIDs
        let dependencies = Vec::new();

        let queued_task = QueuedTask {
            task_id,
            method: task.spec.method.clone(),
            args: task.spec.args.clone(),
            priority: task.spec.priority,
            retry_count: 0,
            max_retries: task.spec.retry_policy.max_retries,
            timeout_seconds: task.spec.timeout_seconds,
            metadata: task.spec.metadata.clone(),
            dependencies,
        };

        info!(
            "Queueing task {} with method '{}' and priority {}",
            task.name_any(),
            task.spec.method,
            task.spec.priority
        );

        // Actually queue the task
        info!("Calling queue.queue_task...");
        match self.queue.queue_task(queued_task).await {
            Ok(_) => {
                info!("Successfully queued task {}", task.name_any());
            }
            Err(e) => {
                error!("Failed to queue task {}: {}", task.name_any(), e);
                return Err(crate::controller::ReconcileError::Task(e));
            }
        }

        info!("Task queued, now updating status...");

        // Update status to queued
        self.update_status(
            task,
            TaskPhase::Queued,
            Some("Task queued for execution".to_string()),
        )
        .await?;

        // Send queued event
        let event = TaskEvent::Queued {
            task_id,
            queue_name: "default".to_string(),
            position: None,
        };

        if let Err(e) = self
            .queue
            .publish_event(event, "task-operator".to_string())
            .await
        {
            warn!("Failed to publish task queued event: {}", e);
        }

        Ok(Action::requeue(Duration::from_secs(5)))
    }

    /// Reconcile a queued task
    #[instrument(skip(self), fields(task_name = %task.name_any()))]
    pub async fn reconcile_queued(
        &self,
        task: &Task,
    ) -> Result<Action, crate::controller::ReconcileError> {
        info!("Reconciling queued task");

        // In the K8s operator model, we'll create a Job for the task
        // The worker will pick it up from the queue and execute it

        // For now, just wait for the task to be picked up
        // In a real implementation, you might want to check queue depth,
        // worker availability, etc.

        Ok(Action::requeue(Duration::from_secs(30)))
    }

    /// Reconcile a running task
    #[instrument(skip(self), fields(task_name = %task.name_any()))]
    pub async fn reconcile_running(
        &self,
        task: &Task,
    ) -> Result<Action, crate::controller::ReconcileError> {
        info!("Reconciling running task");

        // Check if there's an associated Job
        if let Some(status) = &task.status {
            if let Some(job_ref) = &status.job_ref {
                // Check job status
                let job_api: Api<Job> = Api::namespaced(self.client.clone(), &job_ref.namespace);

                match job_api.get(&job_ref.name).await {
                    Ok(job) => {
                        if let Some(job_status) = &job.status {
                            debug!(
                                "Job status: active={}, succeeded={}, failed={}",
                                job_status.active.unwrap_or(0),
                                job_status.succeeded.unwrap_or(0),
                                job_status.failed.unwrap_or(0)
                            );

                            if job_status.succeeded.unwrap_or(0) > 0 {
                                // Job succeeded
                                self.update_status(
                                    task,
                                    TaskPhase::Succeeded,
                                    Some("Job completed successfully".to_string()),
                                )
                                .await?;

                                return Ok(Action::await_change());
                            } else if job_status.failed.unwrap_or(0) > 0 {
                                // Job failed
                                let message = job_status
                                    .conditions
                                    .as_ref()
                                    .and_then(|conditions| {
                                        conditions
                                            .iter()
                                            .find(|c| c.type_ == "Failed")
                                            .and_then(|c| c.message.clone())
                                    })
                                    .unwrap_or_else(|| "Job failed".to_string());

                                self.update_status(task, TaskPhase::Failed, Some(message))
                                    .await?;

                                return Ok(Action::await_change());
                            }
                        }
                    }
                    Err(kube::Error::Api(e)) if e.code == 404 => {
                        warn!(
                            "Job {} not found, task may have been cleaned up",
                            job_ref.name
                        );

                        // Job was deleted, mark task as failed
                        self.update_status(
                            task,
                            TaskPhase::Failed,
                            Some("Job was deleted".to_string()),
                        )
                        .await?;

                        return Ok(Action::await_change());
                    }
                    Err(e) => {
                        error!("Failed to get job status: {}", e);
                        return Err(crate::controller::ReconcileError::Kubernetes(e));
                    }
                }
            }
        }

        // Check timeout
        if let Some(status) = &task.status {
            if let Some(start_time) = &status.start_time {
                let elapsed = chrono::Utc::now().signed_duration_since(*start_time);
                if elapsed.num_seconds() > task.spec.timeout_seconds as i64 {
                    warn!("Task timed out after {} seconds", elapsed.num_seconds());

                    self.update_status(
                        task,
                        TaskPhase::Failed,
                        Some(format!(
                            "Task timed out after {} seconds",
                            task.spec.timeout_seconds
                        )),
                    )
                    .await?;

                    return Ok(Action::await_change());
                }
            }
        }

        Ok(Action::requeue(Duration::from_secs(10)))
    }

    /// Reconcile a completed task
    #[instrument(skip(self), fields(task_name = %task.name_any()))]
    pub async fn reconcile_completed(
        &self,
        task: &Task,
    ) -> Result<Action, crate::controller::ReconcileError> {
        info!("Reconciling completed task");

        // Clean up any associated Jobs
        if let Some(status) = &task.status {
            if let Some(job_ref) = &status.job_ref {
                let job_api: Api<Job> = Api::namespaced(self.client.clone(), &job_ref.namespace);

                let dp = DeleteParams::background();

                match job_api.delete(&job_ref.name, &dp).await {
                    Ok(_) => info!("Deleted Job {}", job_ref.name),
                    Err(kube::Error::Api(e)) if e.code == 404 => {
                        debug!("Job {} already deleted", job_ref.name);
                    }
                    Err(e) => {
                        warn!("Failed to delete Job {}: {}", job_ref.name, e);
                    }
                }
            }
        }

        // No more reconciliation needed for completed tasks
        Ok(Action::await_change())
    }

    /// Clean up task resources
    pub async fn cleanup(&self, task: &Task) -> Result<(), crate::controller::ReconcileError> {
        info!("Cleaning up task {}", task.name_any());

        // Delete any associated Jobs
        if let Some(status) = &task.status {
            if let Some(job_ref) = &status.job_ref {
                let job_api: Api<Job> = Api::namespaced(self.client.clone(), &job_ref.namespace);

                let dp = DeleteParams::foreground();

                match job_api.delete(&job_ref.name, &dp).await {
                    Ok(_) => info!("Deleted Job {} during cleanup", job_ref.name),
                    Err(kube::Error::Api(e)) if e.code == 404 => {
                        debug!("Job {} already deleted", job_ref.name);
                    }
                    Err(e) => {
                        error!(
                            "Failed to delete Job {} during cleanup: {}",
                            job_ref.name, e
                        );
                    }
                }
            }
        }

        Ok(())
    }

    /// Update task status
    async fn update_status(
        &self,
        task: &Task,
        phase: TaskPhase,
        message: Option<String>,
    ) -> Result<(), crate::controller::ReconcileError> {
        let api: Api<Task> = Api::namespaced(self.client.clone(), &self.namespace);

        let mut status = task.status.clone().unwrap_or_default();
        let old_phase = status.phase.clone();

        status.phase = phase.clone();
        if let Some(msg) = message {
            status.message = Some(msg);
        }

        // Update timestamps
        match &phase {
            TaskPhase::Running => {
                if status.start_time.is_none() {
                    status.start_time = Some(chrono::Utc::now());
                }
            }
            TaskPhase::Succeeded | TaskPhase::Failed | TaskPhase::Cancelled => {
                if status.completion_time.is_none() {
                    status.completion_time = Some(chrono::Utc::now());
                }
            }
            _ => {}
        }

        // Update conditions
        if old_phase != phase {
            let condition = TaskCondition {
                r#type: phase_to_condition_type(&phase),
                status: "True".to_string(),
                last_transition_time: Some(chrono::Utc::now()),
                reason: Some(phase_to_reason(&phase)),
                message: status.message.clone(),
            };

            status.conditions.retain(|c| c.r#type != condition.r#type);
            status.conditions.push(condition);
        }

        // Patch the status
        let patch = Patch::Merge(json!({
            "status": status
        }));

        match api
            .patch_status(&task.name_any(), &PatchParams::default(), &patch)
            .await
        {
            Ok(_) => {
                debug!("Updated task status to {:?}", phase);
                Ok(())
            }
            Err(kube::Error::Api(e)) if e.code == 404 => {
                warn!(
                    "Task {} not found when updating status, may have been deleted",
                    task.name_any()
                );
                Ok(()) // Don't fail reconciliation if task was deleted
            }
            Err(e) => Err(crate::controller::ReconcileError::Kubernetes(e)),
        }
    }
}

/// Convert phase to condition type
fn phase_to_condition_type(phase: &TaskPhase) -> String {
    match phase {
        TaskPhase::Pending => "Pending".to_string(),
        TaskPhase::Queued => "Queued".to_string(),
        TaskPhase::Running => "Running".to_string(),
        TaskPhase::Succeeded => "Complete".to_string(),
        TaskPhase::Failed => "Failed".to_string(),
        TaskPhase::Cancelled => "Cancelled".to_string(),
    }
}

/// Convert phase to reason
fn phase_to_reason(phase: &TaskPhase) -> String {
    match phase {
        TaskPhase::Pending => "TaskPending".to_string(),
        TaskPhase::Queued => "TaskQueued".to_string(),
        TaskPhase::Running => "TaskRunning".to_string(),
        TaskPhase::Succeeded => "TaskSucceeded".to_string(),
        TaskPhase::Failed => "TaskFailed".to_string(),
        TaskPhase::Cancelled => "TaskCancelled".to_string(),
    }
}
