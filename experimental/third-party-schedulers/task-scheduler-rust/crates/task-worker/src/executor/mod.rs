use crate::plugins::PluginManager;
use std::sync::Arc;

#[cfg(test)]
mod test;
use std::time::{Duration, Instant};
use sysinfo::System;
use base64;
use bincode;
use task_common::{
    error::{TaskError, TaskResult},
    events::{HardwareInfo, SoftwareInfo, TaskEvent, WorkerCapabilities, WorkerCapacity},
    models::{ExecutionMetrics, TaskStatus as ModelTaskStatus},
    queue::{MessageHandle, QueueManager, QueuedTask, TaskResultMessage},
    Utc,
};
use tokio::sync::Semaphore;
use tracing::{debug, error, info, instrument, warn};
use uuid::Uuid;

/// Task executor that processes tasks from the queue
pub struct TaskExecutor {
    /// Worker ID
    worker_id: String,

    /// Queue manager
    queue: Arc<QueueManager>,

    /// Plugin manager
    plugin_manager: Arc<PluginManager>,

    /// Concurrency limiter
    semaphore: Arc<Semaphore>,

    /// Maximum concurrent tasks
    max_concurrent_tasks: usize,

    /// System info for metrics
    system: System,
    
    /// Batch size for fetching tasks
    batch_size: usize,
    
    /// Fetch timeout in milliseconds
    fetch_timeout_ms: u64,
}

impl TaskExecutor {
    /// Create a new task executor
    pub fn new(
        worker_id: String,
        queue: Arc<QueueManager>,
        plugin_manager: Arc<PluginManager>,
        max_concurrent_tasks: usize,
    ) -> Self {
        Self::with_config(worker_id, queue, plugin_manager, max_concurrent_tasks, 10, 1)
    }
    
    /// Create a new task executor with configuration
    pub fn with_config(
        worker_id: String,
        queue: Arc<QueueManager>,
        plugin_manager: Arc<PluginManager>,
        max_concurrent_tasks: usize,
        batch_size: usize,
        fetch_timeout_ms: u64,
    ) -> Self {
        Self {
            worker_id,
            queue,
            plugin_manager,
            semaphore: Arc::new(Semaphore::new(max_concurrent_tasks)),
            max_concurrent_tasks,
            system: System::new_all(),
            batch_size,
            fetch_timeout_ms,
        }
    }

