//! Integration and E2E tests for the Task Scheduler system.
//!
//! This crate contains all integration tests, E2E tests, performance tests,
//! and benchmarks for the task scheduler system.

// Re-export specific modules to avoid conflicts
pub use task_common::{events, models, tracing, crd};
pub use task_manager::{api, dependency_manager};
pub use task_worker::executor;

/// Test utilities module
pub mod test_utils {
    use std::time::Duration;
    
    /// Default timeout for test operations
    pub const DEFAULT_TIMEOUT: Duration = Duration::from_secs(30);
    
    /// Test-specific configuration
    pub struct TestConfig {
        pub timeout: Duration,
        pub retry_interval: Duration,
        pub max_retries: u32,
    }
    
    impl Default for TestConfig {
        fn default() -> Self {
            Self {
                timeout: DEFAULT_TIMEOUT,
                retry_interval: Duration::from_millis(500),
                max_retries: 60,
            }
        }
    }
}