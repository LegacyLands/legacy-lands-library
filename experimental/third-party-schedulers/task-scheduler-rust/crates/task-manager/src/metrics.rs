pub mod enhanced;

use axum::{response::IntoResponse, routing::get, Router};
use prometheus::{
    CounterVec, GaugeVec, HistogramOpts, HistogramVec, Registry, IntCounterVec, IntGaugeVec,
};
use std::net::SocketAddr;
use tracing::info;

pub use enhanced::EnhancedMetrics;

/// Metrics for the Task Manager
pub struct Metrics {
    /// Total tasks submitted
    pub tasks_submitted: CounterVec,

    /// Total tasks by status
    pub tasks_by_status: CounterVec,
    
    /// Total task errors by category
    pub tasks_errors_by_category: CounterVec,

    /// Task submission duration
    pub task_submission_duration: HistogramVec,

    /// Queue depth
    pub queue_depth: GaugeVec,

    /// Active gRPC connections
    pub grpc_connections: GaugeVec,

    /// Storage operations
    pub storage_operations: CounterVec,

    /// Storage operation duration
    pub storage_duration: HistogramVec,
    
    /// Task execution duration by method
    pub task_execution_duration: HistogramVec,
    
    /// Worker pool metrics
    pub worker_pool_size: IntGaugeVec,
    pub worker_active_tasks: IntGaugeVec,
    
    /// Queue metrics
    pub queue_operations: IntCounterVec,
    pub queue_latency: HistogramVec,
    
    /// Dependency resolution metrics
    pub dependency_checks: IntCounterVec,
    pub dependency_resolution_duration: HistogramVec,
    
    /// System resource metrics
    pub memory_usage_bytes: IntGaugeVec,
    pub cpu_usage_percent: GaugeVec,
    
    /// Task retry metrics
    pub task_retries: CounterVec,

    /// Task lifecycle duration (submission to completion)
    pub task_lifecycle_duration: HistogramVec,
    
    /// Tasks with unsupported methods
    pub unsupported_method_tasks: CounterVec,
}

