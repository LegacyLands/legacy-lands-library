use crate::{
    error::{TaskError, TaskResult},
    events::{subjects, EventEnvelope, TaskEvent},
};
use async_nats::jetstream::{
    consumer,
    stream::{Config, Stream},
    Context,
};
use async_nats::{jetstream, Client, Subscriber};
use futures::StreamExt;
use serde::{Deserialize, Serialize};
use std::time::Duration;
use tracing::{debug, error, info};
use uuid::Uuid;

#[cfg(any(test, feature = "test-utils"))]
pub mod mock;

/// NATS JetStream queue abstraction
pub struct QueueManager {
    client: Client,
    jetstream: Context,
}

impl QueueManager {
    /// Create a new queue manager
    pub async fn new(nats_url: &str) -> TaskResult<Self> {
        info!("Connecting to NATS at {}", nats_url);

        let client = async_nats::connect(nats_url)
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to connect to NATS: {}", e)))?;

        let jetstream = jetstream::new(client.clone());

        Ok(Self { client, jetstream })
    }

    /// Get the NATS client
    pub fn get_client(&self) -> &Client {
        &self.client
    }

    /// Initialize streams and consumers
    pub async fn initialize(&self) -> TaskResult<()> {
        info!("Initializing JetStream streams");

        // Create task events stream
        self.create_stream(
            "TASK_EVENTS",
            vec![format!("{}.*", subjects::TASK_EVENTS)],
            "Stream for task lifecycle events",
        )
        .await?;

        // Create worker events stream
        self.create_stream(
            "WORKER_EVENTS",
            vec![format!("{}.*", subjects::WORKER_EVENTS)],
            "Stream for worker events",
        )
        .await?;

        // Create task queue stream
        self.create_stream(
            "TASK_QUEUE",
            vec![subjects::TASK_QUEUE.to_string()],
            "Stream for task work queue",
        )
        .await?;

        // Create task results stream
        self.create_stream(
            "TASK_RESULTS",
            vec![subjects::TASK_RESULTS.to_string()],
            "Stream for task results",
        )
        .await?;

        info!("JetStream streams initialized successfully");
        Ok(())
    }

    /// Create a stream if it doesn't exist
    async fn create_stream(
        &self,
        name: &str,
        subjects: Vec<String>,
        description: &str,
    ) -> TaskResult<Stream> {
        let config = Config {
            name: name.to_string(),
            subjects,
            description: Some(description.to_string()),
            retention: jetstream::stream::RetentionPolicy::Limits,
            max_messages: 100_000,                          // 100K messages
            max_bytes: 100 * 1024 * 1024,                   // 100MB per stream
            max_age: Duration::from_secs(24 * 60 * 60),     // 1 day
            storage: jetstream::stream::StorageType::File,
            num_replicas: 1,
            duplicate_window: Duration::from_secs(120),
            ..Default::default()
        };

        match self.jetstream.get_or_create_stream(config).await {
            Ok(stream) => {
                debug!("Stream '{}' ready", name);
                Ok(stream)
            }
            Err(e) => {
                error!("Failed to create stream '{}': {}", name, e);
                Err(TaskError::QueueError(format!(
                    "Failed to create stream '{}': {}",
                    name, e
                )))
            }
        }
    }

    /// Publish an event
    pub async fn publish_event(&self, event: TaskEvent, source: String) -> TaskResult<()> {
        let envelope = EventEnvelope::new(event.clone(), source);
        let subject = match &event {
            TaskEvent::Created { .. } => subjects::TASK_CREATED,
            TaskEvent::Queued { .. } => subjects::TASK_QUEUED,
            TaskEvent::Assigned { .. } => subjects::TASK_ASSIGNED,
            TaskEvent::Started { .. } => subjects::TASK_STARTED,
            TaskEvent::Completed { .. } => subjects::TASK_COMPLETED,
            TaskEvent::Failed { .. } => subjects::TASK_FAILED,
            TaskEvent::Retrying { .. } => subjects::TASK_RETRYING,
            TaskEvent::Cancelled { .. } => subjects::TASK_CANCELLED,
            TaskEvent::WorkerHeartbeat { .. } => subjects::WORKER_HEARTBEAT,
            TaskEvent::WorkerJoined { .. } => subjects::WORKER_JOINED,
            TaskEvent::WorkerLeft { .. } => subjects::WORKER_LEFT,
            TaskEvent::UnsupportedMethod { .. } => subjects::TASK_UNSUPPORTED_METHOD,
            _ => subjects::TASK_EVENTS,
        };

        let payload = serde_json::to_vec(&envelope)
            .map_err(|e| TaskError::SerializationError(e.to_string()))?;

        self.client
            .publish(subject, payload.into())
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to publish event: {}", e)))?;

        debug!("Published event to {}: {:?}", subject, event);
        Ok(())
    }

