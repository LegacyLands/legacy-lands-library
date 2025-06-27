/// Test utilities for task manager
use std::sync::{Arc, Mutex};
use std::collections::VecDeque;
use task_common::{
    error::TaskResult,
    events::TaskEvent,
    queue::QueuedTask,
};

/// In-memory queue implementation for testing
pub struct MemoryQueue {
    tasks: Arc<Mutex<VecDeque<QueuedTask>>>,
}

impl MemoryQueue {
    /// Create a new memory queue
    pub fn new() -> Self {
        Self {
            tasks: Arc::new(Mutex::new(VecDeque::new())),
        }
    }

    /// Queue a task
    pub async fn queue_task(&self, task: QueuedTask) -> TaskResult<()> {
        self.tasks.lock().unwrap().push_back(task);
        Ok(())
    }

    /// Dequeue tasks
    pub async fn dequeue_task(&self, _queue: &str, count: usize) -> TaskResult<Vec<QueuedTask>> {
        let mut tasks = self.tasks.lock().unwrap();
        let mut result = Vec::new();
        
        for _ in 0..count {
            if let Some(task) = tasks.pop_front() {
                result.push(task);
            } else {
                break;
            }
        }
        
        Ok(result)
    }

    /// Publish event (no-op for memory queue)
    pub async fn publish_event(&self, _event: TaskEvent, _source: String) -> TaskResult<()> {
        Ok(())
    }
}

impl Default for MemoryQueue {
    fn default() -> Self {
        Self::new()
    }
}