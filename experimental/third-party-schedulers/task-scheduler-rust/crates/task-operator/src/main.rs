use axum::response::IntoResponse;
use clap::Parser;
use kube::Client;
use prometheus::{Encoder, TextEncoder};
use std::sync::Arc;
use task_common::{
    queue::QueueManager,
    tracing::{init_tracing, shutdown_tracing, TracingConfig},
};
use task_operator::{ResultListener, TaskController};
use tracing::{error, info};

#[derive(Parser, Debug)]
#[command(author, version, about, long_about = None)]
struct Args {
    /// Namespace to watch (defaults to all namespaces)
    #[arg(long, env = "NAMESPACE")]
    namespace: Option<String>,

    /// NATS server URL
    #[arg(long, env = "NATS_URL", default_value = "nats://localhost:4222")]
    nats_url: String,

    /// Log level
    #[arg(long, env = "LOG_LEVEL", default_value = "info")]
    log_level: String,

    /// OTLP endpoint for tracing
    #[arg(long, env = "OTLP_ENDPOINT", default_value = "http://localhost:4317")]
    otlp_endpoint: String,

    /// Enable tracing
    #[arg(long, env = "TRACING_ENABLED", default_value = "true")]
    tracing_enabled: bool,

    /// Metrics address
    #[arg(long, env = "METRICS_ADDRESS", default_value = "0.0.0.0:9002")]
    metrics_address: String,
}

#[tokio::main]
async fn main() -> Result<(), Box<dyn std::error::Error>> {
    let args = Args::parse();

    // Initialize tracing
    if args.tracing_enabled {
        let tracing_config = TracingConfig {
            service_name: "task-operator".to_string(),
            service_version: env!("CARGO_PKG_VERSION").to_string(),
            otlp_endpoint: args.otlp_endpoint,
            environment: "production".to_string(),
            sampling_ratio: 1.0,
            export_timeout: std::time::Duration::from_secs(10),
            log_level: args.log_level.clone(),
        };

        init_tracing(tracing_config)?;
    } else {
        // Just initialize basic logging
        tracing_subscriber::fmt()
            .with_env_filter(&args.log_level)
            .json()
            .init();
    }

    info!("Starting Task Operator v{}", env!("CARGO_PKG_VERSION"));

    // Create Kubernetes client
    let client = Client::try_default().await?;

    // Determine namespace
    let namespace = if let Some(ns) = args.namespace {
        info!("Watching namespace: {}", ns);
        ns
    } else {
        // Try to get namespace from service account
        let ns = std::fs::read_to_string("/var/run/secrets/kubernetes.io/serviceaccount/namespace")
            .unwrap_or_else(|_| "default".to_string())
            .trim()
            .to_string();
        info!("Watching namespace: {} (from service account)", ns);
        ns
    };

    // Initialize queue manager
    let queue = Arc::new(QueueManager::new(&args.nats_url).await?);
    queue.initialize().await?;

    // Create controller and result listener
    let controller = TaskController::new(client.clone(), namespace.clone(), queue.clone()).await?;
    info!("Creating result listener for namespace: {}", namespace);
    let result_listener = ResultListener::new(client, namespace.clone(), queue);

    // Start metrics server
    let metrics_addr: std::net::SocketAddr = args.metrics_address.parse()?;
    let metrics_handle = tokio::spawn(async move {
        if let Err(e) = run_metrics_server(metrics_addr).await {
            error!("Metrics server error: {}", e);
        }
    });

    // Run controller
    let controller_handle = tokio::spawn(async move {
        if let Err(e) = controller.run().await {
            error!("Controller error: {}", e);
        }
    });

    // Run result listener
    info!("Starting result listener task...");
    let result_listener_handle = tokio::spawn(async move {
        info!("Result listener task started");
        if let Err(e) = result_listener.start().await {
            error!("Result listener error: {}", e);
        }
        info!("Result listener task ended");
    });

    // Wait for shutdown signal
    tokio::signal::ctrl_c().await?;
    info!("Received shutdown signal");

    // Cancel tasks
    controller_handle.abort();
    result_listener_handle.abort();
    metrics_handle.abort();

    // Shutdown tracing
    if args.tracing_enabled {
        shutdown_tracing();
    }

    info!("Task Operator shut down successfully");
    Ok(())
}

/// Run metrics server
async fn run_metrics_server(addr: std::net::SocketAddr) -> Result<(), Box<dyn std::error::Error>> {
    use axum::{routing::get, Router};

    info!("Starting metrics server on {}", addr);

    let app = Router::new()
        .route("/metrics", get(metrics_handler))
        .route("/health", get(health_handler));

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

/// Metrics handler
async fn metrics_handler() -> impl axum::response::IntoResponse {
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