    /// Subscribe to events
    pub async fn subscribe_events(&self, pattern: &str) -> TaskResult<EventSubscriber> {
        let subscriber = self
            .client
            .subscribe(pattern.to_string())
            .await
            .map_err(|e| {
                TaskError::QueueError(format!("Failed to subscribe to {}: {}", pattern, e))
            })?;

        Ok(EventSubscriber { subscriber })
    }

    /// Queue a task for processing
    pub async fn queue_task(&self, task_data: QueuedTask) -> TaskResult<()> {
        let payload = serde_json::to_vec(&task_data)
            .map_err(|e| TaskError::SerializationError(e.to_string()))?;

        self.jetstream
            .publish(subjects::TASK_QUEUE, payload.into())
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to queue task: {}", e)))?
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to confirm task queue: {}", e)))?;

        debug!("Queued task {}", task_data.task_id);
        Ok(())
    }

    /// Create a task queue consumer
    pub async fn create_task_consumer(
        &self,
        worker_id: &str,
        max_concurrent: usize,
    ) -> TaskResult<TaskConsumer> {
        self.create_task_consumer_with_config(worker_id, max_concurrent, 10, 1).await
    }
    
    /// Create a task queue consumer with custom configuration
    pub async fn create_task_consumer_with_config(
        &self,
        worker_id: &str,
        max_concurrent: usize,
        batch_size: usize,
        fetch_timeout_ms: u64,
    ) -> TaskResult<TaskConsumer> {
        let stream = self.jetstream.get_stream("TASK_QUEUE").await.map_err(|e| {
            TaskError::QueueError(format!("Failed to get task queue stream: {}", e))
        })?;

        // Use a shared consumer group for all workers
        let consumer_config = consumer::pull::Config {
            name: Some("task-workers".to_string()),
            durable_name: Some("task-workers".to_string()),
            ack_policy: consumer::AckPolicy::Explicit,
            ack_wait: Duration::from_secs(300), // 5 minutes
            max_deliver: 4,  // Allow 1 initial delivery + 3 retries
            max_ack_pending: max_concurrent as i64,
            filter_subject: subjects::TASK_QUEUE.to_string(),
            ..Default::default()
        };

        // Get or create the shared consumer
        let consumer = stream
            .get_or_create_consumer("task-workers", consumer_config)
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to create consumer: {}", e)))?;

        Ok(TaskConsumer {
            consumer,
            worker_id: worker_id.to_string(),
            batch_size,
            fetch_timeout_ms,
        })
    }

    /// Publish task result
    pub async fn publish_result(&self, result: TaskResultMessage) -> TaskResult<()> {
        let payload = serde_json::to_vec(&result)
            .map_err(|e| TaskError::SerializationError(e.to_string()))?;

        self.jetstream
            .publish(subjects::TASK_RESULTS, payload.into())
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to publish result: {}", e)))?
            .await
            .map_err(|e| {
                TaskError::QueueError(format!("Failed to confirm result publish: {}", e))
            })?;

        debug!("Published result for task {}", result.task_id);
        Ok(())
    }
}

/// Task data in the queue
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct QueuedTask {
    pub task_id: Uuid,
    pub method: String,
    pub args: Vec<serde_json::Value>,
    pub priority: i32,
    pub retry_count: u32,
    pub max_retries: u32,
    pub timeout_seconds: u64,
    pub metadata: std::collections::HashMap<String, String>,
    /// List of task IDs that this task depends on
    pub dependencies: Vec<Uuid>,
}

/// Task result message
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TaskResultMessage {
    pub task_id: Uuid,
    pub success: bool,
    pub result: Option<serde_json::Value>,
    pub error: Option<String>,
    pub execution_time_ms: u64,
    pub worker_id: String,
}

/// Event subscriber wrapper
pub struct EventSubscriber {
    subscriber: Subscriber,
}

impl EventSubscriber {
    /// Get the next event
    pub async fn next(&mut self) -> TaskResult<Option<EventEnvelope>> {
        match self.subscriber.next().await {
            Some(msg) => {
                // Skip empty messages
                if msg.payload.is_empty() {
                    debug!("Received empty message on subject: {}", msg.subject);
                    return Ok(None);
                }
                
                // Try to deserialize
                match serde_json::from_slice::<EventEnvelope>(&msg.payload) {
                    Ok(envelope) => Ok(Some(envelope)),
                    Err(e) => {
                        // Log the error but don't fail - just skip this message
                        debug!(
                            "Failed to deserialize event on subject {}: {}. Payload: {:?}",
                            msg.subject,
                            e,
                            String::from_utf8_lossy(&msg.payload)
                        );
                        Ok(None)
                    }
                }
            }
            None => Ok(None),
        }
    }
}

