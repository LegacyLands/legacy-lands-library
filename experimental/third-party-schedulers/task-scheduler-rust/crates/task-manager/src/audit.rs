use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use serde_json::Value;
use std::collections::HashMap;
use std::sync::Arc;
use tokio::sync::mpsc;
use tracing::error;
use uuid::Uuid;

/// Audit event type
#[derive(Debug, Clone, Copy, PartialEq, Eq, Serialize, Deserialize)]
#[serde(rename_all = "snake_case")]
pub enum AuditEventType {
    // Task lifecycle events
    TaskSubmitted,
    TaskQueued,
    TaskStarted,
    TaskCompleted,
    TaskFailed,
    TaskCancelled,
    TaskPaused,
    TaskResumed,
    TaskRetried,

    // Worker events
    WorkerJoined,
    WorkerLeft,
    WorkerHeartbeat,

    // System events
    ConfigChanged,
    SystemStartup,
    SystemShutdown,

    // Security events
    AuthenticationFailure,
    AuthorizationFailure,
    AccessDenied,
}

/// Audit log entry
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEntry {
    /// Entry ID
    pub id: Uuid,

    /// Timestamp
    pub timestamp: DateTime<Utc>,

    /// Event type
    pub event_type: AuditEventType,

    /// Actor (user, service, or system)
    pub actor: String,

    /// Resource type (task, worker, config, etc.)
    pub resource_type: String,

    /// Resource ID
    pub resource_id: String,

    /// Event details
    pub details: HashMap<String, Value>,

    /// Source IP address (if applicable)
    pub ip_address: Option<String>,

    /// User agent (if applicable)
    pub user_agent: Option<String>,

    /// Correlation ID for tracing related events
    pub correlation_id: Option<Uuid>,
}

/// Audit logger trait
#[async_trait::async_trait]
pub trait AuditLogger: Send + Sync {
    /// Log an audit entry
    async fn log(&self, entry: AuditEntry) -> Result<(), Box<dyn std::error::Error>>;

    /// Query audit logs
    async fn query(
        &self,
        filter: AuditQuery,
    ) -> Result<Vec<AuditEntry>, Box<dyn std::error::Error>>;
}

/// Audit query filter
#[derive(Debug, Clone, Default)]
pub struct AuditQuery {
    /// Filter by event type
    pub event_type: Option<AuditEventType>,

    /// Filter by actor
    pub actor: Option<String>,

    /// Filter by resource type
    pub resource_type: Option<String>,

    /// Filter by resource ID
    pub resource_id: Option<String>,

    /// Filter by time range (start)
    pub start_time: Option<DateTime<Utc>>,

    /// Filter by time range (end)
    pub end_time: Option<DateTime<Utc>>,

    /// Filter by correlation ID
    pub correlation_id: Option<Uuid>,

    /// Maximum results
    pub limit: Option<usize>,
}

/// In-memory audit logger (for development/testing)
pub struct InMemoryAuditLogger {
    entries: Arc<parking_lot::RwLock<Vec<AuditEntry>>>,
    max_entries: usize,
}

impl InMemoryAuditLogger {
    /// Create a new in-memory audit logger
    pub fn new(max_entries: usize) -> Self {
        Self {
            entries: Arc::new(parking_lot::RwLock::new(Vec::new())),
            max_entries,
        }
    }
}

#[async_trait::async_trait]
impl AuditLogger for InMemoryAuditLogger {
    async fn log(&self, entry: AuditEntry) -> Result<(), Box<dyn std::error::Error>> {
        let mut entries = self.entries.write();

        // Maintain max entries limit
        if entries.len() >= self.max_entries {
            entries.remove(0);
        }

        entries.push(entry);
        Ok(())
    }

