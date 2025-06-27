use crate::error::{TaskError, TaskResult};
use opentelemetry::{global, trace::TracerProvider};
use opentelemetry_otlp::{SpanExporter, WithExportConfig};
use opentelemetry_sdk::propagation::TraceContextPropagator;
use std::time::Duration;
use tracing::info;
use tracing_opentelemetry::OpenTelemetryLayer;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter, Registry};

/// Distributed tracing configuration
#[derive(Debug, Clone)]
pub struct TracingConfig {
    /// Service name
    pub service_name: String,

    /// Service version
    pub service_version: String,

    /// OTLP endpoint (e.g., "http://localhost:4317")
    pub otlp_endpoint: String,

    /// Environment (e.g., "development", "production")
    pub environment: String,

    /// Sampling ratio (0.0 to 1.0)
    pub sampling_ratio: f64,

    /// Export timeout
    pub export_timeout: Duration,

    /// Log level filter
    pub log_level: String,
}

impl Default for TracingConfig {
    fn default() -> Self {
        Self {
            service_name: "task-scheduler".to_string(),
            service_version: env!("CARGO_PKG_VERSION").to_string(),
            otlp_endpoint: "http://localhost:4317".to_string(),
            environment: "development".to_string(),
            sampling_ratio: 1.0,
            export_timeout: Duration::from_secs(10),
            log_level: "info".to_string(),
        }
    }
}

/// Initialize distributed tracing
pub fn init_tracing(config: TracingConfig) -> TaskResult<()> {
    info!(
        "Initializing distributed tracing for service: {}",
        config.service_name
    );

    // OpenTelemetry 0.30 removed global error handler
    // Errors are now handled through the Result types

    // Create OTLP exporter
    let exporter = SpanExporter::builder()
        .with_tonic()
        .with_endpoint(&config.otlp_endpoint)
        .build()
        .map_err(|e| TaskError::InternalError(format!("Failed to create exporter: {}", e)))?;

    // Create trace provider
    // In OpenTelemetry 0.30, TracerProvider is configured differently
    let tracer_provider = opentelemetry_sdk::trace::SdkTracerProvider::builder()
        .with_batch_exporter(exporter)
        .build();

    // Set as global tracer provider
    global::set_tracer_provider(tracer_provider.clone());

    // Set global propagator
    global::set_text_map_propagator(TraceContextPropagator::new());

    // Create OpenTelemetry layer
    let telemetry_layer =
        OpenTelemetryLayer::new(tracer_provider.tracer(config.service_name.clone()));

    // Create environment filter
    let env_filter =
        EnvFilter::try_from_default_env().unwrap_or_else(|_| EnvFilter::new(&config.log_level));

    // Create JSON formatter for structured logging
    let formatting_layer = tracing_subscriber::fmt::layer()
        .json()
        .with_current_span(true)
        .with_span_list(true);

    // Initialize subscriber
    Registry::default()
        .with(env_filter)
        .with(formatting_layer)
        .with(telemetry_layer)
        .try_init()
        .map_err(|e| TaskError::InternalError(format!("Failed to initialize tracing: {}", e)))?;

    info!("Distributed tracing initialized successfully");
    Ok(())
}

/// Shutdown tracing gracefully
pub fn shutdown_tracing() {
    info!("Shutting down distributed tracing");
    // In OpenTelemetry 0.30, shutdown is handled by dropping the provider
    // or calling shutdown on the provider instance directly
}

/// Extract trace context from HTTP headers
pub fn extract_trace_context(headers: &http::HeaderMap) -> opentelemetry::Context {
    use opentelemetry::propagation::Extractor;

    struct HeaderExtractor<'a>(&'a http::HeaderMap);

    impl<'a> Extractor for HeaderExtractor<'a> {
        fn get(&self, key: &str) -> Option<&str> {
            self.0.get(key).and_then(|v| v.to_str().ok())
        }

        fn keys(&self) -> Vec<&str> {
            self.0.keys().map(|k| k.as_str()).collect()
        }
    }

    global::get_text_map_propagator(|propagator| propagator.extract(&HeaderExtractor(headers)))
}

