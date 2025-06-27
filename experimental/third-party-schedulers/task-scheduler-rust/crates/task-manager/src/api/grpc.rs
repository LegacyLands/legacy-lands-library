use crate::api::proto::{
    task_response, task_scheduler_server::TaskScheduler, CancelTaskRequest, CancelTaskResponse,
    PauseTaskRequest, PauseTaskResponse, ResultRequest, ResultResponse, ResumeTaskRequest,
    ResumeTaskResponse, TaskRequest, TaskResponse, TaskStatusRequest, TaskStatusResponse,
};
use crate::{handlers::TaskValidator, storage::TaskStorage};
use std::sync::Arc;
use task_common::{
    error::TaskError,
    events::TaskEvent,
    models::{TaskInfo, TaskMetadata, TaskStatus as ModelTaskStatus},
    queue::{QueueManager, QueuedTask},
    Utc, Uuid,
};
use tonic::{Request, Response, Status};
use tracing::{debug, info, instrument, warn};

/// gRPC service implementation for the Task Scheduler
pub struct TaskSchedulerService {
    storage: Arc<TaskStorage>,
    queue: Arc<QueueManager>,
    validator: Arc<TaskValidator>,
    dependency_manager: Arc<crate::dependency_manager::DependencyManager>,
    cancellation_manager: Arc<crate::cancellation::CancellationManager>,
}

impl TaskSchedulerService {
    /// Create a new TaskSchedulerService
    pub fn new(
        storage: Arc<TaskStorage>,
        queue: Arc<QueueManager>,
        validator: Arc<TaskValidator>,
        dependency_manager: Arc<crate::dependency_manager::DependencyManager>,
        cancellation_manager: Arc<crate::cancellation::CancellationManager>,
    ) -> Self {
        Self {
            storage,
            queue,
            validator,
            dependency_manager,
            cancellation_manager,
        }
    }
}

