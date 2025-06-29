use std::collections::{HashMap, HashSet};
use std::sync::Arc;
use task_common::{
    error::{TaskError, TaskResult},
    models::TaskStatus as ModelTaskStatus,
    queue::{QueueManager, QueuedTask},
    Uuid,
};
use tokio::sync::RwLock;
use tracing::{debug, info, warn};
use crate::metrics::Metrics;

/// Manages task dependencies and triggers dependent tasks when dependencies complete
pub struct DependencyManager {
    /// Map from task ID to set of tasks that depend on it
    dependents: Arc<RwLock<HashMap<Uuid, HashSet<Uuid>>>>,

    /// Storage reference
    storage: Arc<dyn crate::storage::StorageBackend>,

    /// Queue reference
    queue: Arc<QueueManager>,
    
    /// Metrics
    metrics: Arc<Metrics>,
}

impl DependencyManager {
    /// Create a new dependency manager
    pub fn new(storage: Arc<dyn crate::storage::StorageBackend>, queue: Arc<QueueManager>, metrics: Arc<Metrics>) -> Self {
        Self {
            dependents: Arc::new(RwLock::new(HashMap::new())),
            storage,
            queue,
            metrics,
        }
    }

    /// Register a task with dependencies
    pub async fn register_task_dependencies(
        &self,
        task_id: Uuid,
        dependencies: &[Uuid],
    ) -> TaskResult<()> {
        if dependencies.is_empty() {
            return Ok(());
        }

        let mut deps = self.dependents.write().await;

        for dep_id in dependencies {
            deps.entry(*dep_id)
                .or_insert_with(HashSet::new)
                .insert(task_id);

            debug!("Registered task {} as dependent on {}", task_id, dep_id);
        }

        Ok(())
    }

    /// Check and queue tasks whose dependencies are now satisfied
    pub async fn check_dependent_tasks(&self, completed_task_id: Uuid) -> TaskResult<Vec<Uuid>> {
        let mut queued_tasks = Vec::new();

        // Get list of dependent tasks
        let dependent_task_ids = {
            let deps = self.dependents.read().await;
            deps.get(&completed_task_id).cloned().unwrap_or_default()
        };

        if dependent_task_ids.is_empty() {
            debug!("No tasks depend on {}", completed_task_id);
            return Ok(queued_tasks);
        }

        info!(
            "Task {} completed, checking {} dependent tasks",
            completed_task_id,
            dependent_task_ids.len()
        );

        for dependent_id in dependent_task_ids {
            match self.check_and_queue_task(dependent_id).await {
                Ok(true) => {
                    queued_tasks.push(dependent_id);
                    info!(
                        "Queued dependent task {} after dependency {} completed",
                        dependent_id, completed_task_id
                    );
                }
                Ok(false) => {
                    debug!("Task {} still has unsatisfied dependencies", dependent_id);
                }
                Err(e) => {
                    warn!("Failed to check dependent task {}: {}", dependent_id, e);
                }
            }
        }

        // Clean up completed task from dependents map
        let mut deps = self.dependents.write().await;
        deps.remove(&completed_task_id);

        Ok(queued_tasks)
    }

