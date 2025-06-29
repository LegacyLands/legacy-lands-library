use chrono::{DateTime, Utc};
use serde::{Deserialize, Serialize};
use std::collections::HashMap;
use std::path::Path;
use std::sync::Arc;
use thiserror::Error;
use tokio::io::AsyncWriteExt;
use tokio::sync::{mpsc, Mutex};
use tracing::{error, info};
use uuid::Uuid;

/// Audit logging errors
#[derive(Debug, Error)]
pub enum AuditError {
    #[error("IO error: {0}")]
    IoError(#[from] std::io::Error),

    #[error("Serialization error: {0}")]
    SerializationError(#[from] serde_json::Error),

    #[error("Audit logger not initialized")]
    NotInitialized,

    #[error("Audit logger shut down")]
    ShutDown,
}

pub type AuditResult<T> = Result<T, AuditError>;

/// Audit event severity levels
#[derive(Debug, Clone, Copy, Serialize, Deserialize, PartialEq, Eq, PartialOrd, Ord)]
pub enum AuditSeverity {
    Info,
    Warning,
    Critical,
}

/// Audit event categories
#[derive(Debug, Clone, Serialize, Deserialize)]
pub enum AuditCategory {
    Authentication,
    Authorization,
    TaskExecution,
    Configuration,
    DataAccess,
    SystemOperation,
    SecurityViolation,
}

/// Audit event outcome
#[derive(Debug, Clone, Serialize, Deserialize, PartialEq)]
pub enum AuditOutcome {
    Success,
    Failure,
    Denied,
}

/// Audit event structure
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct AuditEvent {
    /// Unique event ID
    pub id: Uuid,

    /// Event timestamp
    pub timestamp: DateTime<Utc>,

    /// Event category
    pub category: AuditCategory,

    /// Event severity
    pub severity: AuditSeverity,

    /// Event outcome
    pub outcome: AuditOutcome,

    /// User/subject that triggered the event
    pub subject: String,

    /// Resource being accessed
    pub resource: String,

    /// Action performed
    pub action: String,

    /// Event description
    pub description: String,

    /// Additional context data
    pub context: HashMap<String, serde_json::Value>,

    /// Source IP address (if available)
    pub source_ip: Option<String>,

    /// Trace ID for correlation
    pub trace_id: Option<String>,
}

impl AuditEvent {
    /// Create a new audit event
    pub fn new(
        category: AuditCategory,
        severity: AuditSeverity,
        outcome: AuditOutcome,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
        description: impl Into<String>,
    ) -> Self {
        Self {
            id: Uuid::new_v4(),
            timestamp: Utc::now(),
            category,
            severity,
            outcome,
            subject: subject.into(),
            resource: resource.into(),
            action: action.into(),
            description: description.into(),
            context: HashMap::new(),
            source_ip: None,
            trace_id: None,
        }
    }

    /// Add context data
    pub fn with_context(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.context.insert(key.into(), value);
        self
    }

    /// Set source IP
    pub fn with_source_ip(mut self, ip: impl Into<String>) -> Self {
        self.source_ip = Some(ip.into());
        self
    }

    /// Set trace ID
    pub fn with_trace_id(mut self, trace_id: impl Into<String>) -> Self {
        self.trace_id = Some(trace_id.into());
        self
    }
}

/// Audit event builder for convenience
pub struct AuditEventBuilder {
    event: AuditEvent,
}

impl AuditEventBuilder {
    /// Create a success event
    pub fn success(
        category: AuditCategory,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
    ) -> Self {
        let action_str = action.into();
        Self {
            event: AuditEvent::new(
                category,
                AuditSeverity::Info,
                AuditOutcome::Success,
                subject,
                resource,
                action_str.clone(),
                format!("Successfully performed {}", action_str),
            ),
        }
    }

    /// Create a failure event
    pub fn failure(
        category: AuditCategory,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
        reason: impl Into<String>,
    ) -> Self {
        let action_str = action.into();
        Self {
            event: AuditEvent::new(
                category,
                AuditSeverity::Warning,
                AuditOutcome::Failure,
                subject,
                resource,
                action_str.clone(),
                format!("Failed to perform {}: {}", action_str, reason.into()),
            ),
        }
    }

    /// Create a security violation event
    pub fn security_violation(
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
        violation: impl Into<String>,
    ) -> Self {
        Self {
            event: AuditEvent::new(
                AuditCategory::SecurityViolation,
                AuditSeverity::Critical,
                AuditOutcome::Denied,
                subject,
                resource,
                action,
                format!("Security violation: {}", violation.into()),
            ),
        }
    }

    /// Set severity
    pub fn severity(mut self, severity: AuditSeverity) -> Self {
        self.event.severity = severity;
        self
    }

    /// Add context
    pub fn context(mut self, key: impl Into<String>, value: serde_json::Value) -> Self {
        self.event.context.insert(key.into(), value);
        self
    }

    /// Set source IP
    pub fn source_ip(mut self, ip: impl Into<String>) -> Self {
        self.event.source_ip = Some(ip.into());
        self
    }

    /// Set trace ID
    pub fn trace_id(mut self, trace_id: impl Into<String>) -> Self {
        self.event.trace_id = Some(trace_id.into());
        self
    }

    /// Build the event
    pub fn build(self) -> AuditEvent {
        self.event
    }
}

/// Audit logger configuration
#[derive(Debug, Clone)]
pub struct AuditConfig {
    /// File path for audit logs
    pub file_path: Option<String>,

    /// Whether to log to stdout
    pub log_to_stdout: bool,

    /// Minimum severity level to log
    pub min_severity: AuditSeverity,

    /// Buffer size for async logging
    pub buffer_size: usize,

    /// Whether to include full context in logs
    pub include_context: bool,
}

impl Default for AuditConfig {
    fn default() -> Self {
        Self {
            file_path: Some("/var/log/task-scheduler/audit.log".to_string()),
            log_to_stdout: false,
            min_severity: AuditSeverity::Info,
            buffer_size: 1000,
            include_context: true,
        }
    }
}

/// Audit logger for security events
pub struct AuditLogger {
    /// Logger configuration
    config: AuditConfig,

    /// Channel sender for async logging
    sender: Arc<Mutex<Option<mpsc::Sender<AuditEvent>>>>,

    /// Join handle for the logger task
    handle: Arc<Mutex<Option<tokio::task::JoinHandle<()>>>>,
}

impl AuditLogger {
    /// Create a new audit logger
    pub async fn new(config: AuditConfig) -> AuditResult<Self> {
        let (sender, receiver) = mpsc::channel(config.buffer_size);

        // Start the logging task
        let config_clone = config.clone();
        let handle = tokio::spawn(async move {
            Self::logging_task(config_clone, receiver).await;
        });

        Ok(Self {
            config,
            sender: Arc::new(Mutex::new(Some(sender))),
            handle: Arc::new(Mutex::new(Some(handle))),
        })
    }

    /// Log an audit event
    pub async fn log(&self, event: AuditEvent) -> AuditResult<()> {
        // Filter by severity
        if event.severity < self.config.min_severity {
            return Ok(());
        }

        // Send to logging task
        let sender = self.sender.lock().await;
        if let Some(sender) = sender.as_ref() {
            sender
                .send(event)
                .await
                .map_err(|_| AuditError::ShutDown)?;
        } else {
            return Err(AuditError::ShutDown);
        }

        Ok(())
    }

    /// Create a success event and log it
    pub async fn log_success(
        &self,
        category: AuditCategory,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
    ) -> AuditResult<()> {
        let event = AuditEventBuilder::success(category, subject, resource, action).build();
        self.log(event).await
    }

    /// Create a failure event and log it
    pub async fn log_failure(
        &self,
        category: AuditCategory,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
        reason: impl Into<String>,
    ) -> AuditResult<()> {
        let event =
            AuditEventBuilder::failure(category, subject, resource, action, reason).build();
        self.log(event).await
    }

    /// Create a security violation event and log it
    pub async fn log_security_violation(
        &self,
        subject: impl Into<String>,
        resource: impl Into<String>,
        action: impl Into<String>,
        violation: impl Into<String>,
    ) -> AuditResult<()> {
        let event =
            AuditEventBuilder::security_violation(subject, resource, action, violation).build();
        self.log(event).await
    }

    /// Shutdown the audit logger
    pub async fn shutdown(&self) -> AuditResult<()> {
        // Close the channel by dropping the sender
        self.sender.lock().await.take();

        // Wait for the logging task to finish
        if let Some(handle) = self.handle.lock().await.take() {
            handle.await.ok();
        }

        info!("Audit logger shut down");
        Ok(())
    }

    /// The logging task that writes events to file/stdout
    async fn logging_task(config: AuditConfig, mut receiver: mpsc::Receiver<AuditEvent>) {
        // Open file if configured
        let file = if let Some(path) = &config.file_path {
            // Create directory if it doesn't exist
            if let Some(parent) = Path::new(path).parent() {
                if let Err(e) = tokio::fs::create_dir_all(parent).await {
                    error!("Failed to create audit log directory: {}", e);
                }
            }

            match tokio::fs::OpenOptions::new()
                .create(true)
                .append(true)
                .open(path)
                .await
            {
                Ok(f) => Some(Arc::new(Mutex::new(f))),
                Err(e) => {
                    error!("Failed to open audit log file: {}", e);
                    None
                }
            }
        } else {
            None
        };

        // Process events
        while let Some(event) = receiver.recv().await {
            // Format the event
            let formatted = if config.include_context {
                match serde_json::to_string(&event) {
                    Ok(json) => json,
                    Err(e) => {
                        error!("Failed to serialize audit event: {}", e);
                        continue;
                    }
                }
            } else {
                // Simplified format without full context
                format!(
                    "{} [{}] {} - {} {} {} - {}",
                    event.timestamp.to_rfc3339(),
                    format!("{:?}", event.severity).to_uppercase(),
                    format!("{:?}", event.outcome),
                    event.subject,
                    event.action,
                    event.resource,
                    event.description
                )
            };

            // Write to file
            if let Some(file_arc) = &file {
                let mut file = file_arc.lock().await;
                if let Err(e) = file.write_all(format!("{}\n", formatted).as_bytes()).await {
                    error!("Failed to write audit log: {}", e);
                }
                if let Err(e) = file.flush().await {
                    error!("Failed to flush audit log: {}", e);
                }
            }

            // Write to stdout if configured
            if config.log_to_stdout {
                println!("AUDIT: {}", formatted);
            }
        }

        info!("Audit logging task completed");
    }
}

/// Global audit logger instance
static GLOBAL_AUDIT_LOGGER: Mutex<Option<Arc<AuditLogger>>> = Mutex::const_new(None);

/// Initialize the global audit logger
pub async fn init_global_audit_logger(config: AuditConfig) -> AuditResult<()> {
    let logger = Arc::new(AuditLogger::new(config).await?);
    *GLOBAL_AUDIT_LOGGER.lock().await = Some(logger);
    info!("Global audit logger initialized");
    Ok(())
}

/// Get the global audit logger
pub async fn audit_logger() -> AuditResult<Arc<AuditLogger>> {
    GLOBAL_AUDIT_LOGGER
        .lock()
        .await
        .clone()
        .ok_or(AuditError::NotInitialized)
}

/// Shutdown the global audit logger
pub async fn shutdown_global_audit_logger() -> AuditResult<()> {
    if let Some(logger) = GLOBAL_AUDIT_LOGGER.lock().await.take() {
        logger.shutdown().await?;
    }
    Ok(())
}

#[cfg(test)]
mod tests {
    use super::*;
    use tempfile::NamedTempFile;

    #[tokio::test]
    async fn test_audit_event_builder() {
        let event = AuditEventBuilder::success(
            AuditCategory::TaskExecution,
            "user123",
            "task:456",
            "execute",
        )
        .context("task_type", serde_json::json!("data_processing"))
        .source_ip("192.168.1.100")
        .build();

        assert_eq!(event.subject, "user123");
        assert_eq!(event.resource, "task:456");
        assert_eq!(event.action, "execute");
        assert_eq!(event.outcome, AuditOutcome::Success);
        assert_eq!(event.source_ip, Some("192.168.1.100".to_string()));
    }

    #[tokio::test]
    async fn test_audit_logger() {
        let temp_file = NamedTempFile::new().unwrap();
        let config = AuditConfig {
            file_path: Some(temp_file.path().to_str().unwrap().to_string()),
            log_to_stdout: false,
            min_severity: AuditSeverity::Info,
            buffer_size: 100,
            include_context: true,
        };

        let logger = AuditLogger::new(config).await.unwrap();

        // Log some events
        logger
            .log_success(
                AuditCategory::Authentication,
                "user123",
                "system",
                "login",
            )
            .await
            .unwrap();

        logger
            .log_failure(
                AuditCategory::Authorization,
                "user456",
                "task:789",
                "delete",
                "insufficient permissions",
            )
            .await
            .unwrap();

        logger
            .log_security_violation(
                "attacker",
                "system",
                "brute_force",
                "multiple failed login attempts",
            )
            .await
            .unwrap();

        // Give some time for the logging task to process
        tokio::time::sleep(tokio::time::Duration::from_millis(100)).await;

        // Shutdown and check file contents
        logger.shutdown().await.unwrap();

        let contents = tokio::fs::read_to_string(temp_file.path())
            .await
            .unwrap();
        assert!(contents.contains("user123"));
        assert!(contents.contains("login"));
        assert!(contents.contains("insufficient permissions"));
        assert!(contents.contains("Security violation"));
    }
}