#[tonic::async_trait]
impl TaskScheduler for TaskSchedulerService {
    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn submit_task(
        &self,
        request: Request<TaskRequest>,
    ) -> Result<Response<TaskResponse>, Status> {
        let task_request = request.into_inner();
        let task_id = if task_request.task_id.is_empty() {
            Uuid::new_v4()
        } else {
            Uuid::parse_str(&task_request.task_id)
                .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?
        };

        info!(
            "Submitting task {} with method: {}",
            task_id, task_request.method
        );

        // Validate the task request
        self.validator
            .validate(&task_request)
            .await
            .map_err(|e| match e {
                TaskError::InvalidConfiguration(msg) => Status::invalid_argument(msg),
                TaskError::MethodNotFound(method) => {
                    Status::not_found(format!("Method not found: {}", method))
                }
                _ => Status::internal(e.to_string()),
            })?;

        // Parse arguments
        let args: Vec<serde_json::Value> = task_request
            .args
            .into_iter()
            .map(|any| {
                // Convert protobuf Any to JSON value
                // This is simplified - in production you'd need proper type handling
                serde_json::Value::String(format!("{:?}", any))
            })
            .collect();

        // Parse dependencies
        let dependencies: Vec<Uuid> = task_request
            .deps
            .into_iter()
            .map(|dep| Uuid::parse_str(&dep))
            .collect::<Result<Vec<_>, _>>()
            .map_err(|e| Status::invalid_argument(format!("Invalid dependency ID: {}", e)))?;

        // Create task info
        let task_info = TaskInfo {
            id: task_id,
            method: task_request.method.clone(),
            args,
            dependencies,
            priority: 50, // Default priority
            metadata: TaskMetadata::default(),
            status: ModelTaskStatus::Pending,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };

        // Store task in database
        self.storage
            .create_task(&task_info)
            .await
            .map_err(|e| Status::internal(format!("Failed to store task: {}", e)))?;

        // Register task dependencies with dependency manager
        if !task_info.dependencies.is_empty() {
            self.dependency_manager
                .register_task_dependencies(task_id, &task_info.dependencies)
                .await
                .map_err(|e| Status::internal(format!("Failed to register dependencies: {}", e)))?;
        }

        // Publish task created event
        let event = TaskEvent::Created {
            task: Box::new(task_info.clone()),
            source: "task-manager".to_string(),
        };

        if let Err(e) = self
            .queue
            .publish_event(event, "task-manager".to_string())
            .await
        {
            warn!("Failed to publish task created event: {}", e);
        }

        // If async, queue the task and return immediately
        if task_request.is_async {
            // Check if dependencies are satisfied before queueing
            if !task_info.dependencies.is_empty() {
                let deps_satisfied = self
                    .validator
                    .check_dependencies(&task_info.dependencies, &self.storage)
                    .await
                    .map_err(|e| {
                        Status::internal(format!("Failed to check dependencies: {}", e))
                    })?;

                if !deps_satisfied {
                    // Update task status to waiting for dependencies
                    self.storage
                        .update_task_status(task_id, ModelTaskStatus::WaitingDependencies)
                        .await
                        .map_err(|e| {
                            Status::internal(format!("Failed to update task status: {}", e))
                        })?;

                    debug!("Task {} is waiting for dependencies", task_id);

                    return Ok(Response::new(TaskResponse {
                        task_id: task_id.to_string(),
                        status: task_response::Status::Pending as i32,
                        result: "Waiting for dependencies".to_string(),
                    }));
                }
            }

            // Queue the task
            let queued_task = QueuedTask {
                task_id: task_info.id,
                method: task_info.method.clone(),
                args: task_info.args.clone(),
                priority: task_info.priority,
                retry_count: 0,
                max_retries: 3,
                timeout_seconds: 3600,
                metadata: Default::default(),
                dependencies: task_info.dependencies.clone(),
            };

            self.queue
                .queue_task(queued_task)
                .await
                .map_err(|e| Status::internal(format!("Failed to queue task: {}", e)))?;

            // Update status to queued
            self.storage
                .update_task_status(task_id, ModelTaskStatus::Queued)
                .await
                .map_err(|e| Status::internal(format!("Failed to update task status: {}", e)))?;

            // Publish queued event
            let event = TaskEvent::Queued {
                task_id,
                queue_name: "default".to_string(),
                position: None,
            };

            if let Err(e) = self
                .queue
                .publish_event(event, "task-manager".to_string())
                .await
            {
                warn!("Failed to publish task queued event: {}", e);
            }

            Ok(Response::new(TaskResponse {
                task_id: task_id.to_string(),
                status: task_response::Status::Pending as i32,
                result: String::new(),
            }))
        } else {
            // For synchronous tasks, we need to wait for execution
            // In the distributed version, this would involve waiting for the result
            // For now, return a pending status
            Ok(Response::new(TaskResponse {
                task_id: task_id.to_string(),
                status: task_response::Status::Pending as i32,
                result: "Task submitted for synchronous execution".to_string(),
            }))
        }
    }

    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn get_result(
        &self,
        request: Request<ResultRequest>,
    ) -> Result<Response<ResultResponse>, Status> {
        let task_id = Uuid::parse_str(&request.into_inner().task_id)
            .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?;

        info!("Getting result for task {}", task_id);

        // Get task from storage
        let task = self
            .storage
            .get_task(task_id)
            .await
            .map_err(|e| Status::internal(format!("Failed to get task: {}", e)))?
            .ok_or_else(|| Status::not_found(format!("Task {} not found", task_id)))?;

        // Get result if available
        let result = self
            .storage
            .get_task_result(task_id)
            .await
            .map_err(|e| Status::internal(format!("Failed to get task result: {}", e)))?;

        let (status, result_str) = match &task.status {
            ModelTaskStatus::Pending
            | ModelTaskStatus::Queued
            | ModelTaskStatus::WaitingDependencies => {
                (task_response::Status::Pending as i32, String::new())
            }
            ModelTaskStatus::Running { .. } => {
                (task_response::Status::Pending as i32, String::new())
            }
            ModelTaskStatus::Succeeded { .. } => {
                let result_str = result
                    .and_then(|r| r.result)
                    .map(|v| v.to_string())
                    .unwrap_or_default();
                (task_response::Status::Success as i32, result_str)
            }
            ModelTaskStatus::Failed { error, .. } => {
                (task_response::Status::Failed as i32, error.clone())
            }
            ModelTaskStatus::Cancelled { reason, .. } => {
                (task_response::Status::Failed as i32, reason.clone())
            }
        };

        Ok(Response::new(ResultResponse {
            status,
            result: result_str,
        }))
    }

    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn cancel_task(
        &self,
        request: Request<CancelTaskRequest>,
    ) -> Result<Response<CancelTaskResponse>, Status> {
        let task_id = Uuid::parse_str(&request.into_inner().task_id)
            .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?;

        info!("Cancelling task {}", task_id);

        // Cancel the task via cancellation manager
        let cancelled = self
            .cancellation_manager
            .cancel_task(&task_id, "User request".to_string())
            .map_err(|e| Status::internal(format!("Failed to cancel task: {}", e)))?;

        if cancelled {
            // Update task status
            self.storage
                .update_task_status(
                    task_id,
                    ModelTaskStatus::Cancelled {
                        reason: "User request".to_string(),
                        cancelled_at: chrono::Utc::now(),
                    },
                )
                .await
                .map_err(|e| Status::internal(format!("Failed to update task status: {}", e)))?;
        }

        Ok(Response::new(CancelTaskResponse {
            success: cancelled,
            message: if cancelled {
                "Task cancelled successfully".to_string()
            } else {
                "Task not found or already completed".to_string()
            },
        }))
    }

    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn pause_task(
        &self,
        request: Request<PauseTaskRequest>,
    ) -> Result<Response<PauseTaskResponse>, Status> {
        let task_id = Uuid::parse_str(&request.into_inner().task_id)
            .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?;

        info!("Pausing task {}", task_id);

        // Get the appropriate scheduler and pause the task
        // For now, return unimplemented as pause/resume needs scheduler integration
        Err(Status::unimplemented("Task pause not yet implemented"))
    }

    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn resume_task(
        &self,
        request: Request<ResumeTaskRequest>,
    ) -> Result<Response<ResumeTaskResponse>, Status> {
        let task_id = Uuid::parse_str(&request.into_inner().task_id)
            .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?;

        info!("Resuming task {}", task_id);

        // Get the appropriate scheduler and resume the task
        // For now, return unimplemented as pause/resume needs scheduler integration
        Err(Status::unimplemented("Task resume not yet implemented"))
    }