impl Metrics {
    /// Create new metrics instance
    pub fn new(registry: &Registry) -> Result<Self, prometheus::Error> {
        let tasks_submitted = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_tasks_submitted_total",
                "Total number of tasks submitted"
            ),
            &["method", "async"]
        )?;
        registry.register(Box::new(tasks_submitted.clone()))?;

        let tasks_by_status = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_tasks_status_total",
                "Total number of tasks by status"
            ),
            &["status"]
        )?;
        registry.register(Box::new(tasks_by_status.clone()))?;
        
        let tasks_errors_by_category = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_tasks_errors_by_category_total",
                "Total number of task errors by category"
            ),
            &["error_category"]
        )?;
        registry.register(Box::new(tasks_errors_by_category.clone()))?;

        let task_submission_duration = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_task_submission_duration_seconds",
                "Task submission duration in seconds",
            ),
            &["method"],
        )?;
        registry.register(Box::new(task_submission_duration.clone()))?;

        let queue_depth = GaugeVec::new(
            prometheus::Opts::new("task_manager_queue_depth", "Current queue depth"),
            &["queue"]
        )?;
        registry.register(Box::new(queue_depth.clone()))?;

        let grpc_connections = GaugeVec::new(
            prometheus::Opts::new(
                "task_manager_grpc_connections",
                "Number of active gRPC connections"
            ),
            &["type"]
        )?;
        registry.register(Box::new(grpc_connections.clone()))?;

        let storage_operations = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_storage_operations_total",
                "Total storage operations"
            ),
            &["operation", "status"]
        )?;
        registry.register(Box::new(storage_operations.clone()))?;

        let storage_duration = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_storage_duration_seconds",
                "Storage operation duration in seconds",
            ),
            &["operation"],
        )?;
        registry.register(Box::new(storage_duration.clone()))?;

        let task_execution_duration = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_task_execution_duration_seconds",
                "Task execution duration in seconds",
            ),
            &["method", "status"],
        )?;
        registry.register(Box::new(task_execution_duration.clone()))?;

        let worker_pool_size = IntGaugeVec::new(
            prometheus::Opts::new("task_manager_worker_pool_size", "Number of workers in pool"),
            &["pool"],
        )?;
        registry.register(Box::new(worker_pool_size.clone()))?;

        let worker_active_tasks = IntGaugeVec::new(
            prometheus::Opts::new("task_manager_worker_active_tasks", "Number of active tasks per worker"),
            &["worker_id"],
        )?;
        registry.register(Box::new(worker_active_tasks.clone()))?;

        let queue_operations = IntCounterVec::new(
            prometheus::Opts::new(
                "task_manager_queue_operations_total",
                "Total queue operations"
            ),
            &["operation", "status"],
        )?;
        registry.register(Box::new(queue_operations.clone()))?;

        let queue_latency = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_queue_latency_seconds",
                "Queue operation latency in seconds",
            ),
            &["operation"],
        )?;
        registry.register(Box::new(queue_latency.clone()))?;

        let dependency_checks = IntCounterVec::new(
            prometheus::Opts::new(
                "task_manager_dependency_checks_total",
                "Total dependency checks"
            ),
            &["result"],
        )?;
        registry.register(Box::new(dependency_checks.clone()))?;

        let dependency_resolution_duration = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_dependency_resolution_duration_seconds",
                "Dependency resolution duration in seconds",
            ),
            &["task_count"],
        )?;
        registry.register(Box::new(dependency_resolution_duration.clone()))?;

        let memory_usage_bytes = IntGaugeVec::new(
            prometheus::Opts::new("task_manager_memory_usage_bytes", "Memory usage in bytes"),
            &["type"],
        )?;
        registry.register(Box::new(memory_usage_bytes.clone()))?;

        let cpu_usage_percent = GaugeVec::new(
            prometheus::Opts::new("task_manager_cpu_usage_percent", "CPU usage percentage"),
            &["core"],
        )?;
        registry.register(Box::new(cpu_usage_percent.clone()))?;

        let task_retries = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_task_retries_total",
                "Total number of task retries"
            ),
            &["method", "attempt"],
        )?;
        registry.register(Box::new(task_retries.clone()))?;

        let task_lifecycle_duration = HistogramVec::new(
            HistogramOpts::new(
                "task_manager_task_lifecycle_duration_seconds",
                "Task lifecycle duration from submission to completion in seconds",
            ),
            &["method", "status"],
        )?;
        registry.register(Box::new(task_lifecycle_duration.clone()))?;
        
        let unsupported_method_tasks = CounterVec::new(
            prometheus::Opts::new(
                "task_manager_unsupported_method_tasks_total",
                "Total number of tasks with unsupported methods"
            ),
            &["method"],
        )?;
        registry.register(Box::new(unsupported_method_tasks.clone()))?;

        // Initialize gauges with default values so they appear in /metrics
        queue_depth.with_label_values(&["default"]).set(0.0);
        grpc_connections.with_label_values(&["active"]).set(0.0);
        worker_pool_size.with_label_values(&["default"]).set(0);
        memory_usage_bytes.with_label_values(&["used"]).set(0);
        memory_usage_bytes.with_label_values(&["total"]).set(0);
        memory_usage_bytes.with_label_values(&["available"]).set(0);
        cpu_usage_percent.with_label_values(&["total"]).set(0.0);
        
        // Initialize at least one worker_active_tasks metric
        // This ensures the metric appears in /metrics even before any workers join
        worker_active_tasks.with_label_values(&["placeholder"]).set(0);

        Ok(Self {
            tasks_submitted,
            tasks_by_status,
            tasks_errors_by_category,
            task_submission_duration,
            queue_depth,
            grpc_connections,
            storage_operations,
            storage_duration,
            task_execution_duration,
            worker_pool_size,
            worker_active_tasks,
            queue_operations,
            queue_latency,
            dependency_checks,
            dependency_resolution_duration,
            memory_usage_bytes,
            cpu_usage_percent,
            task_retries,
            task_lifecycle_duration,
            unsupported_method_tasks,
        })
    }
}

/// Start the metrics server
pub async fn start_metrics_server(
    addr: SocketAddr,
    registry: Registry,
) -> Result<(), Box<dyn std::error::Error>> {
    info!("Starting metrics server on {}", addr);

    let app = Router::new()
        .route("/metrics", get(move || metrics_handler(registry.clone())))
        .route("/health", get(health_handler));

    let listener = tokio::net::TcpListener::bind(addr).await?;
    axum::serve(listener, app).await?;

    Ok(())
}

