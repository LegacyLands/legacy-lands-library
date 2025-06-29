use std::sync::Arc;
use task_common::security::{
    audit::{AuditCategory, AuditEventBuilder, AuditLogger},
    rbac::{Permission, RoleBasedAccessControl},
};
use tonic::{metadata::MetadataMap, Request, Status};
use tracing::warn;

use super::auth::AuthService;

/// Security context extracted from request
#[derive(Debug, Clone)]
pub struct SecurityContext {
    pub user_id: String,
    pub username: String,
    pub roles: Vec<String>,
    pub token: String,
    pub source_ip: Option<String>,
}

/// Authentication interceptor for gRPC
#[derive(Clone)]
pub struct AuthInterceptor {
    auth_service: Arc<AuthService>,
    rbac: Arc<RoleBasedAccessControl>,
    audit_logger: Option<Arc<AuditLogger>>,
}

impl AuthInterceptor {
    /// Create a new auth interceptor
    pub fn new(
        auth_service: Arc<AuthService>,
        rbac: Arc<RoleBasedAccessControl>,
        audit_logger: Option<Arc<AuditLogger>>,
    ) -> Self {
        Self {
            auth_service,
            rbac,
            audit_logger,
        }
    }

    /// Extract security context from request
    pub fn extract_context<T>(&self, request: &Request<T>) -> Result<SecurityContext, Status> {
        // Extract token from authorization header
        let token = self.extract_token(request.metadata())?;

        // Validate token
        let token_info = self
            .auth_service
            .validate_token(&token)
            .map_err(|e| Status::unauthenticated(format!("Authentication failed: {}", e)))?;

        // Extract source IP from request
        let source_ip = self.extract_source_ip(request);

        Ok(SecurityContext {
            user_id: token_info.user_id,
            username: token_info.username,
            roles: token_info.roles,
            token,
            source_ip,
        })
    }

    /// Check if user has required permission
    pub async fn check_permission(
        &self,
        context: &SecurityContext,
        resource: &str,
        action: &str,
        resource_id: Option<&str>,
    ) -> Result<(), Status> {
        let permission = if let Some(id) = resource_id {
            Permission::with_resource_id(resource, action, id)
        } else {
            Permission::new(resource, action)
        };

        match self.rbac.check_permission(&context.user_id, &permission) {
            Ok(true) => {
                // Log successful authorization
                if let Some(logger) = &self.audit_logger {
                    let _ = logger
                        .log(
                            AuditEventBuilder::success(
                                AuditCategory::Authorization,
                                &context.username,
                                resource,
                                action,
                            )
                            .source_ip(context.source_ip.clone().unwrap_or_default())
                            .build(),
                        )
                        .await;
                }
                Ok(())
            }
            Ok(false) => {
                // Log authorization failure
                if let Some(logger) = &self.audit_logger {
                    let _ = logger
                        .log_security_violation(
                            &context.username,
                            resource,
                            action,
                            "Permission denied",
                        )
                        .await;
                }
                Err(Status::permission_denied(format!(
                    "Permission denied: {} on {}",
                    action, resource
                )))
            }
            Err(e) => {
                warn!("RBAC check error: {}", e);
                Err(Status::internal("Authorization check failed"))
            }
        }
    }

    /// Authenticate request (for use in interceptor)
    pub async fn authenticate<T>(&self, mut request: Request<T>) -> Result<Request<T>, Status> {
        // Extract security context
        let context = self.extract_context(&request)?;

        // Log authentication success
        if let Some(logger) = &self.audit_logger {
            let _ = logger
                .log(
                    AuditEventBuilder::success(
                        AuditCategory::Authentication,
                        &context.username,
                        "grpc",
                        "authenticate",
                    )
                    .source_ip(context.source_ip.clone().unwrap_or_default())
                    .build(),
                )
                .await;
        }

        // Add security context to request extensions
        request.extensions_mut().insert(context);

        Ok(request)
    }

    /// Extract token from metadata
    fn extract_token(&self, metadata: &MetadataMap) -> Result<String, Status> {
        // Try to get authorization header
        let auth_header = metadata
            .get("authorization")
            .ok_or_else(|| Status::unauthenticated("Missing authorization header"))?;

        let auth_str = auth_header
            .to_str()
            .map_err(|_| Status::unauthenticated("Invalid authorization header"))?;

        // Extract bearer token
        if auth_str.starts_with("Bearer ") {
            Ok(auth_str[7..].to_string())
        } else {
            Err(Status::unauthenticated("Invalid authorization format"))
        }
    }

    /// Extract source IP from request
    fn extract_source_ip<T>(&self, request: &Request<T>) -> Option<String> {
        // Try to get from x-forwarded-for header
        if let Some(forwarded) = request.metadata().get("x-forwarded-for") {
            if let Ok(forwarded_str) = forwarded.to_str() {
                // Take the first IP from the list
                return forwarded_str.split(',').next().map(|s| s.trim().to_string());
            }
        }

        // Try to get from x-real-ip header
        if let Some(real_ip) = request.metadata().get("x-real-ip") {
            if let Ok(ip_str) = real_ip.to_str() {
                return Some(ip_str.to_string());
            }
        }

        // Try to get from remote address
        request
            .remote_addr()
            .map(|addr| addr.ip().to_string())
    }
}

/// Helper function to get security context from request extensions
pub fn get_security_context<T>(request: &Request<T>) -> Result<SecurityContext, Status> {
    request
        .extensions()
        .get::<SecurityContext>()
        .cloned()
        .ok_or_else(|| Status::internal("Security context not found"))
}

/// Macro to check permission in service methods
#[macro_export]
macro_rules! check_permission {
    ($interceptor:expr, $request:expr, $resource:expr, $action:expr) => {{
        let context = $crate::security::interceptor::get_security_context($request)?;
        $interceptor
            .check_permission(&context, $resource, $action, None)
            .await?;
        context
    }};
    ($interceptor:expr, $request:expr, $resource:expr, $action:expr, $resource_id:expr) => {{
        let context = $crate::security::interceptor::get_security_context($request)?;
        $interceptor
            .check_permission(&context, $resource, $action, Some($resource_id))
            .await?;
        context
    }};
}

#[cfg(test)]
mod tests {
    use super::*;
    use tonic::metadata::MetadataValue;
    use std::str::FromStr;

    #[tokio::test]
    async fn test_token_extraction() {
        let rbac = Arc::new(RoleBasedAccessControl::new());
        let auth = Arc::new(AuthService::new(None, 3600, rbac.clone()).unwrap());
        let interceptor = AuthInterceptor::new(auth.clone(), rbac, None);

        // Create a request with authorization header
        let mut request = Request::new(());
        request.metadata_mut().insert(
            "authorization",
            MetadataValue::from_str("Bearer test-token").unwrap(),
        );

        // Extract token should work
        let token = interceptor.extract_token(request.metadata()).unwrap();
        assert_eq!(token, "test-token");
    }
}