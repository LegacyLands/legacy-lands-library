use opentelemetry::{
    global,
    trace::{Tracer, TracerProvider},
    KeyValue,
};
use opentelemetry_sdk::{
    propagation::TraceContextPropagator,
    trace::{self, RandomIdGenerator, Sampler},
    Resource,
};
use opentelemetry_otlp::WithExportConfig;
use opentelemetry_semantic_conventions::resource::{SERVICE_NAME, SERVICE_VERSION};
use std::time::Duration;
use tracing::info;
use tracing_opentelemetry::OpenTelemetryLayer;
use tracing_subscriber::{layer::SubscriberExt, util::SubscriberInitExt, EnvFilter, Registry};

/// Configuration for Jaeger tracing
#[derive(Debug, Clone)]
pub struct JaegerConfig {
    /// Service name
    pub service_name: String,
    /// Service version
    pub service_version: String,
    /// Deployment environment (e.g., dev, staging, prod)
    pub environment: String,
    /// Jaeger endpoint URL
    pub endpoint: String,
    /// Sampling ratio (0.0 to 1.0)
    pub sampling_ratio: f64,
    /// Export timeout
    pub export_timeout: Duration,
}

impl Default for JaegerConfig {
    fn default() -> Self {
        Self {
            service_name: "task-scheduler".to_string(),
            service_version: env!("CARGO_PKG_VERSION").to_string(),
            environment: "development".to_string(),
            endpoint: "http://localhost:4317".to_string(),
            sampling_ratio: 1.0,
            export_timeout: Duration::from_secs(10),
        }
    }
}

/// Initialize Jaeger tracing
pub fn init_jaeger_tracing(config: JaegerConfig) -> Result<(), Box<dyn std::error::Error + Send + Sync>> {
    // Set global propagator
    global::set_text_map_propagator(TraceContextPropagator::new());

    // Configure trace provider
    let mut trace_config = trace::Config::default();
    trace_config.sampler = Box::new(Sampler::TraceIdRatioBased(config.sampling_ratio));
    trace_config.id_generator = Box::new(RandomIdGenerator::default());
    trace_config.span_limits.max_events_per_span = 64;
    trace_config.span_limits.max_attributes_per_span = 64; 
    trace_config.span_limits.max_links_per_span = 16;
    
    let resource = Resource::builder()
        .with_attribute(KeyValue::new(SERVICE_NAME, config.service_name.clone()))
        .with_attribute(KeyValue::new(SERVICE_VERSION, config.service_version.clone()))
        .with_attribute(KeyValue::new("deployment.environment", config.environment.clone()))
        .build();
    trace_config.resource = std::borrow::Cow::Owned(resource);

    // Create OTLP exporter
    let exporter = opentelemetry_otlp::SpanExporter::builder()
        .with_tonic()
        .with_endpoint(config.endpoint.clone())
        .with_timeout(config.export_timeout)
        .build()?;

    // Build tracer provider
    let provider = opentelemetry_sdk::trace::SdkTracerProvider::builder()
        .with_sampler(Sampler::TraceIdRatioBased(config.sampling_ratio))
        .with_id_generator(RandomIdGenerator::default())
        .with_span_limits(trace_config.span_limits.clone())
        .with_resource(trace_config.resource.into_owned())
        .with_batch_exporter(exporter)
        .build();
    
    global::set_tracer_provider(provider.clone());
    let tracer = provider.tracer("task-scheduler");

    // Create telemetry layer
    let telemetry_layer = OpenTelemetryLayer::new(tracer);

    // Initialize tracing subscriber
    let subscriber = Registry::default()
        .with(EnvFilter::from_default_env())
        .with(tracing_subscriber::fmt::layer())
        .with(telemetry_layer);

    subscriber.init();

    info!(
        "Jaeger tracing initialized for service '{}' v{} in '{}' environment",
        config.service_name, config.service_version, config.environment
    );

    Ok(())
}

/// Shutdown tracing and flush remaining spans
pub fn shutdown_tracing() {
    // Opentelemetry 0.30 doesn't have a global shutdown function
    // The provider will be shut down when dropped
    info!("Jaeger tracing shutdown complete");
}

/// Create a new tracer for a specific component
pub fn create_tracer(component_name: &'static str) -> impl Tracer {
    global::tracer(component_name)
}

/// Utility macros for common span operations
#[macro_export]
macro_rules! trace_task_submission {
    ($task:expr) => {{
        use tracing::{info_span, Instrument};
        
        let span = info_span!(
            "task.submit",
            task.id = %$task.id,
            task.method = %$task.task_type,
            task.priority = $task.priority,
            task.is_async = $task.is_async,
            otel.kind = "producer"
        );
        
        async move {
            tracing::info!("Submitting task");
            $task
        }.instrument(span)
    }};
}

#[macro_export]
macro_rules! trace_task_execution {
    ($task_id:expr, $method:expr, $body:expr) => {{
        use tracing::{info_span, Instrument};
        
        let span = info_span!(
            "task.execute",
            task.id = %$task_id,
            task.method = %$method,
            otel.kind = "consumer"
        );
        
        async move {
            tracing::info!("Executing task");
            $body
        }.instrument(span)
    }};
}

#[macro_export]
macro_rules! trace_storage_operation {
    ($operation:expr, $storage_type:expr, $body:expr) => {{
        use tracing::{info_span, Instrument};
        
        let span = info_span!(
            "storage.operation",
            db.operation = %$operation,
            db.system = %$storage_type,
            otel.kind = "client"
        );
        
        async move {
            $body
        }.instrument(span)
    }};
}

