use crate::api::proto::{
    task_response, task_scheduler_server::TaskScheduler, BatchTaskRequest, BatchTaskResponse,
    CancelTaskRequest, CancelTaskResponse, PauseTaskRequest, PauseTaskResponse, ResultRequest,
    ResultResponse, ResumeTaskRequest, ResumeTaskResponse, TaskRequest, TaskResponse,
    TaskStatusRequest, TaskStatusResponse,
};
use crate::{handlers::TaskValidator, storage::StorageBackend, metrics::Metrics};
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
    storage: Arc<dyn StorageBackend>,
    queue: Arc<QueueManager>,
    validator: Arc<TaskValidator>,
    dependency_manager: Arc<crate::dependency_manager::DependencyManager>,
    cancellation_manager: Arc<crate::cancellation::CancellationManager>,
    metrics: Arc<Metrics>,
}

impl TaskSchedulerService {
    /// Create a new TaskSchedulerService
    pub fn new(
        storage: Arc<dyn StorageBackend>,
        queue: Arc<QueueManager>,
        validator: Arc<TaskValidator>,
        dependency_manager: Arc<crate::dependency_manager::DependencyManager>,
        cancellation_manager: Arc<crate::cancellation::CancellationManager>,
        metrics: Arc<Metrics>,
    ) -> Self {
        Self {
            storage,
            queue,
            validator,
            dependency_manager,
            cancellation_manager,
            metrics,
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

        // Record metrics
        self.metrics.tasks_submitted
            .with_label_values(&[&task_request.method, "true"])
            .inc();
        
        let timer = self.metrics.task_submission_duration
            .with_label_values(&[&task_request.method])
            .start_timer();

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

        // Arguments are JSON strings
        let args: Vec<String> = task_request.args;

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
        let storage_timer = self.metrics.storage_duration
            .with_label_values(&["create"])
            .start_timer();
        
        let storage_result = self.storage
            .create_task(&task_info)
            .await;
            
        storage_timer.observe_duration();
        
        match storage_result {
            Ok(_) => {
                self.metrics.storage_operations
                    .with_label_values(&["create", "success"])
                    .inc();
            }
            Err(e) => {
                self.metrics.storage_operations
                    .with_label_values(&["create", "error"])
                    .inc();
                return Err(Status::internal(format!("Failed to store task: {}", e)));
            }
        }

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
                let dependency_timer = self.metrics.dependency_resolution_duration
                    .with_label_values(&[&task_info.dependencies.len().to_string()])
                    .start_timer();
                    
                let deps_satisfied = self
                    .validator
                    .check_dependencies(&task_info.dependencies, &self.storage)
                    .await
                    .map_err(|e| {
                        Status::internal(format!("Failed to check dependencies: {}", e))
                    })?;
                    
                dependency_timer.observe_duration();
                
                // Record dependency check result
                if deps_satisfied {
                    self.metrics.dependency_checks
                        .with_label_values(&["satisfied"])
                        .inc();
                } else {
                    self.metrics.dependency_checks
                        .with_label_values(&["failed"])
                        .inc();
                }

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

            // Record queue operation metrics
            let queue_result = self.queue
                .queue_task(queued_task)
                .await;
                
            match queue_result {
                Ok(_) => {
                    self.metrics.queue_operations
                        .with_label_values(&["enqueue", "success"])
                        .inc();
                    
                    // Increment queue depth when task is successfully queued
                    self.metrics.queue_depth
                        .with_label_values(&["default"])
                        .inc();
                }
                Err(e) => {
                    self.metrics.queue_operations
                        .with_label_values(&["enqueue", "error"])
                        .inc();
                    return Err(Status::internal(format!("Failed to queue task: {}", e)));
                }
            }

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

            // Record task status metric
            self.metrics.tasks_by_status
                .with_label_values(&["queued"])
                .inc();
            
            // Stop the timer
            timer.observe_duration();

            Ok(Response::new(TaskResponse {
                task_id: task_id.to_string(),
                status: task_response::Status::Pending as i32,
                result: String::new(),
            }))
        } else {
            // For synchronous tasks, queue the task and wait for result
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

            // Record queue operation metrics
            let queue_result = self.queue
                .queue_task(queued_task)
                .await;
                
            match queue_result {
                Ok(_) => {
                    self.metrics.queue_operations
                        .with_label_values(&["enqueue", "success"])
                        .inc();
                    
                    // Increment queue depth when task is successfully queued
                    self.metrics.queue_depth
                        .with_label_values(&["default"])
                        .inc();
                }
                Err(e) => {
                    self.metrics.queue_operations
                        .with_label_values(&["enqueue", "error"])
                        .inc();
                    return Err(Status::internal(format!("Failed to queue task: {}", e)));
                }
            }

            // Update status to queued
            self.storage
                .update_task_status(task_id, ModelTaskStatus::Queued)
                .await
                .map_err(|e| Status::internal(format!("Failed to update task status: {}", e)))?;

            // For synchronous tasks, wait for completion (with timeout)
            let start_time = std::time::Instant::now();
            let timeout = std::time::Duration::from_secs(30); // 30 second timeout

            loop {
                if start_time.elapsed() > timeout {
                    return Ok(Response::new(TaskResponse {
                        task_id: task_id.to_string(),
                        status: task_response::Status::Failed as i32,
                        result: "Task execution timeout".to_string(),
                    }));
                }

                // Check task status
                let task = self
                    .storage
                    .get_task(task_id)
                    .await
                    .map_err(|e| Status::internal(format!("Failed to get task: {}", e)))?
                    .ok_or_else(|| Status::not_found(format!("Task {} not found", task_id)))?;

                match &task.status {
                    ModelTaskStatus::Succeeded { .. } => {
                        // Get result
                        let result = self
                            .storage
                            .get_task_result(task_id)
                            .await
                            .map_err(|e| Status::internal(format!("Failed to get task result: {}", e)))?;

                        let result_str = result
                            .and_then(|r| r.result)
                            .map(|v| v.to_string())
                            .unwrap_or_default();

                        return Ok(Response::new(TaskResponse {
                            task_id: task_id.to_string(),
                            status: task_response::Status::Success as i32,
                            result: result_str,
                        }));
                    }
                    ModelTaskStatus::Failed { error, .. } => {
                        return Ok(Response::new(TaskResponse {
                            task_id: task_id.to_string(),
                            status: task_response::Status::Failed as i32,
                            result: error.clone(),
                        }));
                    }
                    _ => {
                        // Still pending/running, wait a bit
                        tokio::time::sleep(tokio::time::Duration::from_millis(1)).await;
                    }
                }
            }
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
    
    #[instrument(skip(self, request), fields(batch_size = %request.get_ref().tasks.len()))]
    async fn batch_submit_tasks(
        &self,
        request: Request<BatchTaskRequest>,
    ) -> Result<Response<BatchTaskResponse>, Status> {
        let batch_request = request.into_inner();
        let batch_id = Uuid::new_v4().to_string();
        let _is_async = batch_request.is_async;
        let total_tasks = batch_request.tasks.len();
        
        info!(
            "Batch submitting {} tasks, batch_id: {}",
            total_tasks,
            batch_id
        );
        
        let timer = self.metrics.task_submission_duration
            .with_label_values(&["batch"])
            .start_timer();
        
        // Convert all task requests to TaskInfo structs
        let mut task_infos = Vec::with_capacity(batch_request.tasks.len());
        let mut responses = Vec::with_capacity(batch_request.tasks.len());
        let mut total_failed = 0;
        
        for task_request in batch_request.tasks {
            let task_id = if task_request.task_id.is_empty() {
                Uuid::new_v4()
            } else {
                match Uuid::parse_str(&task_request.task_id) {
                    Ok(id) => id,
                    Err(e) => {
                        total_failed += 1;
                        responses.push(TaskResponse {
                            task_id: task_request.task_id.clone(),
                            status: task_response::Status::Failed as i32,
                            result: format!("Invalid task ID: {}", e),
                        });
                        continue;
                    }
                }
            };
            
            // Validate the task request
            match self.validator.validate(&task_request).await {
                Ok(_) => {},
                Err(e) => {
                    total_failed += 1;
                    responses.push(TaskResponse {
                        task_id: task_id.to_string(),
                        status: task_response::Status::Failed as i32,
                        result: format!("Validation failed: {}", e),
                    });
                    continue;
                }
            }
            
            // Arguments are already base64-encoded bincode strings
            let args: Vec<String> = task_request.args;
            
            // Parse dependencies
            let dependencies = match task_request
                .deps
                .into_iter()
                .map(|dep| Uuid::parse_str(&dep))
                .collect::<Result<Vec<_>, _>>() {
                Ok(deps) => deps,
                Err(e) => {
                    total_failed += 1;
                    responses.push(TaskResponse {
                        task_id: task_id.to_string(),
                        status: task_response::Status::Failed as i32,
                        result: format!("Invalid dependency ID: {}", e),
                    });
                    continue;
                }
            };
            
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
            
            task_infos.push(task_info);
            responses.push(TaskResponse {
                task_id: task_id.to_string(),
                status: task_response::Status::Pending as i32,
                result: String::new(),
            });
        }
        
        // Batch insert all valid tasks
        if !task_infos.is_empty() {
            let storage_timer = self.metrics.storage_duration
                .with_label_values(&["batch_create"])
                .start_timer();
            
            match self.storage.create_tasks_batch(&task_infos).await {
                Ok(_) => {
                    self.metrics.storage_operations
                        .with_label_values(&["batch_create", "success"])
                        .inc();
                    
                    // Register dependencies in batch
                    let mut dependency_futures = Vec::new();
                    for task_info in &task_infos {
                        if !task_info.dependencies.is_empty() {
                            let dep_future = self.dependency_manager
                                .register_task_dependencies(task_info.id, &task_info.dependencies);
                            dependency_futures.push((task_info.id, dep_future));
                        }
                    }
                    
                    // Wait for all dependency registrations
                    for (task_id, dep_future) in dependency_futures {
                        if let Err(e) = dep_future.await {
                            warn!("Failed to register dependencies for task {}: {}", task_id, e);
                        }
                    }
                    
                    // Create queued tasks
                    let queued_tasks: Vec<QueuedTask> = task_infos.iter()
                        .map(|task_info| QueuedTask {
                            task_id: task_info.id,
                            method: task_info.method.clone(),
                            args: task_info.args.clone(),
                            priority: task_info.priority,
                            retry_count: 0,
                            max_retries: 3,
                            timeout_seconds: 3600,
                            metadata: Default::default(),
                            dependencies: task_info.dependencies.clone(),
                        })
                        .collect();
                    
                    // Queue all tasks in batch
                    if let Err(e) = self.queue.queue_tasks_batch(queued_tasks).await {
                        warn!("Failed to queue tasks in batch: {}", e);
                    } else {
                        self.metrics.queue_depth
                            .with_label_values(&["default"])
                            .add(task_infos.len() as f64);
                    }
                    
                    // Update task statuses in batch
                    let task_ids: Vec<Uuid> = task_infos.iter().map(|t| t.id).collect();
                    if let Err(e) = self.storage
                        .update_tasks_status_batch(&task_ids, ModelTaskStatus::Queued)
                        .await {
                        warn!("Failed to update task statuses: {}", e);
                    }
                    
                    // Record metrics
                    self.metrics.tasks_submitted
                        .with_label_values(&["batch", "true"])
                        .inc_by(task_infos.len() as f64);
                    
                    self.metrics.tasks_by_status
                        .with_label_values(&["queued"])
                        .inc_by(task_infos.len() as f64);
                }
                Err(e) => {
                    self.metrics.storage_operations
                        .with_label_values(&["batch_create", "error"])
                        .inc();
                    
                    // Mark all tasks as failed
                    for response in &mut responses {
                        response.status = task_response::Status::Failed as i32;
                        response.result = format!("Failed to store tasks: {}", e);
                    }
                    total_failed = responses.len() as i32;
                }
            }
            
            storage_timer.observe_duration();
        }
        
        timer.observe_duration();
        
        Ok(Response::new(BatchTaskResponse {
            responses,
            total_submitted: (total_tasks - total_failed as usize) as i32,
            total_failed,
            batch_id,
        }))
    }
}
