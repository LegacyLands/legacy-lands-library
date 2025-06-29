use axum::response::IntoResponse;
use clap::Parser;
use std::{path::PathBuf, sync::Arc};
use task_common::{
    queue::QueueManager,
};
use task_worker::{
    config::{Config, OperationMode},
    executor::TaskExecutor,
    plugins::PluginManager,
};
use tracing::{error, info, warn};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Configuration file path
    #[arg(short, long)]
    config: Option<PathBuf>,

    /// Worker ID (defaults to hostname-pid)
    #[arg(long, env = "WORKER_ID")]
    worker_id: Option<String>,

    /// NATS server URL
    #[arg(long, env = "NATS_URL")]
    nats_url: Option<String>,

    /// Maximum concurrent tasks
    #[arg(long, env = "MAX_CONCURRENT_TASKS")]
    max_concurrent_tasks: Option<usize>,

    /// Operation mode (worker or job)
    #[arg(long, value_enum)]
    mode: Option<OperationMode>,

    /// Log level
    #[arg(long, env = "LOG_LEVEL")]
    log_level: Option<String>,

    /// Load plugin from path
    #[arg(long)]
    load_plugin: Vec<String>,
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
    if let Some(id) = args.worker_id {
        config.worker.worker_id = Some(id);
    }
    if let Some(url) = args.nats_url {
        config.queue.nats_url = url;
    }
    if let Some(max) = args.max_concurrent_tasks {
        config.worker.max_concurrent_tasks = max;
    }
    if let Some(mode) = args.mode {
        config.worker.mode = mode;
    }
    if let Some(level) = args.log_level {
        config.observability.log_level = level;
    }

    // Generate worker ID if not provided
    let worker_id = config.worker.worker_id.clone().unwrap_or_else(|| {
        format!(
            "{}-{}",
            hostname::get().unwrap_or_default().to_string_lossy(),
            std::process::id()
        )
    });

    // Initialize tracing
    if config.observability.tracing_enabled {
        // TODO: Initialize Jaeger tracing when the module is fixed
        tracing_subscriber::fmt()
            .with_env_filter(&config.observability.log_level)
            .json()
            .init();
    } else {
        // Just initialize basic logging
        tracing_subscriber::fmt()
            .with_env_filter(&config.observability.log_level)
            .json()
            .init();
    }

    info!("Starting Task Worker v{}", env!("CARGO_PKG_VERSION"));
    info!("Worker ID: {}", worker_id);
    info!("Operation mode: {:?}", config.worker.mode);

    // Initialize plugin manager
    let plugin_manager = Arc::new(PluginManager::new());

    // Load plugins from directories
    if config.plugins.auto_load {
        for dir in &config.plugins.plugin_dirs {
            if !dir.exists() {
                warn!("Plugin directory does not exist: {}", dir.display());
                continue;
            }

            info!("Scanning plugin directory: {}", dir.display());

            for pattern in &config.plugins.plugin_patterns {
                let glob_pattern = dir.join(pattern).to_string_lossy().to_string();

                for entry in glob::glob(&glob_pattern)? {
                    match entry {
                        Ok(path) => {
                            info!("Loading plugin: {}", path.display());
                            match plugin_manager.load_plugin(path.to_str().unwrap()).await {
                                Ok(tasks) => {
                                    info!("Loaded {} tasks from {}", tasks.len(), path.display());
                                }
                                Err(e) => {
                                    error!("Failed to load plugin {}: {}", path.display(), e);
                                }
                            }
                        }
                        Err(e) => {
                            error!("Failed to read plugin path: {}", e);
                        }
                    }
                }
            }
        }
    }

    // Load plugins from command line
    for plugin_path in args.load_plugin {
        info!("Loading plugin from command line: {}", plugin_path);
        match plugin_manager.load_plugin(&plugin_path).await {
            Ok(tasks) => {
                info!("Loaded {} tasks from {}", tasks.len(), plugin_path);
            }
            Err(e) => {
                error!("Failed to load plugin {}: {}", plugin_path, e);
            }
        }
    }

    // List loaded tasks
    let methods = plugin_manager.list_methods();
    info!("Available tasks: {} total", methods.len());
    for method in &methods {
        info!("  - {}", method);
    }

    let tracing_enabled = config.observability.tracing_enabled;

    match config.worker.mode {
        OperationMode::Worker => {
            // Long-running worker mode
            run_worker_mode(worker_id, config, plugin_manager).await?;
        }
        OperationMode::Job => {
            // Single job execution mode
            run_job_mode(worker_id, config, plugin_manager).await?;
        }
    }

    // Shutdown tracing
    if tracing_enabled {
        // TODO: Shutdown tracing when the module is fixed
    }

    info!("Task Worker shut down successfully");
    Ok(())
}