    async fn query(
        &self,
        filter: AuditQuery,
    ) -> Result<Vec<AuditEntry>, Box<dyn std::error::Error>> {
        let entries = self.entries.read();

        let mut results: Vec<_> = entries
            .iter()
            .filter(|entry| {
                // Apply filters
                if let Some(event_type) = filter.event_type {
                    if entry.event_type != event_type {
                        return false;
                    }
                }

                if let Some(ref actor) = filter.actor {
                    if &entry.actor != actor {
                        return false;
                    }
                }

                if let Some(ref resource_type) = filter.resource_type {
                    if &entry.resource_type != resource_type {
                        return false;
                    }
                }

                if let Some(ref resource_id) = filter.resource_id {
                    if &entry.resource_id != resource_id {
                        return false;
                    }
                }

                if let Some(start_time) = filter.start_time {
                    if entry.timestamp < start_time {
                        return false;
                    }
                }

                if let Some(end_time) = filter.end_time {
                    if entry.timestamp > end_time {
                        return false;
                    }
                }

                if let Some(correlation_id) = filter.correlation_id {
                    if entry.correlation_id != Some(correlation_id) {
                        return false;
                    }
                }

                true
            })
            .cloned()
            .collect();

        // Sort by timestamp (newest first)
        results.sort_by(|a, b| b.timestamp.cmp(&a.timestamp));

        // Apply limit
        if let Some(limit) = filter.limit {
            results.truncate(limit);
        }

        Ok(results)
    }
}

/// Asynchronous audit logger that batches entries
pub struct AsyncAuditLogger {
    sender: mpsc::Sender<AuditEntry>,
}

impl AsyncAuditLogger {
    /// Create a new async audit logger
    pub fn new<L: AuditLogger + Send + Sync + 'static>(logger: Arc<L>, buffer_size: usize) -> Self {
        let (sender, mut receiver) = mpsc::channel::<AuditEntry>(buffer_size);

        // Spawn background task to process entries
        tokio::spawn(async move {
            while let Some(entry) = receiver.recv().await {
                if let Err(e) = logger.log(entry).await {
                    error!("Failed to log audit entry: {}", e);
                }
            }
        });

        Self { sender }
    }

    /// Log an audit entry
    pub async fn log(&self, entry: AuditEntry) -> Result<(), Box<dyn std::error::Error>> {
        self.sender
            .send(entry)
            .await
            .map_err(|e| format!("Failed to send audit entry: {}", e).into())
    }
}

/// Audit context for tracking related events
#[derive(Debug, Clone)]
pub struct AuditContext {
    correlation_id: Uuid,
    actor: String,
    ip_address: Option<String>,
    user_agent: Option<String>,
}

impl AuditContext {
    /// Create a new audit context
    pub fn new(actor: String) -> Self {
        Self {
            correlation_id: Uuid::new_v4(),
            actor,
            ip_address: None,
            user_agent: None,
        }
    }

    /// Set IP address
    pub fn with_ip(mut self, ip: String) -> Self {
        self.ip_address = Some(ip);
        self
    }

    /// Set user agent
    pub fn with_user_agent(mut self, ua: String) -> Self {
        self.user_agent = Some(ua);
        self
    }