/// Inject trace context into HTTP headers
pub fn inject_trace_context(context: &opentelemetry::Context, headers: &mut http::HeaderMap) {
    use http::HeaderValue;
    use opentelemetry::propagation::Injector;

    struct HeaderInjector<'a>(&'a mut http::HeaderMap);

    impl<'a> Injector for HeaderInjector<'a> {
        fn set(&mut self, key: &str, value: String) {
            if let Ok(header_name) = http::HeaderName::from_bytes(key.as_bytes()) {
                if let Ok(header_value) = HeaderValue::from_str(&value) {
                    self.0.insert(header_name, header_value);
                }
            }
        }
    }

    global::get_text_map_propagator(|propagator| {
        propagator.inject_context(context, &mut HeaderInjector(headers))
    });
}

/// Create a span for task execution
#[macro_export]
macro_rules! task_span {
    ($name:expr, $task_id:expr) => {
        tracing::info_span!(
            $name,
            task.id = %$task_id,
            otel.kind = "server",
            otel.status_code = tracing::field::Empty,
            otel.status_message = tracing::field::Empty,
        )
    };
    ($name:expr, $task_id:expr, $($field:tt)*) => {
        tracing::info_span!(
            $name,
            task.id = %$task_id,
            otel.kind = "server",
            otel.status_code = tracing::field::Empty,
            otel.status_message = tracing::field::Empty,
            $($field)*
        )
    };
}

/// Record span status
#[macro_export]
macro_rules! record_status {
    ($span:expr, ok) => {
        $span.record("otel.status_code", "OK");
    };
    ($span:expr, error, $msg:expr) => {
        $span.record("otel.status_code", "ERROR");
        $span.record("otel.status_message", $msg);
    };
}

/// Metrics helpers
pub mod metrics {
    use opentelemetry::{
        metrics::{Counter, Histogram, Meter},
        KeyValue,
    };
    use std::time::Duration;

    /// Task metrics
    pub struct TaskMetrics {
        pub tasks_submitted: Counter<u64>,
        pub tasks_completed: Counter<u64>,
        pub tasks_failed: Counter<u64>,
        pub task_duration: Histogram<f64>,
        pub queue_depth: Histogram<u64>,
    }

    impl TaskMetrics {
        /// Create new task metrics
        pub fn new(meter: &Meter) -> Self {
            Self {
                tasks_submitted: meter
                    .u64_counter("task.submitted")
                    .with_description("Total number of tasks submitted")
                    .build(),

                tasks_completed: meter
                    .u64_counter("task.completed")
                    .with_description("Total number of tasks completed successfully")
                    .build(),

                tasks_failed: meter
                    .u64_counter("task.failed")
                    .with_description("Total number of tasks failed")
                    .build(),

                task_duration: meter
                    .f64_histogram("task.duration")
                    .with_description("Task execution duration")
                    .build(),

                queue_depth: meter
                    .u64_histogram("task.queue.depth")
                    .with_description("Number of tasks in queue")
                    .build(),
            }
        }

        /// Record task submission
        pub fn record_submission(&self, method: &str) {
            self.tasks_submitted
                .add(1, &[KeyValue::new("method", method.to_string())]);
        }

        /// Record task completion
        pub fn record_completion(&self, method: &str, duration: Duration) {
            self.tasks_completed
                .add(1, &[KeyValue::new("method", method.to_string())]);
            self.task_duration.record(
                duration.as_secs_f64(),
                &[KeyValue::new("method", method.to_string())],
            );
        }

        /// Record task failure
        pub fn record_failure(&self, method: &str, error: &str) {
            self.tasks_failed.add(
                1,
                &[
                    KeyValue::new("method", method.to_string()),
                    KeyValue::new("error", error.to_string()),
                ],
            );
        }

        /// Record queue depth
        pub fn record_queue_depth(&self, depth: u64) {
            self.queue_depth.record(depth, &[]);
        }
    }
}