#[macro_export]
macro_rules! trace_queue_operation {
    ($operation:expr, $queue_name:expr, $body:expr) => {{
        use tracing::{info_span, Instrument};
        
        let span = info_span!(
            "queue.operation",
            messaging.operation = %$operation,
            messaging.system = "nats",
            messaging.destination = %$queue_name,
            otel.kind = "producer"
        );
        
        async move {
            $body
        }.instrument(span)
    }};
}

/// Context propagation utilities
pub mod propagation {
    use opentelemetry::{
        propagation::{Extractor, Injector},
        global,
    };
    use std::collections::HashMap;
    use tonic::metadata::MetadataMap;

    /// Extract trace context from gRPC metadata
    pub fn extract_from_grpc(metadata: &MetadataMap) -> opentelemetry::Context {
        struct MetadataExtractor<'a>(&'a MetadataMap);
        
        impl<'a> Extractor for MetadataExtractor<'a> {
            fn get(&self, key: &str) -> Option<&str> {
                self.0.get(key).and_then(|v| v.to_str().ok())
            }

            fn keys(&self) -> Vec<&str> {
                self.0.keys()
                    .filter_map(|k| match k {
                        tonic::metadata::KeyRef::Ascii(s) => Some(s.as_str()),
                        _ => None,
                    })
                    .collect()
            }
        }

        global::get_text_map_propagator(|propagator| {
            propagator.extract(&MetadataExtractor(metadata))
        })
    }

    /// Inject trace context into gRPC metadata
    pub fn inject_to_grpc(metadata: &mut MetadataMap) {
        struct MetadataInjector<'a>(&'a mut MetadataMap);
        
        impl<'a> Injector for MetadataInjector<'a> {
            fn set(&mut self, key: &str, value: String) {
                if let Ok(key) = tonic::metadata::MetadataKey::from_bytes(key.as_bytes()) {
                    if let Ok(val) = tonic::metadata::MetadataValue::try_from(&value) {
                        self.0.insert(key, val);
                    }
                }
            }
        }

        global::get_text_map_propagator(|propagator| {
            propagator.inject_context(&opentelemetry::Context::current(), &mut MetadataInjector(metadata))
        });
    }

    /// Extract trace context from HTTP headers
    pub fn extract_from_http(headers: &HashMap<String, String>) -> opentelemetry::Context {
        struct HeaderExtractor<'a>(&'a HashMap<String, String>);
        
        impl<'a> Extractor for HeaderExtractor<'a> {
            fn get(&self, key: &str) -> Option<&str> {
                self.0.get(key).map(|v| v.as_str())
            }

            fn keys(&self) -> Vec<&str> {
                self.0.keys().map(|k| k.as_str()).collect()
            }
        }

        global::get_text_map_propagator(|propagator| {
            propagator.extract(&HeaderExtractor(headers))
        })
    }

    /// Inject trace context into HTTP headers
    pub fn inject_to_http(headers: &mut HashMap<String, String>) {
        struct HeaderInjector<'a>(&'a mut HashMap<String, String>);
        
        impl<'a> Injector for HeaderInjector<'a> {
            fn set(&mut self, key: &str, value: String) {
                self.0.insert(key.to_string(), value);
            }
        }

        global::get_text_map_propagator(|propagator| {
            propagator.inject_context(&opentelemetry::Context::current(), &mut HeaderInjector(headers))
        });
    }
}

/// Span attributes for common operations
pub mod attributes {
    use opentelemetry::KeyValue;
    
    /// Task-related attributes
    pub fn task_attributes(task_id: &str, task_type: &str, priority: i32) -> Vec<KeyValue> {
        vec![
            KeyValue::new("task.id", task_id.to_string()),
            KeyValue::new("task.type", task_type.to_string()),
            KeyValue::new("task.priority", priority as i64),
        ]
    }
    
    /// Worker-related attributes
    pub fn worker_attributes(worker_id: &str, worker_type: &str) -> Vec<KeyValue> {
        vec![
            KeyValue::new("worker.id", worker_id.to_string()),
            KeyValue::new("worker.type", worker_type.to_string()),
        ]
    }
    
    /// Error attributes
    pub fn error_attributes(error_type: &str, error_message: &str) -> Vec<KeyValue> {
        vec![
            KeyValue::new("error.type", error_type.to_string()),
            KeyValue::new("error.message", error_message.to_string()),
        ]
    }
    
    /// Storage attributes
    pub fn storage_attributes(backend: &str, operation: &str) -> Vec<KeyValue> {
        vec![
            KeyValue::new("db.system", backend.to_string()),
            KeyValue::new("db.operation", operation.to_string()),
        ]
    }
    
    /// Queue attributes
    pub fn queue_attributes(queue_name: &str, operation: &str) -> Vec<KeyValue> {
        vec![
            KeyValue::new("messaging.system", "nats"),
            KeyValue::new("messaging.destination", queue_name.to_string()),
            KeyValue::new("messaging.operation", operation.to_string()),
        ]
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_jaeger_config_default() {
        let config = JaegerConfig::default();
        assert_eq!(config.service_name, "task-scheduler");
        assert_eq!(config.environment, "development");
        assert_eq!(config.endpoint, "http://localhost:4317");
        assert_eq!(config.sampling_ratio, 1.0);
    }

    #[test]
    fn test_attributes() {
        let task_attrs = attributes::task_attributes("task-123", "process_data", 10);
        assert_eq!(task_attrs.len(), 3);
        
        let worker_attrs = attributes::worker_attributes("worker-1", "cpu");
        assert_eq!(worker_attrs.len(), 2);
        
        let error_attrs = attributes::error_attributes("ValidationError", "Invalid input");
        assert_eq!(error_attrs.len(), 2);
    }
}