/// Run in worker mode (long-running)
async fn run_worker_mode(
    worker_id: String,
    config: Config,
    plugin_manager: Arc<PluginManager>,
) -> Result<(), Box<dyn std::error::Error>> {
    info!("Running in worker mode");

    // Initialize queue manager
    let queue = Arc::new(QueueManager::new(&config.queue.nats_url).await?);
    queue.initialize().await?;

    // Create and start executor
    let mut executor = TaskExecutor::with_config(
        worker_id,
        queue,
        plugin_manager,
        config.worker.max_concurrent_tasks,
        config.queue.batch_size,
        config.queue.fetch_timeout_ms,
    );

    // Start metrics server
    let metrics_addr: std::net::SocketAddr = config.worker.metrics_address.parse()?;
    let _metrics_handle = tokio::spawn(async move {
        if let Err(e) = start_metrics_server(metrics_addr).await {
            error!("Metrics server error: {}", e);
        }
    });

    // Start executor
    executor.start().await?;

    Ok(())
}

/// Run in job mode (single task execution)
async fn run_job_mode(
    _worker_id: String,
    _config: Config,
    plugin_manager: Arc<PluginManager>,
) -> Result<(), Box<dyn std::error::Error>> {
    info!("Running in job mode");

    // Get task parameters from environment
    let method =
        std::env::var("TASK_METHOD").map_err(|_| "TASK_METHOD environment variable not set")?;

    let args_json =
        std::env::var("TASK_ARGS").map_err(|_| "TASK_ARGS environment variable not set")?;

    let timeout_seconds: u64 = std::env::var("TASK_TIMEOUT")
        .unwrap_or_else(|_| "3600".to_string())
        .parse()?;

    info!(
        "Executing task: {} with timeout {}s",
        method, timeout_seconds
    );

    // Parse arguments
    let args: Vec<serde_json::Value> = serde_json::from_str(&args_json)?;

    // Execute task
    let timeout_duration = std::time::Duration::from_secs(timeout_seconds);
    let result = plugin_manager
        .execute_task(&method, args, timeout_duration)
        .await;

    match result {
        Ok(value) => {
            info!("Task completed successfully");
            println!("{}", serde_json::to_string(&value)?);
            Ok(())
        }
        Err(e) => {
            error!("Task failed: {}", e);
            std::process::exit(1);
        }
    }
}

/// Start metrics server
async fn start_metrics_server(
    addr: std::net::SocketAddr,
) -> Result<(), Box<dyn std::error::Error>> {
    use axum::{routing::get, Router};

    let app = Router::new()
        .route("/metrics", get(metrics_handler))
        .route("/health", get(health_handler));

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

/// Metrics handler
async fn metrics_handler() -> impl axum::response::IntoResponse {
    use prometheus::{Encoder, TextEncoder};

    let encoder = TextEncoder::new();
    let metric_families = prometheus::gather();

    let mut buffer = Vec::new();
    if let Err(e) = encoder.encode(&metric_families, &mut buffer) {
        return (
            axum::http::StatusCode::INTERNAL_SERVER_ERROR,
            format!("Failed to encode metrics: {}", e),
        )
            .into_response();
    }

    (
        axum::http::StatusCode::OK,
        [(axum::http::header::CONTENT_TYPE, encoder.format_type())],
        buffer,
    )
        .into_response()
}

/// Health check handler
async fn health_handler() -> impl axum::response::IntoResponse {
    (axum::http::StatusCode::OK, "OK")
}