    /// Start the executor
    pub async fn start(&mut self) -> TaskResult<()> {
        info!("Starting task executor for worker {}", self.worker_id);

        // Send worker joined event
        info!("Sending worker joined event");
        if let Err(e) = self.send_worker_joined_event().await {
            warn!(
                "Failed to send worker joined event: {}, continuing anyway",
                e
            );
        }

        // Create task consumer
        info!("Creating task consumer for worker {}", self.worker_id);
        let consumer = self
            .queue
            .create_task_consumer_with_config(
                &self.worker_id, 
                self.max_concurrent_tasks,
                self.batch_size,
                self.fetch_timeout_ms
            )
            .await?;
        info!("Task consumer created successfully with batch_size={} and fetch_timeout_ms={}", 
            self.batch_size, self.fetch_timeout_ms);

        // Start heartbeat task
        let heartbeat_handle = self.start_heartbeat();

        // Set up shutdown signal handler
        let shutdown_signal = tokio::signal::ctrl_c();
        tokio::pin!(shutdown_signal);

        // Main processing loop
        info!(
            "Entering main processing loop for worker {}",
            self.worker_id
        );
        let mut loop_count = 0;
        loop {
            loop_count += 1;
            // Fetch tasks from queue with timeout
            debug!("Fetching tasks from queue (attempt {})...", loop_count);
            let fetch_result = tokio::select! {
                _ = &mut shutdown_signal => {
                    info!("Received shutdown signal");
                    break;
                }
                result = consumer.fetch(self.batch_size) => {
                    debug!("Fetch completed with result: {:?}", result.is_ok());
                    result
                }
            };

            let tasks = match fetch_result {
                Ok(tasks) => {
                    debug!("Fetched {} tasks from queue", tasks.len());
                    tasks
                }
                Err(e) => {
                    error!("Failed to fetch tasks: {}", e);
                    tokio::time::sleep(Duration::from_secs(5)).await;
                    continue;
                }
            };

            if tasks.is_empty() {
                // No sleep needed - fetch already has a timeout
                continue;
            }

            info!("Fetched {} tasks from queue", tasks.len());
            
            // Log details about fetched tasks for debugging
            for (task, _) in &tasks {
                debug!("Fetched task {} with method {}", task.task_id, task.method);
            }

            // Process tasks concurrently
            let mut handles = Vec::new();

            for (task, handle) in tasks {
                let semaphore = self.semaphore.clone();
                let queue = self.queue.clone();
                let plugin_manager = self.plugin_manager.clone();
                let worker_id = self.worker_id.clone();

                let handle = tokio::spawn(async move {
                    // Acquire semaphore permit
                    let _permit = semaphore.acquire().await.unwrap();

                    // Process the task
                    let result =
                        Self::process_task(task, handle, queue, plugin_manager, worker_id).await;

                    if let Err(e) = result {
                        error!("Task processing failed: {}", e);
                    }
                });

                handles.push(handle);
            }

            // Wait for all tasks to complete
            for handle in handles {
                if let Err(e) = handle.await {
                    error!("Task processing panic: {}", e);
                }
            }
        }

        // Cancel heartbeat
        heartbeat_handle.abort();

        // Send worker left event
        self.send_worker_left_event("Shutdown requested".to_string())
            .await?;

        info!("Task executor stopped");
        Ok(())
    }

    /// Process a single task
    #[instrument(skip(handle, queue, plugin_manager), fields(task_id = %task.task_id))]
    async fn process_task(
        mut task: QueuedTask,
        handle: MessageHandle,
        queue: Arc<QueueManager>,
        plugin_manager: Arc<PluginManager>,
        worker_id: String,
    ) -> TaskResult<()> {
        let task_id = task.task_id;
        let start_time = Instant::now();
        
        // Check if method is supported BEFORE sending Started event
        let is_supported = plugin_manager.get_task_info(&task.method).is_some();
        
        if !is_supported {
            info!(
                "Task {} has unsupported method '{}', rejecting immediately",
                task_id, task.method
            );
            
            // Handle unsupported method without sending Started event
            return Self::handle_unsupported_method(
                task_id,
                task.method.clone(),
                handle,
                queue,
                worker_id,
                start_time.elapsed(),
            ).await;
        }
        
        // Get the actual retry count from the message delivery count
        // Subtract 1 because the first delivery is not a retry
        let delivery_count = handle.delivery_count();
        task.retry_count = if delivery_count > 0 { (delivery_count - 1) as u32 } else { 0 };
        
        debug!("Task {} delivery_count={}, retry_count={}", task_id, delivery_count, task.retry_count);

        info!("Processing task {} with method {}", task_id, task.method);

        // Send task started event
        let event = TaskEvent::Started {
            task_id,
            worker_id: worker_id.clone(),
            timestamp: Utc::now(),
        };

        if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
            warn!("Failed to publish task started event: {}", e);
        }

        // Deserialize args - try bincode first, then JSON as fallback
        let mut decoded_args = Vec::new();
        
