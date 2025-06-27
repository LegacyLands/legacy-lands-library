use crate::api::proto::TaskRequest;
use std::collections::HashSet;
use task_common::{
    error::{TaskError, TaskResult},
    Uuid,
};
use tracing::{debug, warn};

/// Task validator to ensure task requests are valid
pub struct TaskValidator {
    /// Set of supported methods
    supported_methods: HashSet<String>,

    /// Maximum task dependencies
    max_dependencies: usize,

    /// Maximum argument size in bytes
    max_arg_size: usize,
}

impl TaskValidator {
    /// Create a new task validator
    pub fn new() -> Self {
        Self {
            supported_methods: HashSet::new(),
            max_dependencies: 100,
            max_arg_size: 1024 * 1024, // 1MB
        }
    }

    /// Register a supported method
    pub fn register_method(&mut self, method: String) {
        self.supported_methods.insert(method);
    }

    /// Register multiple methods
    pub fn register_methods(&mut self, methods: Vec<String>) {
        for method in methods {
            self.supported_methods.insert(method);
        }
    }

    /// Validate a task request
    pub async fn validate(&self, request: &TaskRequest) -> TaskResult<()> {
        debug!("Validating task request: {}", request.task_id);

        // Validate method name
        if request.method.is_empty() {
            return Err(TaskError::InvalidConfiguration(
                "Method name cannot be empty".to_string(),
            ));
        }

        // Check if method is supported (if we have a whitelist)
        if !self.supported_methods.is_empty() && !self.supported_methods.contains(&request.method) {
            return Err(TaskError::MethodNotFound(request.method.clone()));
        }

        // Validate dependencies
        if request.deps.len() > self.max_dependencies {
            return Err(TaskError::InvalidConfiguration(format!(
                "Too many dependencies: {} (max: {})",
                request.deps.len(),
                self.max_dependencies
            )));
        }

        // Validate dependency IDs
        for dep in &request.deps {
            if Uuid::parse_str(dep).is_err() {
                return Err(TaskError::InvalidConfiguration(format!(
                    "Invalid dependency ID: {}",
                    dep
                )));
            }
        }

        // Validate arguments size
        let total_arg_size: usize = request
            .args
            .iter()
            .map(|arg| {
                // Estimate size - in production, you'd serialize and measure
                arg.value.len()
            })
            .sum();

        if total_arg_size > self.max_arg_size {
            return Err(TaskError::InvalidConfiguration(format!(
                "Arguments too large: {} bytes (max: {} bytes)",
                total_arg_size, self.max_arg_size
            )));
        }

        debug!("Task request validation successful");
        Ok(())
    }

    /// Check if all dependencies are satisfied
    pub async fn check_dependencies(
        &self,
        dependencies: &[Uuid],
        storage: &crate::storage::TaskStorage,
    ) -> TaskResult<bool> {
        if dependencies.is_empty() {
            return Ok(true);
        }

        debug!("Checking {} dependencies", dependencies.len());

        for dep_id in dependencies {
            match storage.get(dep_id).await {
                Some(dep_task) => {
                    use task_common::models::TaskStatus;
                    match &dep_task.status {
                        TaskStatus::Succeeded { .. } => {
                            debug!("Dependency {} is completed", dep_id);
                        }
                        TaskStatus::Failed { .. } => {
                            warn!("Dependency {} has failed", dep_id);
                            return Ok(false);
                        }
                        TaskStatus::Cancelled { .. } => {
                            warn!("Dependency {} was cancelled", dep_id);
                            return Ok(false);
                        }
                        _ => {
                            debug!("Dependency {} is not yet completed", dep_id);
                            return Ok(false);
                        }
                    }
                }
                None => {
                    warn!("Dependency {} not found", dep_id);
                    return Ok(false);
                }
            }
        }

        debug!("All dependencies satisfied");
        Ok(true)
    }
}

impl Default for TaskValidator {
    fn default() -> Self {
        Self::new()
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use task_common::models::{TaskInfo, TaskMetadata, TaskStatus};
    use chrono::Utc;

    #[tokio::test]
    async fn test_task_validator_new() {
        let validator = TaskValidator::new();
        assert_eq!(validator.max_dependencies, 100);
        assert_eq!(validator.max_arg_size, 1024 * 1024);
        assert!(validator.supported_methods.is_empty());
    }

    #[tokio::test]
    async fn test_register_method() {
        let mut validator = TaskValidator::new();
        validator.register_method("test_method".to_string());
        assert!(validator.supported_methods.contains("test_method"));
    }

    #[tokio::test]
    async fn test_register_multiple_methods() {
        let mut validator = TaskValidator::new();
        let methods = vec!["method1".to_string(), "method2".to_string(), "method3".to_string()];
        validator.register_methods(methods.clone());
        
        for method in methods {
            assert!(validator.supported_methods.contains(&method));
        }
    }

    #[tokio::test]
    async fn test_validate_empty_method() {
        let validator = TaskValidator::new();
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "".to_string(),
            args: vec![],
            deps: vec![],
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InvalidConfiguration(msg) => {
                assert_eq!(msg, "Method name cannot be empty");
            }
            _ => panic!("Expected InvalidConfiguration error"),
        }
    }