    /// Create an audit entry
    pub fn create_entry(
        &self,
        event_type: AuditEventType,
        resource_type: &str,
        resource_id: &str,
        details: HashMap<String, Value>,
    ) -> AuditEntry {
        AuditEntry {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            event_type,
            actor: self.actor.clone(),
            resource_type: resource_type.to_string(),
            resource_id: resource_id.to_string(),
            details,
            ip_address: self.ip_address.clone(),
            user_agent: self.user_agent.clone(),
            correlation_id: Some(self.correlation_id),
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;
    use chrono::TimeZone;

    #[test]
    fn test_audit_event_type_serialization() {
        // Test that event types serialize correctly
        assert_eq!(
            serde_json::to_string(&AuditEventType::TaskSubmitted).unwrap(),
            "\"task_submitted\""
        );
        assert_eq!(
            serde_json::to_string(&AuditEventType::AuthenticationFailure).unwrap(),
            "\"authentication_failure\""
        );
    }

    #[test]
    fn test_audit_event_type_deserialization() {
        assert_eq!(
            serde_json::from_str::<AuditEventType>("\"task_submitted\"").unwrap(),
            AuditEventType::TaskSubmitted
        );
        assert_eq!(
            serde_json::from_str::<AuditEventType>("\"worker_heartbeat\"").unwrap(),
            AuditEventType::WorkerHeartbeat
        );
    }

    #[test]
    fn test_audit_entry_creation() {
        let entry = AuditEntry {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            event_type: AuditEventType::TaskSubmitted,
            actor: "test_user".to_string(),
            resource_type: "task".to_string(),
            resource_id: "task_123".to_string(),
            details: HashMap::new(),
            ip_address: Some("192.168.1.1".to_string()),
            user_agent: Some("TestClient/1.0".to_string()),
            correlation_id: Some(Uuid::new_v4()),
        };

        assert_eq!(entry.actor, "test_user");
        assert_eq!(entry.resource_type, "task");
        assert!(entry.ip_address.is_some());
    }

    #[test]
    fn test_audit_entry_serialization() {
        let mut details = HashMap::new();
        details.insert("key1".to_string(), Value::String("value1".to_string()));
        details.insert("key2".to_string(), Value::Number(serde_json::Number::from(42)));

        let entry = AuditEntry {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            event_type: AuditEventType::TaskCompleted,
            actor: "system".to_string(),
            resource_type: "task".to_string(),
            resource_id: "task_456".to_string(),
            details,
            ip_address: None,
            user_agent: None,
            correlation_id: None,
        };

        let json = serde_json::to_string(&entry).unwrap();
        assert!(json.contains("\"event_type\":\"task_completed\""));
        assert!(json.contains("\"actor\":\"system\""));
        assert!(json.contains("\"key1\":\"value1\""));
    }

    #[test]
    fn test_audit_query_default() {
        let query = AuditQuery::default();
        assert!(query.event_type.is_none());
        assert!(query.actor.is_none());
        assert!(query.resource_type.is_none());
        assert!(query.resource_id.is_none());
        assert!(query.start_time.is_none());
        assert!(query.end_time.is_none());
        assert!(query.correlation_id.is_none());
        assert!(query.limit.is_none());
    }

    #[test]
    fn test_audit_query_with_filters() {
        let start_time = Utc.with_ymd_and_hms(2024, 1, 1, 0, 0, 0).unwrap();
        let end_time = Utc.with_ymd_and_hms(2024, 1, 2, 0, 0, 0).unwrap();
        
        let query = AuditQuery {
            event_type: Some(AuditEventType::TaskSubmitted),
            actor: Some("user123".to_string()),
            resource_type: Some("task".to_string()),
            resource_id: Some("task_789".to_string()),
            start_time: Some(start_time),
            end_time: Some(end_time),
            correlation_id: Some(Uuid::new_v4()),
            limit: Some(100),
        };

        assert_eq!(query.event_type, Some(AuditEventType::TaskSubmitted));
        assert_eq!(query.actor, Some("user123".to_string()));
        assert_eq!(query.limit, Some(100));
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_new() {
        let logger = InMemoryAuditLogger::new(100);
        let entries = logger.entries.read();
        assert!(entries.is_empty());
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_log() {
        let logger = InMemoryAuditLogger::new(100);
        
        let entry = AuditEntry {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            event_type: AuditEventType::TaskSubmitted,
            actor: "test_user".to_string(),
            resource_type: "task".to_string(),
            resource_id: "task_123".to_string(),
            details: HashMap::new(),
            ip_address: None,
            user_agent: None,
            correlation_id: None,
        };

        logger.log(entry.clone()).await.unwrap();
        
        let entries = logger.entries.read();
        assert_eq!(entries.len(), 1);
        assert_eq!(entries[0].id, entry.id);
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_max_entries() {
        let logger = InMemoryAuditLogger::new(3);
        
        // Log 5 entries
        for i in 0..5 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type: AuditEventType::TaskSubmitted,
                actor: format!("user_{}", i),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        // Should only keep last 3 entries
        let entries = logger.entries.read();
        assert_eq!(entries.len(), 3);
        assert_eq!(entries[0].actor, "user_2");
        assert_eq!(entries[1].actor, "user_3");
        assert_eq!(entries[2].actor, "user_4");
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_no_filters() {
        let logger = InMemoryAuditLogger::new(100);
        
        // Log some entries
        for i in 0..3 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type: if i % 2 == 0 {
                    AuditEventType::TaskSubmitted
                } else {
                    AuditEventType::TaskCompleted
                },
                actor: format!("user_{}", i),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        let results = logger.query(AuditQuery::default()).await.unwrap();
        assert_eq!(results.len(), 3);
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_event_type_filter() {
        let logger = InMemoryAuditLogger::new(100);
        
        // Log entries with different event types
        for i in 0..4 {
            let event_type = match i % 4 {
                0 => AuditEventType::TaskSubmitted,
                1 => AuditEventType::TaskCompleted,
                2 => AuditEventType::TaskFailed,
                _ => AuditEventType::TaskCancelled,
            };
            
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type,
                actor: "user".to_string(),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        let query = AuditQuery {
            event_type: Some(AuditEventType::TaskSubmitted),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].event_type, AuditEventType::TaskSubmitted);
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_actor_filter() {
        let logger = InMemoryAuditLogger::new(100);
        
        // Log entries with different actors
        for i in 0..3 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type: AuditEventType::TaskSubmitted,
                actor: format!("user_{}", i % 2),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        let query = AuditQuery {
            actor: Some("user_0".to_string()),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 2);
        for result in results {
            assert_eq!(result.actor, "user_0");
        }
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_time_range() {
        let logger = InMemoryAuditLogger::new(100);
        
        let base_time = Utc::now();
        
        // Log entries at different times
        for i in 0..5 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: base_time + chrono::Duration::hours(i),
                event_type: AuditEventType::TaskSubmitted,
                actor: "user".to_string(),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        // Query for entries in the middle time range
        let query = AuditQuery {
            start_time: Some(base_time + chrono::Duration::hours(1)),
            end_time: Some(base_time + chrono::Duration::hours(3)),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 3); // Hours 1, 2, and 3
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_correlation_id() {
        let logger = InMemoryAuditLogger::new(100);
        let correlation_id = Uuid::new_v4();
        
        // Log entries with and without correlation ID
        for i in 0..4 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type: AuditEventType::TaskSubmitted,
                actor: "user".to_string(),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: if i % 2 == 0 { Some(correlation_id) } else { None },
            };
            logger.log(entry).await.unwrap();
        }
        
        let query = AuditQuery {
            correlation_id: Some(correlation_id),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 2);
        for result in results {
            assert_eq!(result.correlation_id, Some(correlation_id));
        }
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_limit() {
        let logger = InMemoryAuditLogger::new(100);
        
        // Log 10 entries
        for i in 0..10 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now() + chrono::Duration::seconds(i),
                event_type: AuditEventType::TaskSubmitted,
                actor: "user".to_string(),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        let query = AuditQuery {
            limit: Some(5),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 5);
        
        // Should return newest entries first
        assert_eq!(results[0].resource_id, "task_9");
        assert_eq!(results[4].resource_id, "task_5");
    }

    #[tokio::test]
    async fn test_in_memory_audit_logger_query_combined_filters() {
        let logger = InMemoryAuditLogger::new(100);
        
        // Log various entries
        for i in 0..10 {
            let entry = AuditEntry {
                id: Uuid::new_v4(),
                timestamp: Utc::now(),
                event_type: if i < 5 {
                    AuditEventType::TaskSubmitted
                } else {
                    AuditEventType::TaskCompleted
                },
                actor: format!("user_{}", i % 3),
                resource_type: "task".to_string(),
                resource_id: format!("task_{}", i),
                details: HashMap::new(),
                ip_address: None,
                user_agent: None,
                correlation_id: None,
            };
            logger.log(entry).await.unwrap();
        }
        
        let query = AuditQuery {
            event_type: Some(AuditEventType::TaskSubmitted),
            actor: Some("user_0".to_string()),
            ..Default::default()
        };
        
        let results = logger.query(query).await.unwrap();
        assert_eq!(results.len(), 2); // Entries 0 and 3
        for result in results {
            assert_eq!(result.event_type, AuditEventType::TaskSubmitted);
            assert_eq!(result.actor, "user_0");
        }
    }

    #[tokio::test]
    async fn test_async_audit_logger() {
        let in_memory_logger = Arc::new(InMemoryAuditLogger::new(100));
        let async_logger = AsyncAuditLogger::new(in_memory_logger.clone(), 100);
        
        let entry = AuditEntry {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            event_type: AuditEventType::TaskSubmitted,
            actor: "test_user".to_string(),
            resource_type: "task".to_string(),
            resource_id: "task_123".to_string(),
            details: HashMap::new(),
            ip_address: None,
            user_agent: None,
            correlation_id: None,
        };
        
        // Log entry through async logger
        async_logger.log(entry.clone()).await.unwrap();
        
        // Give async processing time to complete
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;
        
        // Check that entry was logged
        let results = in_memory_logger.query(AuditQuery::default()).await.unwrap();
        assert_eq!(results.len(), 1);
        assert_eq!(results[0].id, entry.id);
    }

    #[test]
    fn test_audit_context_new() {
        let context = AuditContext::new("test_actor".to_string());
        assert_eq!(context.actor, "test_actor");
        assert!(context.ip_address.is_none());
        assert!(context.user_agent.is_none());
    }

    #[test]
    fn test_audit_context_with_ip() {
        let context = AuditContext::new("test_actor".to_string())
            .with_ip("192.168.1.1".to_string());
        assert_eq!(context.ip_address, Some("192.168.1.1".to_string()));
    }

    #[test]
    fn test_audit_context_with_user_agent() {
        let context = AuditContext::new("test_actor".to_string())
            .with_user_agent("Mozilla/5.0".to_string());
        assert_eq!(context.user_agent, Some("Mozilla/5.0".to_string()));
    }

    #[test]
    fn test_audit_context_create_entry() {
        let context = AuditContext::new("test_actor".to_string())
            .with_ip("192.168.1.1".to_string())
            .with_user_agent("TestClient/1.0".to_string());
        
        let mut details = HashMap::new();
        details.insert("action".to_string(), Value::String("create".to_string()));
        
        let entry = context.create_entry(
            AuditEventType::TaskSubmitted,
            "task",
            "task_123",
            details.clone()
        );
        
        assert_eq!(entry.event_type, AuditEventType::TaskSubmitted);
        assert_eq!(entry.actor, "test_actor");
        assert_eq!(entry.resource_type, "task");
        assert_eq!(entry.resource_id, "task_123");
        assert_eq!(entry.details, details);
        assert_eq!(entry.ip_address, Some("192.168.1.1".to_string()));
        assert_eq!(entry.user_agent, Some("TestClient/1.0".to_string()));
        assert_eq!(entry.correlation_id, Some(context.correlation_id));
    }

    #[test]
    fn test_audit_context_correlation_id_consistent() {
        let context = AuditContext::new("test_actor".to_string());
        
        let entry1 = context.create_entry(
            AuditEventType::TaskSubmitted,
            "task",
            "task_1",
            HashMap::new()
        );
        
        let entry2 = context.create_entry(
            AuditEventType::TaskCompleted,
            "task",
            "task_1",
            HashMap::new()
        );
        
        // Both entries should have the same correlation ID
        assert_eq!(entry1.correlation_id, entry2.correlation_id);
        assert_eq!(entry1.correlation_id, Some(context.correlation_id));
    }
}