/// Task consumer for workers
pub struct TaskConsumer {
    consumer: consumer::Consumer<consumer::pull::Config>,
    worker_id: String,
    batch_size: usize,
    fetch_timeout_ms: u64,
}

impl TaskConsumer {
    /// Fetch tasks from the queue
    pub async fn fetch(&self, batch_size: usize) -> TaskResult<Vec<(QueuedTask, MessageHandle)>> {
        use futures::TryStreamExt;

        let messages = self
            .consumer
            .fetch()
            .max_messages(batch_size.max(self.batch_size))
            .expires(Duration::from_millis(self.fetch_timeout_ms))
            .messages()
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to fetch tasks: {}", e)))?;

        let mut tasks = Vec::new();
        let msgs: Vec<_> = messages
            .try_collect()
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to collect messages: {}", e)))?;

        for msg in msgs {
            match serde_json::from_slice::<QueuedTask>(&msg.payload) {
                Ok(task) => {
                    let handle = MessageHandle {
                        inner: msg,
                        _worker_id: self.worker_id.clone(),
                    };
                    tasks.push((task, handle));
                }
                Err(e) => {
                    error!("Failed to deserialize task: {}", e);
                    // NAK the message to requeue it
                    if let Err(e) = msg.ack_with(jetstream::AckKind::Nak(None)).await {
                        error!("Failed to NAK invalid message: {}", e);
                    }
                }
            }
        }

        Ok(tasks)
    }
}

/// Message handle for acknowledging tasks
pub struct MessageHandle {
    inner: jetstream::Message,
    _worker_id: String,
}

impl MessageHandle {
    /// Get the delivery count (number of times this message has been delivered)
    pub fn delivery_count(&self) -> u64 {
        // NATS JetStream messages have an info() method that provides metadata
        match self.inner.info() {
            Ok(info) => info.delivered as u64,
            Err(_) => 1, // Default to 1 if no info available
        }
    }

    /// Acknowledge successful processing
    pub async fn ack(self) -> TaskResult<()> {
        self.inner
            .ack()
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to ack message: {}", e)))?;
        Ok(())
    }

    /// Negative acknowledge with optional delay
    pub async fn nack(self, delay: Option<Duration>) -> TaskResult<()> {
        self.inner
            .ack_with(jetstream::AckKind::Nak(delay))
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to nack message: {}", e)))?;
        Ok(())
    }

    /// Mark as in progress (extends ack deadline)
    pub async fn in_progress(self) -> TaskResult<()> {
        self.inner
            .ack_with(jetstream::AckKind::Progress)
            .await
            .map_err(|e| TaskError::QueueError(format!("Failed to mark in progress: {}", e)))?;
        Ok(())
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_queue_manager_connection() {
        // Test with mock implementation
        use crate::queue::mock::MockQueueManager;
        
        let mock_manager = MockQueueManager::new();
        let result = mock_manager.initialize().await;
        assert!(result.is_ok());
        
        // If NATS is available, test real connection
        if let Ok(nats_url) = std::env::var("NATS_URL") {
            match QueueManager::new(&nats_url).await {
                Ok(_) => println!("Real NATS connection successful"),
                Err(_) => println!("Real NATS connection failed, using mock"),
            }
        }
    }
    
    #[tokio::test]
    async fn test_mock_queue_functionality() {
        use crate::queue::mock::MockQueueManager;
        
        let mock_manager = MockQueueManager::new();
        mock_manager.initialize().await.unwrap();
        
        // Test event publishing and subscribing
        let mut subscriber = mock_manager.subscribe_events("tasks.events.*").await.unwrap();
        
        let event = TaskEvent::Created {
            task: Box::new(crate::models::TaskInfo {
                id: Uuid::new_v4(),
                method: "test".to_string(),
                args: vec![],
                dependencies: vec![],
                priority: 0,
                metadata: Default::default(),
                status: Default::default(),
                created_at: chrono::Utc::now(),
                updated_at: chrono::Utc::now(),
            }),
            source: "test".to_string(),
        };
        
        mock_manager.publish_event(event.clone(), "test".to_string()).await.unwrap();
        
        // Should receive the event
        let received = subscriber.next().await.unwrap();
        assert!(received.is_some());
        if let Some(envelope) = received {
            match (envelope.event, event) {
                (TaskEvent::Created { task: t1, source: s1 }, TaskEvent::Created { task: t2, source: s2 }) => {
                    assert_eq!(t1.id, t2.id);
                    assert_eq!(s1, s2);
                }
                _ => panic!("Event mismatch"),
            }
        }
    }
}