        for arg in &task.args {
            // Try to decode as base64-encoded bincode first
            if let Ok(decoded_bytes) = base64::Engine::decode(&base64::engine::general_purpose::STANDARD, arg) {
                if let Ok(value) = bincode::deserialize::<serde_json::Value>(&decoded_bytes) {
                    decoded_args.push(value);
                    continue;
                }
            }
            
            // Fallback to JSON parsing
            match serde_json::from_str::<serde_json::Value>(arg) {
                Ok(value) => decoded_args.push(value),
                Err(e) => {
                    error!("Failed to deserialize argument '{}': {}", arg, e);
                    return Self::handle_task_error(
                        task_id,
                        format!("Failed to deserialize argument: {}", e),
                        handle,
                        queue,
                        worker_id,
                        0, // No execution time yet
                        task.retry_count,
                        task.max_retries,
                    ).await;
                }
            }
        }
        
        let args = decoded_args;
        
        debug!("Executing task {} with {} args", task_id, args.len());

        // Execute the task
        let execution_result = plugin_manager
            .execute_task(
                &task.method,
                args,
                Duration::from_secs(task.timeout_seconds),
            )
            .await;

        let execution_time = start_time.elapsed();

        match execution_result {
            Err(TaskError::MethodNotFound(method)) => {
                // Handle unsupported method specially
                warn!(
                    "Task {} uses unsupported method '{}', marking as failed immediately",
                    task_id, method
                );
                
                // Send unsupported method event
                let event = TaskEvent::UnsupportedMethod {
                    task_id,
                    method: method.clone(),
                    worker_id: worker_id.clone(),
                    timestamp: Utc::now(),
                };
                
                if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
                    warn!("Failed to publish unsupported method event: {}", e);
                }
                
                // ACK the message to remove it from queue (no retry for unsupported methods)
                handle.ack().await?;
                
                // Publish failure result
                let result_message = TaskResultMessage {
                    task_id,
                    success: false,
                    result: None,
                    error: Some(format!("Unsupported method: {}", method)),
                    execution_time_ms: execution_time.as_millis() as u64,
                    worker_id: worker_id.clone(),
                };
                
                queue.publish_result(result_message).await?;
                
                // Send failure event
                let event = TaskEvent::Failed {
                    task_id,
                    error: format!("Unsupported method: {}", method),
                    retry_count: 0,
                    will_retry: false,
                    timestamp: Utc::now(),
                };
                
                if let Err(e) = queue.publish_event(event, worker_id).await {
                    warn!("Failed to publish task failed event: {}", e);
                }
                
                Ok(())
            }
            Ok(result) => {
                info!(
                    "Task {} completed successfully in {:?}",
                    task_id, execution_time
                );

                // Acknowledge the message
                handle.ack().await?;

                // Serialize result to JSON string
                let result_string = match serde_json::to_string(&result) {
                    Ok(json) => Some(json),
                    Err(e) => {
                        warn!("Failed to serialize result: {}", e);
                        None
                    }
                };
                
                // Publish result
                let result_message = TaskResultMessage {
                    task_id,
                    success: true,
                    result: result_string,
                    error: None,
                    execution_time_ms: execution_time.as_millis() as u64,
                    worker_id: worker_id.clone(),
                };

                queue.publish_result(result_message).await?;

                // Serialize result for event
                let result_for_event = match serde_json::to_string(&result) {
                    Ok(json) => Some(json),
                    Err(e) => {
                        warn!("Failed to serialize result for event: {}", e);
                        None
                    }
                };
                
                // Send completion event
                let event = TaskEvent::Completed {
                    result: task_common::models::TaskResult {
                        task_id,
                        status: ModelTaskStatus::Succeeded {
                            completed_at: Utc::now(),
                            duration_ms: execution_time.as_millis() as u64,
                        },
                        result: result_for_event,
                        error: None,
                        metrics: ExecutionMetrics {
                            execution_time_ms: execution_time.as_millis() as u64,
                            worker_node: Some(worker_id.clone()),
                            ..Default::default()
                        },
                    },
                    timestamp: Utc::now(),
                };

                if let Err(e) = queue.publish_event(event, worker_id).await {
                    warn!("Failed to publish task completed event: {}", e);
                }

                Ok(())
            }
            Err(e) => {
                error!("Task {} failed: {}", task_id, e);

                // Check if we should retry
                if task.retry_count < task.max_retries {
                    warn!(
                        "Retrying task {} (attempt {}/{})",
                        task_id,
                        task.retry_count + 1,
                        task.max_retries
                    );

                    // Calculate backoff delay
                    let delay = Duration::from_secs((task.retry_count + 1) as u64 * 5);

                    // NAK with delay
                    handle.nack(Some(delay)).await?;

                    // Send retry event
                    let event = TaskEvent::Retrying {
                        task_id,
                        attempt: task.retry_count + 1,
                        delay_seconds: delay.as_secs(),
                        reason: e.to_string(),
                    };

                    if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
                        warn!("Failed to publish task retrying event: {}", e);
                    }
                } else {
                    // Max retries exceeded, acknowledge to remove from queue
                    handle.ack().await?;

                    // Publish failure result
                    let result_message = TaskResultMessage {
                        task_id,
                        success: false,
                        result: None,
                        error: Some(e.to_string()),
                        execution_time_ms: execution_time.as_millis() as u64,
                        worker_id: worker_id.clone(),
                    };

                    queue.publish_result(result_message).await?;

                    // Send failure event
                    let event = TaskEvent::Failed {
                        task_id,
                        error: e.to_string(),
                        retry_count: task.retry_count,
                        will_retry: false,
                        timestamp: Utc::now(),
                    };

                    if let Err(e) = queue.publish_event(event, worker_id).await {
                        warn!("Failed to publish task failed event: {}", e);
                    }
                }