    /// Check if a task's dependencies are satisfied and queue it if they are
    async fn check_and_queue_task(&self, task_id: Uuid) -> TaskResult<bool> {
        // Get task info
        let task_info = self
            .storage
            .get_task(task_id)
            .await?
            .ok_or_else(|| TaskError::TaskNotFound(task_id.to_string()))?;

        // Check if task is in WaitingDependencies state
        if task_info.status != ModelTaskStatus::WaitingDependencies {
            return Ok(false);
        }

        // Check all dependencies
        let dependency_timer = self.metrics.dependency_resolution_duration
            .with_label_values(&[&task_info.dependencies.len().to_string()])
            .start_timer();
            
        let mut all_satisfied = true;
        for dep_id in &task_info.dependencies {
            match self.storage.get_task(*dep_id).await.ok().flatten() {
                Some(dep_task) => {
                    match &dep_task.status {
                        ModelTaskStatus::Succeeded { .. } => {
                            // Dependency satisfied
                            continue;
                        }
                        ModelTaskStatus::Failed { .. } | ModelTaskStatus::Cancelled { .. } => {
                            // Dependency failed, mark this task as failed
                            self.storage
                                .update_task_status(
                                    task_id,
                                    ModelTaskStatus::Failed {
                                        completed_at: chrono::Utc::now(),
                                        error: format!("Dependency {} failed", dep_id),
                                        retries: 0,
                                    },
                                )
                                .await?;
                            all_satisfied = false;
                            break;
                        }
                        _ => {
                            // Dependency not yet complete
                            all_satisfied = false;
                            break;
                        }
                    }
                }
                None => {
                    // Dependency not found, mark task as failed
                    self.storage
                        .update_task_status(
                            task_id,
                            ModelTaskStatus::Failed {
                                completed_at: chrono::Utc::now(),
                                error: format!("Dependency {} not found", dep_id),
                                retries: 0,
                            },
                        )
                        .await?;
                    all_satisfied = false;
                    break;
                }
            }
        }
        
        dependency_timer.observe_duration();
        
        // Record dependency check result
        if all_satisfied {
            self.metrics.dependency_checks
                .with_label_values(&["satisfied"])
                .inc();
        } else {
            self.metrics.dependency_checks
                .with_label_values(&["failed"])
                .inc();
            return Ok(false);
        }

        // All dependencies satisfied, queue the task
        let queued_task = QueuedTask {
            task_id: task_info.id,
            method: task_info.method.clone(),
            args: task_info.args.clone(),
            priority: task_info.priority,
            retry_count: 0,
            max_retries: 3,
            timeout_seconds: 3600,
            metadata: Default::default(),
            dependencies: task_info.dependencies.clone(),
        };

        self.queue.queue_task(queued_task).await?;

        // Update task status to queued
        self.storage
            .update_task_status(task_id, ModelTaskStatus::Queued)
            .await?;

        Ok(true)
    }

    /// Remove a task from dependency tracking (e.g., when cancelled)
    pub async fn remove_task(&self, task_id: Uuid) -> TaskResult<()> {
        let mut deps = self.dependents.write().await;

        // Remove as dependent
        for dependents in deps.values_mut() {
            dependents.remove(&task_id);
        }

        // Remove as dependency
        deps.remove(&task_id);

        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use task_common::models::{TaskInfo, TaskMetadata};
    use chrono::Utc;
    
    macro_rules! skip_without_nats {
        ($components:expr) => {
            match $components {
                Some(c) => c,
                None => return,
            }
        };
    }

    async fn create_test_storage_queue_and_metrics() -> Option<(Arc<dyn crate::storage::StorageBackend>, Arc<QueueManager>, Arc<Metrics>)> {
        let storage = Arc::new(crate::storage::MemoryStorage::new(100));
        // Skip NATS-dependent tests when not available
        if std::env::var("TEST_NATS_URL").is_err() && std::env::var("CI").is_err() {
            eprintln!("Skipping test - NATS not available. Set TEST_NATS_URL to run.");
            return None;
        }
        
        let nats_url = std::env::var("TEST_NATS_URL").unwrap_or_else(|_| "nats://localhost:4222".to_string());
        match QueueManager::new(&nats_url).await {
            Ok(queue) => {
                let registry = prometheus::Registry::new();
                let metrics = Arc::new(Metrics::new(&registry).expect("Failed to create metrics"));
                Some((storage, Arc::new(queue), metrics))
            }
            Err(_) => {
                eprintln!("Failed to connect to NATS at {}. Skipping test.", nats_url);
                None
            }
        }
    }

    async fn create_test_task(id: Uuid, status: ModelTaskStatus, deps: Vec<Uuid>) -> TaskInfo {
        TaskInfo {
            id,
            method: format!("test_method_{}", id),
            args: vec![],
            dependencies: deps,
            priority: 50,
            metadata: TaskMetadata::default(),
            status,
            created_at: Utc::now(),
            updated_at: Utc::now(),
        }
    }

    #[tokio::test]
    async fn test_new() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        // Check that dependents map is empty
        let deps = manager.dependents.read().await;
        assert!(deps.is_empty());
    }