    #[instrument(skip(self, request), fields(task_id = %request.get_ref().task_id))]
    async fn get_task_status(
        &self,
        request: Request<TaskStatusRequest>,
    ) -> Result<Response<TaskStatusResponse>, Status> {
        let task_id = Uuid::parse_str(&request.into_inner().task_id)
            .map_err(|e| Status::invalid_argument(format!("Invalid task ID: {}", e)))?;

        info!("Getting status for task {}", task_id);

        // Get task from storage
        let task = self
            .storage
            .get_task(task_id)
            .await
            .map_err(|e| Status::internal(format!("Failed to get task: {}", e)))?
            .ok_or_else(|| Status::not_found(format!("Task {} not found", task_id)))?;

        // Convert status to proto response
        let (status, message) = match &task.status {
            ModelTaskStatus::Pending => (
                task_response::Status::Pending as i32,
                "Task is pending".to_string(),
            ),
            ModelTaskStatus::Queued => (
                task_response::Status::Pending as i32,
                "Task is queued".to_string(),
            ),
            ModelTaskStatus::WaitingDependencies => (
                task_response::Status::Pending as i32,
                "Task is waiting for dependencies".to_string(),
            ),
            ModelTaskStatus::Running { .. } => (
                task_response::Status::Pending as i32,
                "Task is running".to_string(),
            ),
            ModelTaskStatus::Succeeded { .. } => (
                task_response::Status::Success as i32,
                "Task succeeded".to_string(),
            ),
            ModelTaskStatus::Failed { error, .. } => (
                task_response::Status::Failed as i32,
                format!("Task failed: {}", error),
            ),
            ModelTaskStatus::Cancelled { reason, .. } => (
                task_response::Status::Failed as i32,
                format!("Task cancelled: {}", reason),
            ),
        };

        // Get timestamps and other info
        let created_at = task.created_at.timestamp();
        let (started_at, worker_id) = match &task.status {
            ModelTaskStatus::Running {
                started_at,
                worker_id,
            } => (started_at.timestamp(), worker_id.clone()),
            _ => (0, String::new()),
        };
        let completed_at = match &task.status {
            ModelTaskStatus::Succeeded { completed_at, .. } => completed_at.timestamp(),
            ModelTaskStatus::Failed { completed_at, .. } => completed_at.timestamp(),
            ModelTaskStatus::Cancelled { cancelled_at, .. } => cancelled_at.timestamp(),
            _ => 0,
        };
        let retry_count = match &task.status {
            ModelTaskStatus::Failed { retries, .. } => *retries as i32,
            _ => 0,
        };

        Ok(Response::new(TaskStatusResponse {
            state: status,
            worker_id,
            created_at,
            started_at,
            completed_at,
            retry_count,
            message,
        }))
    }
}
