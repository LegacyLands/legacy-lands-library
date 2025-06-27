use parking_lot::RwLock;
use std::collections::HashSet;
use std::sync::Arc;
use task_common::error::{TaskError, TaskResult};
use tracing::{debug, info, instrument, warn};
use uuid::Uuid;

/// Task cancellation token
#[derive(Debug, Clone)]
pub struct CancellationToken {
    cancelled: Arc<RwLock<bool>>,
    reason: Arc<RwLock<Option<String>>>,
}

impl CancellationToken {
    /// Create a new cancellation token
    pub fn new() -> Self {
        Self {
            cancelled: Arc::new(RwLock::new(false)),
            reason: Arc::new(RwLock::new(None)),
        }
    }

    /// Check if cancelled
    pub fn is_cancelled(&self) -> bool {
        *self.cancelled.read()
    }

    /// Cancel with reason
    pub fn cancel(&self, reason: String) {
        *self.cancelled.write() = true;
        *self.reason.write() = Some(reason);
    }

    /// Get cancellation reason
    pub fn reason(&self) -> Option<String> {
        self.reason.read().clone()
    }
}

impl Default for CancellationToken {
    fn default() -> Self {
        Self::new()
    }
}

/// Task cancellation manager
pub struct CancellationManager {
    /// Active cancellation tokens
    tokens: Arc<RwLock<HashMap<Uuid, CancellationToken>>>,

    /// Paused tasks
    paused_tasks: Arc<RwLock<HashSet<Uuid>>>,
}

impl CancellationManager {
    /// Create a new cancellation manager
    pub fn new() -> Self {
        Self {
            tokens: Arc::new(RwLock::new(HashMap::new())),
            paused_tasks: Arc::new(RwLock::new(HashSet::new())),
        }
    }

    /// Create a cancellation token for a task
    #[instrument(skip(self))]
    pub fn create_token(&self, task_id: Uuid) -> CancellationToken {
        let token = CancellationToken::new();
        self.tokens.write().insert(task_id, token.clone());
        debug!("Created cancellation token for task {}", task_id);
        token
    }

    /// Get cancellation token for a task
    pub fn get_token(&self, task_id: &Uuid) -> Option<CancellationToken> {
        self.tokens.read().get(task_id).cloned()
    }

    /// Cancel a task
    #[instrument(skip(self))]
    pub fn cancel_task(&self, task_id: &Uuid, reason: String) -> TaskResult<bool> {
        if let Some(token) = self.tokens.read().get(task_id) {
            if token.is_cancelled() {
                warn!("Task {} is already cancelled", task_id);
                return Ok(false);
            }

            token.cancel(reason.clone());
            info!("Cancelled task {} with reason: {}", task_id, reason);
            Ok(true)
        } else {
            Err(TaskError::TaskNotFound(format!(
                "Task {} not found",
                task_id
            )))
        }
    }

    /// Check if a task is cancelled
    pub fn is_cancelled(&self, task_id: &Uuid) -> bool {
        self.tokens
            .read()
            .get(task_id)
            .map(|token| token.is_cancelled())
            .unwrap_or(false)
    }

    /// Pause a task
    #[instrument(skip(self))]
    pub fn pause_task(&self, task_id: &Uuid) -> TaskResult<bool> {
        let mut paused = self.paused_tasks.write();

        if paused.contains(task_id) {
            warn!("Task {} is already paused", task_id);
            Ok(false)
        } else {
            paused.insert(*task_id);
            info!("Paused task {}", task_id);
            Ok(true)
        }
    }

    /// Resume a task
    #[instrument(skip(self))]
    pub fn resume_task(&self, task_id: &Uuid) -> TaskResult<bool> {
        let mut paused = self.paused_tasks.write();

        if paused.remove(task_id) {
            info!("Resumed task {}", task_id);
            Ok(true)
        } else {
            warn!("Task {} was not paused", task_id);
            Ok(false)
        }
    }

    /// Check if a task is paused
    pub fn is_paused(&self, task_id: &Uuid) -> bool {
        self.paused_tasks.read().contains(task_id)
    }

    /// Remove token (when task completes)
    #[instrument(skip(self))]
    pub fn remove_token(&self, task_id: &Uuid) {
        self.tokens.write().remove(task_id);
        self.paused_tasks.write().remove(task_id);
        debug!("Removed cancellation token for task {}", task_id);
    }