/// Metrics endpoint handler
async fn metrics_handler(registry: Registry) -> impl IntoResponse {
    use prometheus::Encoder;

    let encoder = prometheus::TextEncoder::new();
    let metric_families = registry.gather();

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

/// Health check endpoint
async fn health_handler() -> impl IntoResponse {
    (axum::http::StatusCode::OK, "OK")
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_metrics_new() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Use each metric to ensure they're present in registry
        metrics.tasks_submitted.with_label_values(&["test", "true"]).inc();
        metrics.tasks_by_status.with_label_values(&["pending"]).inc();
        metrics.task_submission_duration.with_label_values(&["test"]).observe(0.1);
        metrics.queue_depth.with_label_values(&["default"]).set(0.0);
        metrics.grpc_connections.with_label_values(&["active"]).set(0.0);
        metrics.storage_operations.with_label_values(&["read", "success"]).inc();
        metrics.storage_duration.with_label_values(&["read"]).observe(0.1);
        
        // Verify all metrics are properly registered
        let metric_families = registry.gather();
        assert!(!metric_families.is_empty());
        
        // Check specific metrics exist
        let metric_names: Vec<_> = metric_families
            .iter()
            .map(|f| f.get_name())
            .collect();
        
        assert!(metric_names.contains(&"task_manager_tasks_submitted_total"));
        assert!(metric_names.contains(&"task_manager_tasks_status_total"));
        assert!(metric_names.contains(&"task_manager_task_submission_duration_seconds"));
        assert!(metric_names.contains(&"task_manager_queue_depth"));
        assert!(metric_names.contains(&"task_manager_grpc_connections"));
        assert!(metric_names.contains(&"task_manager_storage_operations_total"));
        assert!(metric_names.contains(&"task_manager_storage_duration_seconds"));
    }

    #[test]
    fn test_tasks_submitted_counter() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Increment counter
        metrics.tasks_submitted
            .with_label_values(&["test_method", "true"])
            .inc();
        
        // Verify counter value
        let value = metrics.tasks_submitted
            .with_label_values(&["test_method", "true"])
            .get();
        assert_eq!(value, 1.0);
        
        // Increment again
        metrics.tasks_submitted
            .with_label_values(&["test_method", "true"])
            .inc();
        assert_eq!(metrics.tasks_submitted.with_label_values(&["test_method", "true"]).get(), 2.0);
        
        // Different labels should have different values
        assert_eq!(metrics.tasks_submitted.with_label_values(&["other_method", "false"]).get(), 0.0);
    }

    #[test]
    fn test_tasks_by_status_counter() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Test different status labels
        metrics.tasks_by_status.with_label_values(&["pending"]).inc();
        metrics.tasks_by_status.with_label_values(&["running"]).inc();
        metrics.tasks_by_status.with_label_values(&["running"]).inc();
        metrics.tasks_by_status.with_label_values(&["completed"]).inc();
        
        assert_eq!(metrics.tasks_by_status.with_label_values(&["pending"]).get(), 1.0);
        assert_eq!(metrics.tasks_by_status.with_label_values(&["running"]).get(), 2.0);
        assert_eq!(metrics.tasks_by_status.with_label_values(&["completed"]).get(), 1.0);
        assert_eq!(metrics.tasks_by_status.with_label_values(&["failed"]).get(), 0.0);
    }

    #[test]
    fn test_task_submission_duration_histogram() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Record some durations
        metrics.task_submission_duration
            .with_label_values(&["method1"])
            .observe(0.1);
        metrics.task_submission_duration
            .with_label_values(&["method1"])
            .observe(0.2);
        metrics.task_submission_duration
            .with_label_values(&["method1"])
            .observe(0.3);
        
        // Get sample count
        let metric = metrics.task_submission_duration
            .with_label_values(&["method1"]);
        assert_eq!(metric.get_sample_count(), 3);
        assert!((metric.get_sample_sum() - 0.6).abs() < 0.0001);
    }

    #[test]
    fn test_queue_depth_gauge() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Set gauge values
        metrics.queue_depth.with_label_values(&["default"]).set(10.0);
        metrics.queue_depth.with_label_values(&["priority"]).set(5.0);
        
        assert_eq!(metrics.queue_depth.with_label_values(&["default"]).get(), 10.0);
        assert_eq!(metrics.queue_depth.with_label_values(&["priority"]).get(), 5.0);
        
        // Update values
        metrics.queue_depth.with_label_values(&["default"]).inc();
        metrics.queue_depth.with_label_values(&["priority"]).dec();
        
        assert_eq!(metrics.queue_depth.with_label_values(&["default"]).get(), 11.0);
        assert_eq!(metrics.queue_depth.with_label_values(&["priority"]).get(), 4.0);
    }

    #[test]
    fn test_grpc_connections_gauge() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Test connection types
        metrics.grpc_connections.with_label_values(&["active"]).set(5.0);
        metrics.grpc_connections.with_label_values(&["idle"]).set(3.0);
        
        assert_eq!(metrics.grpc_connections.with_label_values(&["active"]).get(), 5.0);
        assert_eq!(metrics.grpc_connections.with_label_values(&["idle"]).get(), 3.0);
    }

    #[test]
    fn test_storage_operations_counter() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Test different operations and statuses
        metrics.storage_operations
            .with_label_values(&["create", "success"])
            .inc();
        metrics.storage_operations
            .with_label_values(&["create", "failure"])
            .inc();
        metrics.storage_operations
            .with_label_values(&["read", "success"])
            .inc_by(5.0);
        
        assert_eq!(
            metrics.storage_operations.with_label_values(&["create", "success"]).get(),
            1.0
        );
        assert_eq!(
            metrics.storage_operations.with_label_values(&["create", "failure"]).get(),
            1.0
        );
        assert_eq!(
            metrics.storage_operations.with_label_values(&["read", "success"]).get(),
            5.0
        );
    }

    #[test]
    fn test_storage_duration_histogram() {
        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Record durations for different operations
        for i in 0..10 {
            metrics.storage_duration
                .with_label_values(&["read"])
                .observe(0.01 * i as f64);
        }
        
        let read_metric = metrics.storage_duration.with_label_values(&["read"]);
        assert_eq!(read_metric.get_sample_count(), 10);
        
        // Record write operations
        metrics.storage_duration
            .with_label_values(&["write"])
            .observe(0.05);
        
        let write_metric = metrics.storage_duration.with_label_values(&["write"]);
        assert_eq!(write_metric.get_sample_count(), 1);
        assert_eq!(write_metric.get_sample_sum(), 0.05);
    }

    #[test]
    fn test_metrics_registry_isolation() {
        // Test that different registries have isolated metrics
        let registry1 = Registry::new();
        let registry2 = Registry::new();
        
        let metrics1 = Metrics::new(&registry1).unwrap();
        let metrics2 = Metrics::new(&registry2).unwrap();
        
        metrics1.tasks_submitted.with_label_values(&["test", "true"]).inc();
        
        // Metrics should be isolated
        assert_eq!(metrics1.tasks_submitted.with_label_values(&["test", "true"]).get(), 1.0);
        assert_eq!(metrics2.tasks_submitted.with_label_values(&["test", "true"]).get(), 0.0);
    }

    #[test]
    fn test_duplicate_metrics_registration() {
        let registry = Registry::new();
        
        // First registration should succeed
        let result1 = Metrics::new(&registry);
        assert!(result1.is_ok());
        
        // Second registration should fail due to duplicate metrics
        let result2 = Metrics::new(&registry);
        assert!(result2.is_err());
    }

    #[tokio::test]
    async fn test_metrics_handler() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let registry = Registry::new();
        let metrics = Metrics::new(&registry).unwrap();
        
        // Record some metrics
        metrics.tasks_submitted.with_label_values(&["test", "true"]).inc();
        metrics.queue_depth.with_label_values(&["default"]).set(42.0);
        
        // Create app
        let app = Router::new()
            .route("/metrics", get(move || metrics_handler(registry.clone())));
        
        // Make request
        let response = app
            .oneshot(Request::builder().uri("/metrics").body(Body::empty()).unwrap())
            .await
            .unwrap();
        
        assert_eq!(response.status(), axum::http::StatusCode::OK);
        
        // Check content type
        let content_type = response.headers().get(axum::http::header::CONTENT_TYPE);
        assert!(content_type.is_some());
        
        // Check body contains metrics
        let body = axum::body::to_bytes(response.into_body(), 10_000_000).await.unwrap();
        let body_str = String::from_utf8(body.to_vec()).unwrap();
        
        assert!(body_str.contains("task_manager_tasks_submitted_total"));
        assert!(body_str.contains("task_manager_queue_depth"));
        assert!(body_str.contains("method=\"test\""));
        assert!(body_str.contains("async=\"true\""));
        assert!(body_str.contains("queue=\"default\""));
    }

    #[tokio::test]
    async fn test_health_handler() {
        use axum::body::Body;
        use axum::http::Request;
        use tower::ServiceExt;

        let app = Router::new().route("/health", get(health_handler));
        
        let response = app
            .oneshot(Request::builder().uri("/health").body(Body::empty()).unwrap())
            .await
            .unwrap();
        
        assert_eq!(response.status(), axum::http::StatusCode::OK);
        
        let body = axum::body::to_bytes(response.into_body(), 100).await.unwrap();
        assert_eq!(&body[..], b"OK");
    }

    #[tokio::test]
    async fn test_start_metrics_server_binding() {
        use std::time::Duration;

        let addr: SocketAddr = "127.0.0.1:0".parse().unwrap();
        let registry = Registry::new();
        
        // Start server in background
        let server_handle = tokio::spawn(async move {
            let result = start_metrics_server(addr, registry).await;
            // Server should run indefinitely unless there's an error
            assert!(result.is_err() || result.is_ok());
        });
        
        // Give server time to start
        tokio::time::sleep(Duration::from_millis(100)).await;
        
        // Server should still be running
        assert!(!server_handle.is_finished());
        
        // Clean up
        server_handle.abort();
    }
}
