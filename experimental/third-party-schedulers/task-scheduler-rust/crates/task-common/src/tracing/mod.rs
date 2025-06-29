/// Distributed tracing support for task scheduler
pub mod jaeger;

pub use jaeger::{
    init_jaeger_tracing, shutdown_tracing, create_tracer, JaegerConfig,
    propagation, attributes,
};