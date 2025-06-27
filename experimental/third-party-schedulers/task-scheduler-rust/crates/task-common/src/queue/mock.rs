use crate::{
    error::{TaskError, TaskResult},
    events::{EventEnvelope, TaskEvent},
};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::{mpsc, RwLock};
use uuid::Uuid;

/// Mock implementation of QueueManager for testing
pub struct MockQueueManager {
    /// Event channels for testing
    event_channels: Arc<RwLock<HashMap<String, mpsc::Sender<EventEnvelope>>>>,
    /// Task queue channel
    task_queue: Arc<RwLock<Option<mpsc::Sender<QueuedTask>>>>,
    /// Result channels
    result_channels: Arc<RwLock<HashMap<String, mpsc::Sender<TaskResultMessage>>>>,
}

impl MockQueueManager {
    /// Create a new mock queue manager
    pub fn new() -> Self {
        Self {
            event_channels: Arc::new(RwLock::new(HashMap::new())),
            task_queue: Arc::new(RwLock::new(None)),
            result_channels: Arc::new(RwLock::new(HashMap::new())),
        }
    }

    /// Create a mock queue manager that returns an error
    pub fn new_error() -> Self {
        Self::new()
    }

    /// Initialize (no-op for mock)
    pub async fn initialize(&self) -> TaskResult<()> {
        Ok(())
    }

    /// Subscribe to events
    pub async fn subscribe_events(&self, subject: &str) -> TaskResult<MockEventSubscriber> {
        let (tx, rx) = mpsc::channel(100);
        
        // Store the sender for this subject
        let mut channels = self.event_channels.write().await;
        channels.insert(subject.to_string(), tx);
        
        Ok(MockEventSubscriber { receiver: rx })
    }

    /// Publish an event
    pub async fn publish_event(&self, event: TaskEvent, source: String) -> TaskResult<()> {
        let envelope = EventEnvelope::new(event, source);
        
        // Determine the subject based on event type
        let subject = match &envelope.event {
            TaskEvent::Created { .. } => "tasks.events.created",
            TaskEvent::Queued { .. } => "tasks.events.queued",
            TaskEvent::Started { .. } => "tasks.events.started",
            TaskEvent::Completed { .. } => "tasks.events.completed",
            TaskEvent::Failed { .. } => "tasks.events.failed",
            TaskEvent::WorkerHeartbeat { .. } => "workers.events.heartbeat",
            _ => "tasks.events",
        };
        
        // Send to all matching subscribers
        let channels = self.event_channels.read().await;
        for (sub_pattern, tx) in channels.iter() {
            if subject.starts_with(sub_pattern.trim_end_matches(".*")) {
                let _ = tx.send(envelope.clone()).await;
            }
        }
        
        Ok(())
    }

    /// Queue a task
    pub async fn queue_task(&self, task: QueuedTask) -> TaskResult<()> {
        if let Some(tx) = &*self.task_queue.read().await {
            tx.send(task).await
                .map_err(|_| TaskError::QueueError("Failed to send task".to_string()))?;
        }
        Ok(())
    }

    /// Create a task consumer
    pub async fn create_task_consumer(&self, _consumer_name: &str, _max_pending: i64) -> TaskResult<MockTaskConsumer> {
        let (tx, rx) = mpsc::channel(100);
        *self.task_queue.write().await = Some(tx);
        Ok(MockTaskConsumer { receiver: rx })
    }

    /// Send task result
    pub async fn send_task_result(&self, result: TaskResultMessage) -> TaskResult<()> {
        let channels = self.result_channels.read().await;
        if let Some(tx) = channels.get(&result.task_id.to_string()) {
            tx.send(result).await
                .map_err(|_| TaskError::QueueError("Failed to send result".to_string()))?;
        }
        Ok(())
    }

    /// Subscribe to task results
    pub async fn subscribe_task_results(&self, task_id: &Uuid) -> TaskResult<MockResultSubscriber> {
        let (tx, rx) = mpsc::channel(10);
        let mut channels = self.result_channels.write().await;
        channels.insert(task_id.to_string(), tx);
        Ok(MockResultSubscriber { receiver: rx })
    }
}

/// Mock event subscriber
pub struct MockEventSubscriber {
    receiver: mpsc::Receiver<EventEnvelope>,
}

impl MockEventSubscriber {
    pub async fn next(&mut self) -> TaskResult<Option<EventEnvelope>> {
        Ok(self.receiver.recv().await)
    }
}

/// Mock task consumer
pub struct MockTaskConsumer {
    receiver: mpsc::Receiver<QueuedTask>,
}

impl MockTaskConsumer {
    pub async fn next(&mut self) -> TaskResult<Option<QueuedTask>> {
        Ok(self.receiver.recv().await)
    }
}

/// Mock result subscriber
pub struct MockResultSubscriber {
    receiver: mpsc::Receiver<TaskResultMessage>,
}

impl MockResultSubscriber {
    pub async fn next(&mut self) -> TaskResult<Option<TaskResultMessage>> {
        Ok(self.receiver.recv().await)
    }
}

/// Queued task message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueuedTask {
    pub id: Uuid,
    pub method: String,
    pub args: Vec<serde_json::Value>,
    pub priority: i32,
}

/// Task result message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResultMessage {
    pub task_id: Uuid,
    pub status: String,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
}