    #[tokio::test]
    async fn test_validate_unsupported_method() {
        let mut validator = TaskValidator::new();
        validator.register_method("supported_method".to_string());
        
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "unsupported_method".to_string(),
            args: vec![],
            deps: vec![],
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::MethodNotFound(method) => {
                assert_eq!(method, "unsupported_method");
            }
            _ => panic!("Expected MethodNotFound error"),
        }
    }

    #[tokio::test]
    async fn test_validate_too_many_dependencies() {
        let validator = TaskValidator::new();
        let deps: Vec<String> = (0..101).map(|_| Uuid::new_v4().to_string()).collect();
        
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "test_method".to_string(),
            args: vec![],
            deps,
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InvalidConfiguration(msg) => {
                assert!(msg.contains("Too many dependencies"));
            }
            _ => panic!("Expected InvalidConfiguration error"),
        }
    }

    #[tokio::test]
    async fn test_validate_invalid_dependency_id() {
        let validator = TaskValidator::new();
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "test_method".to_string(),
            args: vec![],
            deps: vec!["invalid-uuid".to_string()],
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InvalidConfiguration(msg) => {
                assert!(msg.contains("Invalid dependency ID"));
            }
            _ => panic!("Expected InvalidConfiguration error"),
        }
    }

    #[tokio::test]
    async fn test_validate_args_too_large() {
        let validator = TaskValidator::new();
        
        // Create large arguments
        let large_value = "x".repeat(1024 * 1024 + 1);
        let _args = vec![serde_json::json!(large_value)];
        
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "test_method".to_string(),
            args: vec![], // Proto expects google.protobuf.Any, simplified for test
            deps: vec![],
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::InvalidConfiguration(msg) => {
                assert!(msg.contains("Arguments too large"));
            }
            _ => panic!("Expected InvalidConfiguration error"),
        }
    }

    #[tokio::test]
    async fn test_validate_success() {
        let validator = TaskValidator::new();
        let request = TaskRequest {
            task_id: Uuid::new_v4().to_string(),
            method: "test_method".to_string(),
            args: vec![],
            deps: vec![Uuid::new_v4().to_string()],
            is_async: true,
        };

        let result = validator.validate(&request).await;
        assert!(result.is_ok());
    }

    #[tokio::test]
    async fn test_check_dependencies_empty() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        let result = validator.check_dependencies(&[], &storage).await;
        assert!(result.is_ok());
        assert!(result.unwrap());
    }

    #[tokio::test]
    async fn test_check_dependencies_all_succeeded() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        // Create succeeded tasks
        let dep1 = Uuid::new_v4();
        let dep2 = Uuid::new_v4();
        
        let task1 = TaskInfo {
            id: dep1,
            method: "method1".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 1000,
            },
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        let task2 = TaskInfo {
            id: dep2,
            method: "method2".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Succeeded {
                completed_at: Utc::now(),
                duration_ms: 1000,
            },
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        storage.create_task(&task1).await.unwrap();
        storage.create_task(&task2).await.unwrap();
        
        let result = validator.check_dependencies(&[dep1, dep2], &storage).await;
        assert!(result.is_ok());
        assert!(result.unwrap());
    }

    #[tokio::test]
    async fn test_check_dependencies_failed() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        let dep_id = Uuid::new_v4();
        let task = TaskInfo {
            id: dep_id,
            method: "method".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Failed {
                completed_at: Utc::now(),
                error: "Test error".to_string(),
                retries: 0,
            },
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        storage.create_task(&task).await.unwrap();
        
        let result = validator.check_dependencies(&[dep_id], &storage).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_check_dependencies_cancelled() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        let dep_id = Uuid::new_v4();
        let task = TaskInfo {
            id: dep_id,
            method: "method".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Cancelled {
                reason: "Test cancellation".to_string(),
                cancelled_at: Utc::now(),
            },
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        storage.create_task(&task).await.unwrap();
        
        let result = validator.check_dependencies(&[dep_id], &storage).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_check_dependencies_not_found() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        let result = validator.check_dependencies(&[Uuid::new_v4()], &storage).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_check_dependencies_not_completed() {
        let validator = TaskValidator::new();
        let storage = crate::storage::TaskStorage::new(100);
        
        let dep_id = Uuid::new_v4();
        let task = TaskInfo {
            id: dep_id,
            method: "method".to_string(),
            args: vec![],
            dependencies: vec![],
            priority: 50,
            metadata: TaskMetadata::default(),
            status: TaskStatus::Running {
                started_at: Utc::now(),
                worker_id: "worker1".to_string(),
            },
            created_at: Utc::now(),
            updated_at: Utc::now(),
        };
        
        storage.create_task(&task).await.unwrap();
        
        let result = validator.check_dependencies(&[dep_id], &storage).await;
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }
}
