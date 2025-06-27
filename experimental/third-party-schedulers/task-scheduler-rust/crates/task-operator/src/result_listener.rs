use futures::StreamExt;
use kube::{Api, Client, ResourceExt};
use std::sync::Arc;
use task_common::crd::{Task, TaskCrdStatus, TaskMetrics, TaskPhase};
use task_common::{
    error::TaskResult,
    events::subjects,
    queue::{QueueManager, TaskResultMessage},
};
use tracing::{error, info, warn};

/// Listens for task results and updates task status
pub struct ResultListener {
    client: Client,
    namespace: String,
    queue: Arc<QueueManager>,
}

impl ResultListener {
    pub fn new(client: Client, namespace: String, queue: Arc<QueueManager>) -> Self {
        Self {
            client,
            namespace,
            queue,
        }
    }

    /// Start listening for task results
    pub async fn start(&self) -> TaskResult<()> {
        info!("Starting result listener for namespace {}", self.namespace);

        // Subscribe to task results
        let mut subscriber = self
            .queue
            .get_client()
            .subscribe(subjects::TASK_RESULTS)
            .await
            .map_err(|e| {
                task_common::error::TaskError::QueueError(format!(
                    "Failed to subscribe to task results: {}",
                    e
                ))
            })?;

        info!("Subscribed to task results stream");

        // Process results
        while let Some(msg) = subscriber.next().await {
            match serde_json::from_slice::<TaskResultMessage>(&msg.payload) {
                Ok(result) => {
                    info!(
                        "Received result for task {}: success={}",
                        result.task_id, result.success
                    );

                    if let Err(e) = self.handle_result(result).await {
                        error!("Failed to handle task result: {}", e);
                    }
                }
                Err(e) => {
                    error!("Failed to deserialize task result: {}", e);
                }
            }

            // Note: Regular NATS subscriptions don't need acknowledgment
        }

        Ok(())
    }

    /// Handle a task result
    async fn handle_result(&self, result: TaskResultMessage) -> TaskResult<()> {
        info!("Handling result for task {}", result.task_id);

        // Find the task by ID
        let api: Api<Task> = Api::namespaced(self.client.clone(), &self.namespace);

        // List all tasks and find the one with matching UID
        let tasks = api
            .list(&Default::default())
            .await
            .map_err(|e| task_common::error::TaskError::KubernetesError(e.to_string()))?;

        let task_uid = result.task_id.to_string();
        let task = tasks
            .items
            .into_iter()
            .find(|t| t.uid() == Some(task_uid.clone()));

        let task = match task {
            Some(t) => t,
            None => {
                warn!("Task with UID {} not found", task_uid);
                return Ok(());
            }
        };

        // Update task status based on result
        let (phase, message) = if result.success {
            (
                TaskPhase::Succeeded,
                "Task completed successfully".to_string(),
            )
        } else {
            let error_msg = result.error.as_deref().unwrap_or("Unknown error");
            (TaskPhase::Failed, format!("Task failed: {}", error_msg))
        };

        // Update status
        let status = TaskCrdStatus {
            phase: phase.clone(),
            message: Some(message.clone()),
            start_time: task.status.as_ref().and_then(|s| s.start_time),
            completion_time: Some(chrono::Utc::now()),
            job_ref: None,
            result: result.result,
            error: result.error,
            retry_count: task.status.as_ref().map(|s| s.retry_count).unwrap_or(0),
            worker_node: Some(result.worker_id),
            metrics: TaskMetrics {
                queue_time_ms: task
                    .status
                    .as_ref()
                    .map(|s| s.metrics.queue_time_ms)
                    .unwrap_or(0),
                execution_time_ms: result.execution_time_ms,
                cpu_usage: None,
                memory_usage: None,
            },
            conditions: task
                .status
                .as_ref()
                .map(|s| s.conditions.clone())
                .unwrap_or_default(),
        };

        // Create patch
        let patch = serde_json::json!({
            "status": status
        });

        // Apply patch
        match api
            .patch_status(
                &task.name_any(),
                &kube::api::PatchParams::default(),
                &kube::api::Patch::Merge(patch),
            )
            .await
        {
            Ok(_) => {
                info!("Updated task {} status to {:?}", task.name_any(), phase);
            }
            Err(e) => {
                error!("Failed to update task status: {}", e);
            }
        }

        Ok(())
    }
}