    /// Get all cancelled tasks
    pub fn get_cancelled_tasks(&self) -> Vec<(Uuid, String)> {
        self.tokens
            .read()
            .iter()
            .filter_map(|(id, token)| {
                if token.is_cancelled() {
                    token.reason().map(|reason| (*id, reason))
                } else {
                    None
                }
            })
            .collect()
    }

    /// Get all paused tasks
    pub fn get_paused_tasks(&self) -> Vec<Uuid> {
        self.paused_tasks.read().iter().copied().collect()
    }
}

impl Default for CancellationManager {
    fn default() -> Self {
        Self::new()
    }
}

use std::collections::HashMap;

#[cfg(test)]
mod tests {
    use super::*;
    use tokio::time::{sleep, Duration};

    #[test]
    fn test_cancellation_token_new() {
        let token = CancellationToken::new();
        assert!(!token.is_cancelled());
        assert!(token.reason().is_none());
    }

    #[test]
    fn test_cancellation_token_cancel() {
        let token = CancellationToken::new();
        let reason = "Test cancellation".to_string();
        
        token.cancel(reason.clone());
        
        assert!(token.is_cancelled());
        assert_eq!(token.reason(), Some(reason));
    }

    #[test]
    fn test_cancellation_token_clone() {
        let token1 = CancellationToken::new();
        let token2 = token1.clone();
        
        // Cancel through first token
        token1.cancel("Cancelled via token1".to_string());
        
        // Check both tokens see the cancellation
        assert!(token1.is_cancelled());
        assert!(token2.is_cancelled());
        assert_eq!(token1.reason(), token2.reason());
    }

    #[tokio::test]
    async fn test_cancellation_manager_new() {
        let manager = CancellationManager::new();
        
        // Check empty state
        assert!(manager.get_cancelled_tasks().is_empty());
        assert!(manager.get_paused_tasks().is_empty());
    }

    #[tokio::test]
    async fn test_create_token() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        let token = manager.create_token(task_id);
        assert!(!token.is_cancelled());
        
