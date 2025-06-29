/// Security module for task scheduler
/// Provides mTLS support, RBAC, and audit logging

pub mod audit;
pub mod mtls;
pub mod rbac;

pub use audit::{AuditEvent, AuditLogger};
pub use mtls::{TlsConfig, TlsConfigBuilder};
pub use rbac::{Permission, Role, RoleBasedAccessControl};