use clap::Parser;
use prometheus::Registry;
use std::{net::SocketAddr, path::PathBuf, sync::Arc};
use task_common::{
    events::TaskEvent,
    queue::QueueManager,
    tracing::{init_tracing, shutdown_tracing, TracingConfig},
};
use task_manager::{
    api::{proto::task_scheduler_server::TaskSchedulerServer, TaskSchedulerService},
    cancellation::CancellationManager,
    config::Config,
    dependency_manager::DependencyManager,
    handlers::TaskValidator,
    metrics::{start_metrics_server, Metrics},
    storage::TaskStorage,
};
use tonic::transport::Server;
use tracing::{error, info};

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
    let tracing_config = TracingConfig {
        service_name: config.observability.service_name.clone(),
        service_version: env!("CARGO_PKG_VERSION").to_string(),
        otlp_endpoint: config.observability.otlp_endpoint.clone(),
        environment: "production".to_string(),
        sampling_ratio: config.observability.sampling_ratio,
        export_timeout: std::time::Duration::from_secs(10),
        log_level: config.observability.log_level.clone(),
    };

    if config.observability.tracing_enabled {
        init_tracing(tracing_config)?;
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
    let _metrics = Arc::new(Metrics::new(&registry)?);

    // Initialize storage
    let storage = Arc::new(TaskStorage::new(config.storage.cache_size));

    // Initialize queue manager
    let queue = Arc::new(QueueManager::new(&config.queue.nats_url).await?);
    queue.initialize().await?;

    // Initialize task validator
    let validator = Arc::new(TaskValidator::new());

    // Initialize dependency manager
    let dependency_manager = Arc::new(DependencyManager::new(storage.clone(), queue.clone()));

    // Initialize cancellation manager
    let cancellation_manager = Arc::new(CancellationManager::new());

    // Start task completion listener
    let dependency_manager_clone = dependency_manager.clone();
    let queue_clone = queue.clone();
    let completion_listener_handle = tokio::spawn(async move {
        info!("Starting task completion listener");

        // Subscribe to task events
        let mut subscriber = match queue_clone.subscribe_events("task.*").await {
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
                    match &envelope.event {
                        TaskEvent::Completed { result, .. } => {
                            info!(
                                "Task {} completed, checking dependent tasks",
                                result.task_id
                            );
                            if let Err(e) = dependency_manager_clone
                                .check_dependent_tasks(result.task_id)
                                .await
                            {
                                error!("Failed to check dependent tasks: {}", e);
                            }
                        }
                        TaskEvent::Failed { task_id, .. }
                        | TaskEvent::Cancelled { task_id, .. } => {
                            info!(
                                "Task {} failed or cancelled, checking dependent tasks",
                                task_id
                            );
                            if let Err(e) = dependency_manager_clone
                                .check_dependent_tasks(*task_id)
                                .await
                            {
                                error!("Failed to check dependent tasks: {}", e);
                            }
                        }
                        _ => {
                            // Other events are ignored
                        }
                    }
                }
                Ok(None) => {
                    // No more events
                }
                Err(e) => {
                    error!("Error receiving event: {}", e);
                    // Sleep a bit before retrying
                    tokio::time::sleep(tokio::time::Duration::from_secs(5)).await;
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
    );

    // Parse addresses
    let grpc_addr: SocketAddr = config.server.grpc_address.parse()?;
    let metrics_addr: SocketAddr = config.server.metrics_address.parse()?;

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

    // Shutdown tracing
    if config.observability.tracing_enabled {
        shutdown_tracing();
    }

    info!("Task Manager shut down successfully");
    Ok(())
}
