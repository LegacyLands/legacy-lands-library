/// Security module for task manager
/// Handles authentication, authorization, and audit logging

pub mod auth;
pub mod interceptor;

pub use auth::{AuthService, TokenInfo};
pub use interceptor::{AuthInterceptor, SecurityContext};

use std::sync::Arc;
use task_common::security::{
    audit::{AuditConfig, AuditLogger, AuditSeverity},
    rbac::RoleBasedAccessControl,
    TlsConfig,
};
use tracing::info;

/// Security manager that coordinates all security features
pub struct SecurityManager {
    pub tls_config: Option<Arc<TlsConfig>>,
    pub rbac: Arc<RoleBasedAccessControl>,
    pub audit_logger: Option<Arc<AuditLogger>>,
    pub auth_service: Arc<AuthService>,
}

impl SecurityManager {
    /// Create a new security manager
    pub async fn new(config: &crate::config::SecurityConfig) -> Result<Self, Box<dyn std::error::Error>> {
        // Initialize RBAC
        let rbac = Arc::new(RoleBasedAccessControl::new());

        // Initialize audit logger if enabled
        let audit_logger = if config.audit_enabled {
            let audit_config = AuditConfig {
                file_path: Some(config.audit_log_path.clone()),
                log_to_stdout: false,
                min_severity: match config.audit_min_severity.as_str() {
                    "warning" => AuditSeverity::Warning,
                    "critical" => AuditSeverity::Critical,
                    _ => AuditSeverity::Info,
                },
                buffer_size: 1000,
                include_context: true,
            };
            Some(Arc::new(AuditLogger::new(audit_config).await?))
        } else {
            None
        };

        // Initialize auth service
        let auth_service = Arc::new(AuthService::new(
            config.jwt_secret.clone(),
            config.session_timeout,
            rbac.clone(),
        )?);

        // Create default admin user if configured
        if let (Some(username), Some(password)) = (&config.default_admin_user, &config.default_admin_password) {
            auth_service.create_default_admin(username, password).await?;
            info!("Created default admin user: {}", username);
        }

        Ok(Self {
            tls_config: None, // Will be set separately
            rbac,
            audit_logger,
            auth_service,
        })
    }

    /// Set TLS configuration
    pub fn set_tls_config(&mut self, tls_config: Arc<TlsConfig>) {
        self.tls_config = Some(tls_config);
    }

    /// Get authentication interceptor
    pub fn auth_interceptor(&self) -> AuthInterceptor {
        AuthInterceptor::new(
            self.auth_service.clone(),
            self.rbac.clone(),
            self.audit_logger.clone(),
        )
    }

    /// Shutdown security manager
    pub async fn shutdown(&self) -> Result<(), Box<dyn std::error::Error>> {
        if let Some(logger) = &self.audit_logger {
            logger.shutdown().await?;
        }
        Ok(())
    }
}