    #[tokio::test]
    async fn test_register_task_dependencies_empty() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let task_id = Uuid::new_v4();
        let result = manager.register_task_dependencies(task_id, &[]).await;
        
        assert!(result.is_ok());
        
        // Verify no dependencies were registered
        let deps = manager.dependents.read().await;
        assert!(deps.is_empty());
    }

    #[tokio::test]
    async fn test_register_task_dependencies_single() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let task_id = Uuid::new_v4();
        let dep_id = Uuid::new_v4();
        
        let result = manager.register_task_dependencies(task_id, &[dep_id]).await;
        assert!(result.is_ok());
        
        // Verify dependency was registered
        let deps = manager.dependents.read().await;
        assert!(deps.contains_key(&dep_id));
        assert!(deps.get(&dep_id).unwrap().contains(&task_id));
    }

    #[tokio::test]
    async fn test_register_task_dependencies_multiple() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let task_id = Uuid::new_v4();
        let dep1 = Uuid::new_v4();
        let dep2 = Uuid::new_v4();
        let dep3 = Uuid::new_v4();
        
        let result = manager.register_task_dependencies(task_id, &[dep1, dep2, dep3]).await;
        assert!(result.is_ok());
        
        // Verify all dependencies were registered
        let deps = manager.dependents.read().await;
        assert!(deps.get(&dep1).unwrap().contains(&task_id));
        assert!(deps.get(&dep2).unwrap().contains(&task_id));
        assert!(deps.get(&dep3).unwrap().contains(&task_id));
    }

    #[tokio::test]
    async fn test_register_multiple_tasks_same_dependency() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let task1 = Uuid::new_v4();
        let task2 = Uuid::new_v4();
        let task3 = Uuid::new_v4();
        let dep_id = Uuid::new_v4();
        
        // Register multiple tasks depending on the same dependency
        manager.register_task_dependencies(task1, &[dep_id]).await.unwrap();
        manager.register_task_dependencies(task2, &[dep_id]).await.unwrap();
        manager.register_task_dependencies(task3, &[dep_id]).await.unwrap();
        
        // Verify all tasks are registered as dependents
        let deps = manager.dependents.read().await;
        let dependents = deps.get(&dep_id).unwrap();
        assert_eq!(dependents.len(), 3);
        assert!(dependents.contains(&task1));
        assert!(dependents.contains(&task2));
        assert!(dependents.contains(&task3));
    }

    #[tokio::test]
    async fn test_check_dependent_tasks_no_dependents() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let completed_task = Uuid::new_v4();
        let result = manager.check_dependent_tasks(completed_task).await;
        
        assert!(result.is_ok());
        assert!(result.unwrap().is_empty());
    }

    #[tokio::test]
    async fn test_check_dependent_tasks_success() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue.clone(), metrics);
        
        // Create dependency task
        let dep_id = Uuid::new_v4();
        let dep_task = create_test_task(dep_id, ModelTaskStatus::Succeeded {
            completed_at: Utc::now(),
            duration_ms: 1000,
        }, vec![]).await;
        storage.create_task(&dep_task).await.unwrap();
        
        // Create dependent task waiting for dependency
        let dependent_id = Uuid::new_v4();
        let dependent_task = create_test_task(
            dependent_id, 
            ModelTaskStatus::WaitingDependencies,
            vec![dep_id]
        ).await;
        storage.create_task(&dependent_task).await.unwrap();
        
        // Register dependency
        manager.register_task_dependencies(dependent_id, &[dep_id]).await.unwrap();
        
        // Check dependent tasks
        let result = manager.check_dependent_tasks(dep_id).await;
        assert!(result.is_ok());
        
        let queued_tasks = result.unwrap();
        assert_eq!(queued_tasks.len(), 1);
        assert_eq!(queued_tasks[0], dependent_id);
        
        // Verify task was updated to Queued status
        let updated_task = storage.get_task(dependent_id).await.unwrap().unwrap();
        assert_eq!(updated_task.status, ModelTaskStatus::Queued);
    }

    #[tokio::test]
    async fn test_check_dependent_tasks_failed_dependency() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue, metrics);
        
        // Create failed dependency
        let dep_id = Uuid::new_v4();
        let dep_task = create_test_task(dep_id, ModelTaskStatus::Failed {
            completed_at: Utc::now(),
            error: "Test failure".to_string(),
            retries: 0,
        }, vec![]).await;
        storage.create_task(&dep_task).await.unwrap();
        
        // Create dependent task
        let dependent_id = Uuid::new_v4();
        let dependent_task = create_test_task(
            dependent_id,
            ModelTaskStatus::WaitingDependencies,
            vec![dep_id]
        ).await;
        storage.create_task(&dependent_task).await.unwrap();
        
        // Register dependency
        manager.register_task_dependencies(dependent_id, &[dep_id]).await.unwrap();
        
        // Check and queue - should fail the dependent task
        let queued = manager.check_and_queue_task(dependent_id).await.unwrap();
        assert!(!queued);
        
        // Verify task was marked as failed
        let updated_task = storage.get_task(dependent_id).await.unwrap().unwrap();
        match updated_task.status {
            ModelTaskStatus::Failed { error, .. } => {
                assert!(error.contains("Dependency") && error.contains("failed"));
            }
            _ => panic!("Expected Failed status"),
        }
    }

    #[tokio::test]
    async fn test_check_and_queue_task_not_waiting() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue, metrics);
        
        // Create task that's already running
        let task_id = Uuid::new_v4();
        let task = create_test_task(task_id, ModelTaskStatus::Running {
            started_at: Utc::now(),
            worker_id: "worker1".to_string(),
        }, vec![]).await;
        storage.create_task(&task).await.unwrap();
        
        let queued = manager.check_and_queue_task(task_id).await.unwrap();
        assert!(!queued);
    }

    #[tokio::test]
    async fn test_check_and_queue_task_not_found() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let result = manager.check_and_queue_task(Uuid::new_v4()).await;
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::TaskNotFound(_) => {}
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[tokio::test]
    async fn test_check_and_queue_complex_dependencies() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue.clone(), metrics);
        
        // Create a complex dependency graph:
        // dep1 -> dep2 -> task
        // dep3 ----^
        let dep1 = Uuid::new_v4();
        let dep2 = Uuid::new_v4();
        let dep3 = Uuid::new_v4();
        let task_id = Uuid::new_v4();
        
        // Create completed dependencies
        let dep1_task = create_test_task(dep1, ModelTaskStatus::Succeeded {
            completed_at: Utc::now(),
            duration_ms: 1000,
        }, vec![]).await;
        let dep2_task = create_test_task(dep2, ModelTaskStatus::Succeeded {
            completed_at: Utc::now(),
            duration_ms: 1000,
        }, vec![]).await;
        let dep3_task = create_test_task(dep3, ModelTaskStatus::Succeeded {
            completed_at: Utc::now(),
            duration_ms: 1000,
        }, vec![]).await;
        
        storage.create_task(&dep1_task).await.unwrap();
        storage.create_task(&dep2_task).await.unwrap();
        storage.create_task(&dep3_task).await.unwrap();
        
        // Create task with multiple dependencies
        let task = create_test_task(
            task_id,
            ModelTaskStatus::WaitingDependencies,
            vec![dep1, dep2, dep3]
        ).await;
        storage.create_task(&task).await.unwrap();
        
        // Check and queue - all dependencies are satisfied
        let queued = manager.check_and_queue_task(task_id).await.unwrap();
        assert!(queued);
        
        // Verify task was queued
        let updated_task = storage.get_task(task_id).await.unwrap().unwrap();
        assert_eq!(updated_task.status, ModelTaskStatus::Queued);
    }

    #[tokio::test]
    async fn test_check_and_queue_partial_dependencies() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue, metrics);
        
        let dep1 = Uuid::new_v4();
        let dep2 = Uuid::new_v4();
        let task_id = Uuid::new_v4();
        
        // Create one completed and one pending dependency
        let dep1_task = create_test_task(dep1, ModelTaskStatus::Succeeded {
            completed_at: Utc::now(),
            duration_ms: 1000,
        }, vec![]).await;
        let dep2_task = create_test_task(dep2, ModelTaskStatus::Queued, vec![]).await;
        
        storage.create_task(&dep1_task).await.unwrap();
        storage.create_task(&dep2_task).await.unwrap();
        
        // Create task depending on both
        let task = create_test_task(
            task_id,
            ModelTaskStatus::WaitingDependencies,
            vec![dep1, dep2]
        ).await;
        storage.create_task(&task).await.unwrap();
        
        // Check and queue - not all dependencies are satisfied
        let queued = manager.check_and_queue_task(task_id).await.unwrap();
        assert!(!queued);
        
        // Task should still be waiting
        let updated_task = storage.get_task(task_id).await.unwrap().unwrap();
        assert_eq!(updated_task.status, ModelTaskStatus::WaitingDependencies);
    }

    #[tokio::test]
    async fn test_remove_task() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage, queue, metrics);
        
        let task1 = Uuid::new_v4();
        let task2 = Uuid::new_v4();
        let dep1 = Uuid::new_v4();
        let dep2 = Uuid::new_v4();
        
        // Register dependencies
        manager.register_task_dependencies(task1, &[dep1, dep2]).await.unwrap();
        manager.register_task_dependencies(task2, &[dep1]).await.unwrap();
        
        // Remove task1
        manager.remove_task(task1).await.unwrap();
        
        // Verify task1 is removed but task2 remains
        let deps = manager.dependents.read().await;
        assert!(!deps.get(&dep1).unwrap().contains(&task1));
        assert!(deps.get(&dep1).unwrap().contains(&task2));
        assert!(!deps.contains_key(&task1));
    }

    #[tokio::test]
    async fn test_dependency_not_found() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = DependencyManager::new(storage.clone(), queue, metrics);
        
        let missing_dep = Uuid::new_v4();
        let task_id = Uuid::new_v4();
        
        // Create task depending on non-existent dependency
        let task = create_test_task(
            task_id,
            ModelTaskStatus::WaitingDependencies,
            vec![missing_dep]
        ).await;
        storage.create_task(&task).await.unwrap();
        
        // Check and queue - should fail due to missing dependency
        let queued = manager.check_and_queue_task(task_id).await.unwrap();
        assert!(!queued);
        
        // Verify task was marked as failed
        let updated_task = storage.get_task(task_id).await.unwrap().unwrap();
        match updated_task.status {
            ModelTaskStatus::Failed { error, .. } => {
                assert!(error.contains("Dependency") && error.contains("not found"));
            }
            _ => panic!("Expected Failed status"),
        }
    }

    #[tokio::test]
    async fn test_concurrent_dependency_registration() {
        let (storage, queue, metrics) = skip_without_nats!(create_test_storage_queue_and_metrics().await);
        let manager = Arc::new(DependencyManager::new(storage, queue, metrics));
        
        let dep_id = Uuid::new_v4();
        let mut handles = vec![];
        
        // Register multiple tasks concurrently
        for _ in 0..10 {
            let manager_clone = manager.clone();
            let handle = tokio::spawn(async move {
                let task_id = Uuid::new_v4();
                manager_clone.register_task_dependencies(task_id, &[dep_id]).await.unwrap();
                task_id
            });
            handles.push(handle);
        }
        
        let task_ids: Vec<Uuid> = futures::future::join_all(handles)
            .await
            .into_iter()
            .map(|r| r.unwrap())
            .collect();
        
        // Verify all tasks were registered
        let deps = manager.dependents.read().await;
        let dependents = deps.get(&dep_id).unwrap();
        assert_eq!(dependents.len(), 10);
        for task_id in task_ids {
            assert!(dependents.contains(&task_id));
        }
    }
}