                Ok(())
            }
        }
    }

    /// Handle task error with retry logic
    async fn handle_task_error(
        task_id: Uuid,
        error: String,
        handle: MessageHandle,
        queue: Arc<QueueManager>,
        worker_id: String,
        execution_time_ms: u64,
        retry_count: u32,
        max_retries: u32,
    ) -> TaskResult<()> {
        error!("Task {} failed: {}", task_id, error);

        // Check if we should retry
        if retry_count < max_retries {
            warn!(
                "Retrying task {} (attempt {}/{})",
                task_id,
                retry_count + 1,
                max_retries
            );

            // Calculate backoff delay
            let delay = Duration::from_secs((retry_count + 1) as u64 * 5);

            // NAK with delay
            handle.nack(Some(delay)).await?;

            // Send retry event
            let event = TaskEvent::Retrying {
                task_id,
                attempt: retry_count + 1,
                delay_seconds: delay.as_secs(),
                reason: error,
            };

            if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
                warn!("Failed to publish task retrying event: {}", e);
            }
        } else {
            // Max retries exceeded, acknowledge to remove from queue
            handle.ack().await?;

            // Publish failure result
            let result_message = TaskResultMessage {
                task_id,
                success: false,
                result: None,
                error: Some(error.clone()),
                execution_time_ms,
                worker_id: worker_id.clone(),
            };

            queue.publish_result(result_message).await?;

            // Send failure event
            let event = TaskEvent::Failed {
                task_id,
                error,
                retry_count,
                will_retry: false,
                timestamp: Utc::now(),
            };

            if let Err(e) = queue.publish_event(event, worker_id).await {
                warn!("Failed to publish task failed event: {}", e);
            }
        }

        Ok(())
    }

    /// Handle unsupported method without going through normal execution
    async fn handle_unsupported_method(
        task_id: Uuid,
        method: String,
        handle: MessageHandle,
        queue: Arc<QueueManager>,
        worker_id: String,
        execution_time: Duration,
    ) -> TaskResult<()> {
        warn!(
            "Task {} uses unsupported method '{}', marking as failed immediately",
            task_id, method
        );
        
        // Send unsupported method event
        let event = TaskEvent::UnsupportedMethod {
            task_id,
            method: method.clone(),
            worker_id: worker_id.clone(),
            timestamp: Utc::now(),
        };
        
        if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
            warn!("Failed to publish unsupported method event: {}", e);
        }
        
        // ACK the message to remove it from queue (no retry for unsupported methods)
        handle.ack().await?;
        
        // Publish failure result
        let result_message = TaskResultMessage {
            task_id,
            success: false,
            result: None,
            error: Some(format!("Unsupported method: {}", method)),
            execution_time_ms: execution_time.as_millis() as u64,
            worker_id: worker_id.clone(),
        };
        
        queue.publish_result(result_message).await?;
        
        // Send failure event
        let event = TaskEvent::Failed {
            task_id,
            error: format!("Unsupported method: {}", method),
            retry_count: 0,
            will_retry: false,
            timestamp: Utc::now(),
        };
        
        if let Err(e) = queue.publish_event(event, worker_id).await {
            warn!("Failed to publish task failed event: {}", e);
        }
        
        Ok(())
    }

    /// Start heartbeat task
    fn start_heartbeat(&self) -> tokio::task::JoinHandle<()> {
        let worker_id = self.worker_id.clone();
        let queue = self.queue.clone();
        let max_tasks = self.max_concurrent_tasks;
        let semaphore = self.semaphore.clone();
        tokio::spawn(async move {
            let mut interval = tokio::time::interval(Duration::from_secs(30));
            let mut system = System::new_all();

            loop {
                interval.tick().await;

                // Update system info
                system.refresh_all();

                // Get current capacity
                let available_permits = semaphore.available_permits();
                let running_tasks = max_tasks - available_permits;

                let capacity = WorkerCapacity {
                    max_tasks: max_tasks as u32,
                    running_tasks: running_tasks as u32,
                    available_cpu: system.global_cpu_usage() as u64,
                    available_memory: system.available_memory(),
                    load_average: 0.0, // Load average not available in sysinfo 0.35
                };

                // Send heartbeat
                let event = TaskEvent::WorkerHeartbeat {
                    worker_id: worker_id.clone(),
                    node_name: hostname::get()
                        .unwrap_or_default()
                        .to_string_lossy()
                        .to_string(),
                    active_tasks: vec![], // TODO: Track active task IDs
                    capacity,
                    timestamp: Utc::now(),
                };

                if let Err(e) = queue.publish_event(event, worker_id.clone()).await {
                    error!("Failed to send heartbeat: {}", e);
                }
            }
        })
    }

    /// Send worker joined event
    async fn send_worker_joined_event(&self) -> TaskResult<()> {
        let _system = System::new_all();

        let capabilities = WorkerCapabilities {
            supported_methods: self.plugin_manager.list_methods(),
            plugins: vec![], // TODO: Get plugin info
            hardware: HardwareInfo {
                cpu_cores: num_cpus::get() as u32,
                total_memory: self.system.total_memory(),
                has_gpu: false, // TODO: Detect GPU
                gpu_info: None,
            },
            software: SoftwareInfo {
                worker_version: env!("CARGO_PKG_VERSION").to_string(),
                rust_version: "1.87.0".to_string(), // TODO: Get from rustc
                os: std::env::consts::OS.to_string(),
                kernel: System::kernel_version().unwrap_or_default(),
            },
        };

        let event = TaskEvent::WorkerJoined {
            worker_id: self.worker_id.clone(),
            node_name: hostname::get()
                .unwrap_or_default()
                .to_string_lossy()
                .to_string(),
            capabilities,
            timestamp: Utc::now(),
        };

        self.queue
            .publish_event(event, self.worker_id.clone())
            .await
    }

    /// Send worker left event
    async fn send_worker_left_event(&self, reason: String) -> TaskResult<()> {
        let event = TaskEvent::WorkerLeft {
            worker_id: self.worker_id.clone(),
            reason,
            reassigned_tasks: vec![], // TODO: Track active tasks
            timestamp: Utc::now(),
        };

        self.queue
            .publish_event(event, self.worker_id.clone())
            .await
    }
}