        // Verify token is stored
        let retrieved = manager.get_token(&task_id);
        assert!(retrieved.is_some());
        assert!(!retrieved.unwrap().is_cancelled());
    }

    #[tokio::test]
    async fn test_cancel_task_success() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        // Create token first
        manager.create_token(task_id);
        
        // Cancel task
        let result = manager.cancel_task(&task_id, "Test reason".to_string());
        assert!(result.is_ok());
        assert!(result.unwrap());
        
        // Verify cancellation
        assert!(manager.is_cancelled(&task_id));
        
        let cancelled = manager.get_cancelled_tasks();
        assert_eq!(cancelled.len(), 1);
        assert_eq!(cancelled[0].0, task_id);
        assert_eq!(cancelled[0].1, "Test reason");
    }

    #[tokio::test]
    async fn test_cancel_task_already_cancelled() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        manager.create_token(task_id);
        
        // First cancellation
        manager.cancel_task(&task_id, "First reason".to_string()).unwrap();
        
        // Second cancellation should return false
        let result = manager.cancel_task(&task_id, "Second reason".to_string());
        assert!(result.is_ok());
        assert!(!result.unwrap());
        
        // Original reason should be preserved
        let token = manager.get_token(&task_id).unwrap();
        assert_eq!(token.reason(), Some("First reason".to_string()));
    }

    #[tokio::test]
    async fn test_cancel_task_not_found() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        let result = manager.cancel_task(&task_id, "Test reason".to_string());
        assert!(result.is_err());
        match result.unwrap_err() {
            TaskError::TaskNotFound(_) => {}
            _ => panic!("Expected TaskNotFound error"),
        }
    }

    #[tokio::test]
    async fn test_pause_task_success() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        let result = manager.pause_task(&task_id);
        assert!(result.is_ok());
        assert!(result.unwrap());
        
        assert!(manager.is_paused(&task_id));
        
        let paused = manager.get_paused_tasks();
        assert_eq!(paused.len(), 1);
        assert_eq!(paused[0], task_id);
    }

    #[tokio::test]
    async fn test_pause_task_already_paused() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        manager.pause_task(&task_id).unwrap();
        
        // Second pause should return false
        let result = manager.pause_task(&task_id);
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_resume_task_success() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        // Pause first
        manager.pause_task(&task_id).unwrap();
        assert!(manager.is_paused(&task_id));
        
        // Resume
        let result = manager.resume_task(&task_id);
        assert!(result.is_ok());
        assert!(result.unwrap());
        
        assert!(!manager.is_paused(&task_id));
        assert!(manager.get_paused_tasks().is_empty());
    }

    #[tokio::test]
    async fn test_resume_task_not_paused() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        let result = manager.resume_task(&task_id);
        assert!(result.is_ok());
        assert!(!result.unwrap());
    }

    #[tokio::test]
    async fn test_remove_token() {
        let manager = CancellationManager::new();
        let task_id = Uuid::new_v4();
        
        // Create and pause
        manager.create_token(task_id);
        manager.pause_task(&task_id).unwrap();
        
        // Remove
        manager.remove_token(&task_id);
        
        // Verify removal
        assert!(manager.get_token(&task_id).is_none());
        assert!(!manager.is_paused(&task_id));
        assert!(!manager.is_cancelled(&task_id));
    }

    #[tokio::test]
    async fn test_get_cancelled_tasks() {
        let manager = CancellationManager::new();
        
        let task1 = Uuid::new_v4();
        let task2 = Uuid::new_v4();
        let task3 = Uuid::new_v4();
        
        manager.create_token(task1);
        manager.create_token(task2);
        manager.create_token(task3);
        
        // Cancel some tasks
        manager.cancel_task(&task1, "Reason 1".to_string()).unwrap();
        manager.cancel_task(&task3, "Reason 3".to_string()).unwrap();
        
        let cancelled = manager.get_cancelled_tasks();
        assert_eq!(cancelled.len(), 2);
        
        // Check both cancelled tasks are present
        assert!(cancelled.iter().any(|(id, reason)| *id == task1 && reason == "Reason 1"));
        assert!(cancelled.iter().any(|(id, reason)| *id == task3 && reason == "Reason 3"));
    }

    #[tokio::test]
    async fn test_concurrent_operations() {
        let manager = Arc::new(CancellationManager::new());
        let task_id = Uuid::new_v4();
        
        // Create token
        manager.create_token(task_id);
        
        let mut handles = vec![];
        
        // Spawn multiple tasks trying to cancel
        for i in 0..5 {
            let manager_clone = manager.clone();
            let handle = tokio::spawn(async move {
                manager_clone.cancel_task(&task_id, format!("Reason {}", i))
            });
            handles.push(handle);
        }
        
        let results: Vec<_> = futures::future::join_all(handles)
            .await
            .into_iter()
            .map(|r| r.unwrap())
            .collect();
        
        // Only one should succeed
        let successes = results.iter().filter(|r| r.is_ok() && r.as_ref().unwrap() == &true).count();
        assert_eq!(successes, 1);
        
        // Task should be cancelled
        assert!(manager.is_cancelled(&task_id));
    }

    #[tokio::test]
    async fn test_pause_resume_concurrent() {
        let manager = Arc::new(CancellationManager::new());
        let task_id = Uuid::new_v4();
        
        let manager1 = manager.clone();
        let manager2 = manager.clone();
        
        // Concurrent pause and resume
        let pause_handle = tokio::spawn(async move {
            for _ in 0..10 {
                manager1.pause_task(&task_id).ok();
                sleep(Duration::from_millis(1)).await;
            }
        });
        
        let resume_handle = tokio::spawn(async move {
            for _ in 0..10 {
                manager2.resume_task(&task_id).ok();
                sleep(Duration::from_millis(1)).await;
            }
        });
        
        pause_handle.await.unwrap();
        resume_handle.await.unwrap();
        
        // Final state could be either paused or resumed, but should be consistent
        let is_paused = manager.is_paused(&task_id);
        let paused_list = manager.get_paused_tasks();
        
        if is_paused {
            assert!(paused_list.contains(&task_id));
        } else {
            assert!(!paused_list.contains(&task_id));
        }
    }

    #[tokio::test]
    async fn test_cancellation_token_thread_safety() {
        let token = Arc::new(CancellationToken::new());
        let mut handles = vec![];
        
        // Multiple threads checking cancellation
        for _ in 0..10 {
            let token_clone = token.clone();
            let handle = tokio::spawn(async move {
                for _ in 0..100 {
                    let _ = token_clone.is_cancelled();
                }
            });
            handles.push(handle);
        }
        
        // One thread cancelling
        let token_clone = token.clone();
        let cancel_handle = tokio::spawn(async move {
            sleep(Duration::from_millis(10)).await;
            token_clone.cancel("Cancelled".to_string());
        });
        
        futures::future::join_all(handles).await;
        cancel_handle.await.unwrap();
        
        assert!(token.is_cancelled());
        assert_eq!(token.reason(), Some("Cancelled".to_string()));
    }
}
