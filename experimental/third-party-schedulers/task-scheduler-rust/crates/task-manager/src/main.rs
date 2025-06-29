use clap::Parser;
use futures::StreamExt;
use prometheus::Registry;
use std::{net::SocketAddr, path::PathBuf, sync::Arc, time::Duration};
use sysinfo::System;
use task_common::{
    events::TaskEvent,
    queue::QueueManager,
    tracing::{init_jaeger_tracing, shutdown_tracing, JaegerConfig},
    models::{TaskResult as TaskResultData, TaskStatus as ModelTaskStatus},
};
use task_manager::{
    api::{proto::task_scheduler_server::TaskSchedulerServer, TaskSchedulerService},
    cancellation::CancellationManager,
    config::Config,
    dependency_manager::DependencyManager,
    handlers::TaskValidator,
    metrics::{start_metrics_server, Metrics},
    storage,
};
use tonic::transport::Server;
use tracing::{debug, error, info, warn};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Configuration file path
    #[arg(short, long)]
    config: Option<PathBuf>,

    /// gRPC server address
    #[arg(long, env = "GRPC_ADDRESS")]
    grpc_address: Option<String>,

    /// Metrics server address
    #[arg(long, env = "METRICS_ADDRESS")]
    metrics_address: Option<String>,

    /// NATS server URL
    #[arg(long, env = "NATS_URL")]
    nats_url: Option<String>,

    /// Log level
    #[arg(long, env = "LOG_LEVEL")]
    log_level: Option<String>,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    // Load configuration
    let mut config = if let Some(config_path) = args.config {
        Config::from_file(&config_path)?
    } else {
        Config::from_env()
    };

    // Override with command line arguments
    if let Some(addr) = args.grpc_address {
        config.server.grpc_address = addr;
    }
    if let Some(addr) = args.metrics_address {
        config.server.metrics_address = addr;
    }
    if let Some(url) = args.nats_url {
        config.queue.nats_url = url;
    }
    if let Some(level) = args.log_level {
        config.observability.log_level = level;
    }

    // Initialize tracing
    if config.observability.tracing_enabled {
        let jaeger_config = JaegerConfig {
            service_name: config.observability.service_name.clone(),
            service_version: env!("CARGO_PKG_VERSION").to_string(),
            environment: "production".to_string(),
            endpoint: config.observability.otlp_endpoint.clone(),
            sampling_ratio: config.observability.sampling_ratio,
            export_timeout: std::time::Duration::from_secs(10),
        };
        init_jaeger_tracing(jaeger_config).map_err(|e| e.to_string())?;
    } else {
        // Just initialize basic logging
        tracing_subscriber::fmt()
            .with_env_filter(&config.observability.log_level)
            .json()
            .init();
    }

    info!("Starting Task Manager v{}", env!("CARGO_PKG_VERSION"));
    info!("Configuration loaded: {:?}", config);

    // Initialize metrics
    let registry = Registry::new();
    let metrics = Arc::new(Metrics::new(&registry)?);

    // Initialize storage based on configuration
    let storage = storage::create_storage_backend(&config).await?;

    // Initialize queue manager
    let queue = Arc::new(QueueManager::new(&config.queue.nats_url).await?);
    queue.initialize().await?;

    // Initialize task validator
    let validator = Arc::new(TaskValidator::new());

    // Initialize dependency manager
    let dependency_manager = Arc::new(DependencyManager::new(storage.clone(), queue.clone(), metrics.clone()));

    // Initialize cancellation manager
    let cancellation_manager = Arc::new(CancellationManager::new());

    // Start task completion listener
    let dependency_manager_clone = dependency_manager.clone();
    let queue_clone = queue.clone();
    let metrics_clone = metrics.clone();
    let storage_clone = storage.clone();
    let completion_listener_handle = tokio::spawn(async move {
        info!("Starting task completion listener");

        // Subscribe to task and worker events
        let mut subscriber = match queue_clone.subscribe_events("*.events.*").await {
            Ok(sub) => sub,
            Err(e) => {
                error!("Failed to subscribe to task events: {}", e);
                return;
            }
        };

        // Listen for task completion events
        loop {
            match subscriber.next().await {
                Ok(Some(envelope)) => {
                    debug!("Received event: {:?}", envelope.event);
                    match &envelope.event {
                        TaskEvent::Started { task_id, worker_id, .. } => {
                            info!("Task {} started by worker {}", task_id, worker_id);
                            // NOTE: We don't decrement queue_depth here because Started events
                            // are fired for retries too, which would cause double-decrement
                            // TODO: Add attempt field to Started event to distinguish initial vs retry
                            
                            // Increment active tasks for this worker
                            metrics_clone.worker_active_tasks
                                .with_label_values(&[worker_id])
                                .inc();
                        }
                        TaskEvent::Completed { result, .. } => {
                            info!(
                                "Task {} completed, checking dependent tasks",
                                result.task_id
                            );
                            
                            // Update task status in storage
                            let model_status = match &result.status {
                                task_common::models::TaskStatus::Succeeded { completed_at, duration_ms } => {
                                    ModelTaskStatus::Succeeded {
                                        completed_at: *completed_at,
                                        duration_ms: *duration_ms,
                                    }
                                },
                                task_common::models::TaskStatus::Failed { error, completed_at, retries } => {
                                    ModelTaskStatus::Failed {
                                        error: error.clone(),
                                        completed_at: *completed_at,
                                        retries: *retries,
                                    }
                                },
                                _ => {
                                    warn!("Unexpected task status in Completed event: {:?}", result.status);
                                    continue;
                                }
                            };
                            
                            if let Err(e) = storage_clone.update_task_status(result.task_id, model_status).await {
                                error!("Failed to update task status in storage: {}", e);
                            }
                            
                            // Store task result
                            let task_result = TaskResultData {
                                task_id: result.task_id,
                                status: result.status.clone(),
                                result: result.result.clone(),
                                error: result.error.clone(),
                                metrics: result.metrics.clone(),
                            };
                            
                            if let Err(e) = storage_clone.store_task_result(&task_result).await {
                                error!("Failed to store task result: {}", e);
                            }
                            
                            // Record task execution duration
                            let execution_time_seconds = result.metrics.execution_time_ms as f64 / 1000.0;
                            
                            // Get task method from storage for proper labeling
                            let method = if let Some(task) = storage_clone.get_task(result.task_id).await.ok().flatten() {
                                task.method
                            } else {
                                "unknown".to_string()
                            };
                            
                            let status = match &result.status {
                                task_common::models::TaskStatus::Succeeded { .. } => "succeeded",
                                task_common::models::TaskStatus::Failed { .. } => "failed",
                                _ => "unknown",
                            };
                            
                            metrics_clone.task_execution_duration
                                .with_label_values(&[&method, status])
                                .observe(execution_time_seconds);
                            
                            // Update task status counter
                            metrics_clone.tasks_by_status
                                .with_label_values(&[status])
                                .inc();
                            
                            // Decrement queue depth when task is finally completed
                            metrics_clone.queue_depth
                                .with_label_values(&["default"])
                                .dec();
                            
                            // Decrement active tasks if we know the worker
                            if let Some(worker_id) = &result.metrics.worker_node {
                                metrics_clone.worker_active_tasks
                                    .with_label_values(&[worker_id])
                                    .dec();
                            }
                            
                            if let Err(e) = dependency_manager_clone
                                .check_dependent_tasks(result.task_id)
                                .await
                            {
                                error!("Failed to check dependent tasks: {}", e);
                            }
                        }
                        TaskEvent::Failed { task_id, .. } => {
                            info!(
                                "Task {} failed, checking dependent tasks",
                                task_id
                            );
                            
                            // Update task status counter
                            metrics_clone.tasks_by_status
                                .with_label_values(&["failed"])
                                .inc();
                            
                            // Decrement queue depth when task finally fails
                            metrics_clone.queue_depth
                                .with_label_values(&["default"])
                                .dec();
                            
                            // Note: We don't have worker_id in Failed/Cancelled events
                            // The WorkerHeartbeat will sync the correct count
                            
                            if let Err(e) = dependency_manager_clone
                                .check_dependent_tasks(*task_id)
                                .await
                            {
                                error!("Failed to check dependent tasks: {}", e);
                            }
                        }
                        TaskEvent::Cancelled { task_id, .. } => {
                            info!(
                                "Task {} cancelled, checking dependent tasks",
                                task_id
                            );
                            
                            // Update task status counter
                            metrics_clone.tasks_by_status
                                .with_label_values(&["cancelled"])
                                .inc();
                            
                            // Decrement queue depth when task is cancelled
                            metrics_clone.queue_depth
                                .with_label_values(&["default"])
                                .dec();
                            
                            if let Err(e) = dependency_manager_clone
                                .check_dependent_tasks(*task_id)
                                .await
                            {
                                error!("Failed to check dependent tasks: {}", e);
                            }
                        }
                        TaskEvent::WorkerHeartbeat { worker_id, capacity, .. } => {
                            // Use heartbeat to sync worker active tasks count
                            metrics_clone.worker_active_tasks
                                .with_label_values(&[worker_id])
                                .set(capacity.running_tasks as i64);
                        }
                        TaskEvent::WorkerJoined { worker_id, .. } => {
                            info!("Worker {} joined", worker_id);
                            // Initialize worker metrics
                            metrics_clone.worker_pool_size
                                .with_label_values(&["default"])
                                .inc();
                            metrics_clone.worker_active_tasks
                                .with_label_values(&[worker_id])
                                .set(0);
                        }
                        TaskEvent::WorkerLeft { worker_id, .. } => {
                            info!("Worker {} left", worker_id);
                            // Clean up worker metrics
                            metrics_clone.worker_pool_size
                                .with_label_values(&["default"])
                                .dec();
                            metrics_clone.worker_active_tasks
                                .with_label_values(&[worker_id])
                                .set(0);
                        }
                        TaskEvent::Retrying { task_id, attempt, delay_seconds, .. } => {
                            info!(
                                "Task {} retrying (attempt {}) with {} seconds delay",
                                task_id, attempt, delay_seconds
                            );
                            
                            // Get task method from storage for proper labeling
                            let method = if let Some(task) = storage_clone.get_task(*task_id).await.ok().flatten() {
                                task.method
                            } else {
                                "unknown".to_string()
                            };
                            
                            // Record retry event
                            metrics_clone.task_retries
                                .with_label_values(&[&method, &attempt.to_string()])
                                .inc();
                        }
                        _ => {
                            // Other events are ignored
                        }
                    }
                }
                Ok(None) => {
                    // No event or skipped event, continue
                    continue;
                }
                Err(e) => {
                    error!("Error receiving event: {}", e);
                    // Sleep a bit before retrying
                    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
                }
            }
        }
    });

    // Start task result listener
    let metrics_clone2 = metrics.clone();
    let storage_clone2 = storage.clone();
    let queue_clone2 = queue.clone();
    let result_listener_handle = tokio::spawn(async move {
        info!("Starting task result listener");

        // Subscribe to task results
        let mut result_sub = match queue_clone2.get_client()
            .subscribe("tasks.results")
            .await
        {
            Ok(sub) => sub,
            Err(e) => {
                error!("Failed to subscribe to task results: {}", e);
                return;
            }
        };

        // Listen for task results
        loop {
            match result_sub.next().await {
                Some(msg) => {
                    match serde_json::from_slice::<task_common::queue::TaskResultMessage>(&msg.payload) {
                        Ok(result_msg) => {
                            // Record execution duration for all tasks (including failed ones)
                            let execution_time_seconds = result_msg.execution_time_ms as f64 / 1000.0;
                            
                            // Get task method from storage
                            let method = if let Some(task) = storage_clone2.get_task(result_msg.task_id).await.ok().flatten() {
                                task.method
                            } else {
                                "unknown".to_string()
                            };
                            
                            let status = if result_msg.success {
                                "succeeded"
                            } else {
                                "failed"
                            };
                            
                            metrics_clone2.task_execution_duration
                                .with_label_values(&[&method, status])
                                .observe(execution_time_seconds);
                            
                            debug!(
                                "Recorded execution duration for task {}: {}s (method: {}, status: {})",
                                result_msg.task_id,
                                execution_time_seconds,
                                method,
                                status
                            );
                            
                            // Also handle error categorization for failed tasks
                            if !result_msg.success {
                                if let Some(error) = &result_msg.error {
                                    let category = categorize_error(&error);
                                    metrics_clone2.tasks_errors_by_category
                                        .with_label_values(&[&category])
                                        .inc();
                                }
                            }
                        }
                        Err(e) => {
                            warn!("Failed to deserialize task result message: {}", e);
                        }
                    }
                }
                None => {
                    // No more messages
                }
            }
        }
    });

    // Create gRPC service
    let service = TaskSchedulerService::new(
        storage.clone(),
        queue.clone(),
        validator.clone(),
        dependency_manager.clone(),
        cancellation_manager.clone(),
        metrics.clone(),
    );

    // Parse addresses
    let grpc_addr: SocketAddr = config.server.grpc_address.parse()?;
    let metrics_addr: SocketAddr = config.server.metrics_address.parse()?;

    // Start system resource monitoring task
    let system_metrics = metrics.clone();
    let system_monitor_handle = tokio::spawn(async move {
        info!("Starting system resource monitoring");
        let mut system = System::new_all();
        let mut interval = tokio::time::interval(Duration::from_secs(5));
        
        loop {
            interval.tick().await;
            
            // Refresh system information
            system.refresh_all();
            
            // Update CPU metrics for each core
            for (i, cpu) in system.cpus().iter().enumerate() {
                system_metrics.cpu_usage_percent
                    .with_label_values(&[&format!("cpu{}", i)])
                    .set(cpu.cpu_usage() as f64);
            }
            
            // Update overall CPU usage
            system_metrics.cpu_usage_percent
                .with_label_values(&["total"])
                .set(system.global_cpu_usage() as f64);
            
            // Update memory metrics
            let total_memory = system.total_memory();
            let used_memory = system.used_memory();
            let available_memory = system.available_memory();
            
            system_metrics.memory_usage_bytes
                .with_label_values(&["total"])
                .set(total_memory as i64);
            
            system_metrics.memory_usage_bytes
                .with_label_values(&["used"])
                .set(used_memory as i64);
                
            system_metrics.memory_usage_bytes
                .with_label_values(&["available"])
                .set(available_memory as i64);
                
            debug!("Updated system metrics: CPU total={}%, Memory used={}/{} MB",
                system.global_cpu_usage(),
                used_memory / 1024 / 1024,
                total_memory / 1024 / 1024
            );
        }
    });

    // Start metrics server
    let metrics_handle = tokio::spawn(async move {
        if let Err(e) = start_metrics_server(metrics_addr, registry).await {
            error!("Metrics server error: {}", e);
        }
    });

    // Configure gRPC server
    let mut server_builder = Server::builder();

    // Configure TLS if enabled
    if config.server.tls_enabled {
        info!("TLS is enabled");
        // TLS configuration would go here
        // This requires loading certificates and configuring tonic with TLS
    }

    // Start gRPC server
    info!("Starting gRPC server on {}", grpc_addr);

    let server_handle = tokio::spawn(async move {
        if let Err(e) = server_builder
            .add_service(TaskSchedulerServer::new(service))
            .serve(grpc_addr)
            .await
        {
            error!("gRPC server error: {}", e);
        }
    });

    // Wait for shutdown signal
    tokio::signal::ctrl_c().await?;
    info!("Received shutdown signal");

    // Graceful shutdown
    info!("Shutting down services...");

    // Cancel server tasks
    server_handle.abort();
    metrics_handle.abort();
    completion_listener_handle.abort();
    result_listener_handle.abort();
    system_monitor_handle.abort();

    // Shutdown tracing
    if config.observability.tracing_enabled {
        shutdown_tracing();
    }

    info!("Task Manager shut down successfully");
    Ok(())
}

/// Categorize error message
fn categorize_error(error: &str) -> String {
    let error_lower = error.to_lowercase();
    if error_lower.contains("timeout") || error_lower.contains("timed out") {
        "timeout"
    } else if error_lower.contains("network") || error_lower.contains("connection") || error_lower.contains("unreachable") {
        "network"
    } else if error_lower.contains("validation") || error_lower.contains("invalid") || error_lower.contains("malformed") {
        "validation"
    } else if error_lower.contains("permission") || error_lower.contains("denied") || error_lower.contains("unauthorized") {
        "permission"
    } else if error_lower.contains("memory") || error_lower.contains("resource") || error_lower.contains("limit") {
        "resource"
    } else if error_lower.contains("database") || error_lower.contains("storage") || error_lower.contains("persistence") {
        "database"
    } else if error_lower.contains("concurrency") || error_lower.contains("lock") || error_lower.contains("deadlock") {
        "concurrency"
    } else if error_lower.contains("retry") || error_lower.contains("attempts") {
        "retry_exhausted"
    } else if error_lower.contains("unsupported") || error_lower.contains("not found") || error_lower.contains("method") {
        "unsupported_method"
    } else {
        "unknown"
    }.to_string()
}
