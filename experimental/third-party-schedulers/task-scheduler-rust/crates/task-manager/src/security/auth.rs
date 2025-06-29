use chrono::{DateTime, Duration, Utc};
use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::sync::Arc;
use task_common::security::rbac::{Permission, RoleBasedAccessControl, User};
use thiserror::Error;
use uuid::Uuid;

/// Authentication errors
#[derive(Debug, Error)]
pub enum AuthError {
    #[error("Invalid credentials")]
    InvalidCredentials,

    #[error("Token expired")]
    TokenExpired,

    #[error("Token not found")]
    TokenNotFound,

    #[error("Invalid token")]
    InvalidToken,

    #[error("User already exists")]
    UserAlreadyExists,

    #[error("User not found")]
    UserNotFound,

    #[error("Internal error: {0}")]
    InternalError(String),
}

pub type AuthResult<T> = Result<T, AuthError>;

/// Token information
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct TokenInfo {
    pub token: String,
    pub user_id: String,
    pub username: String,
    pub roles: Vec<String>,
    pub issued_at: DateTime<Utc>,
    pub expires_at: DateTime<Utc>,
}

/// User credentials
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Credentials {
    pub username: String,
    pub password: String,
}

/// User session
#[derive(Debug, Clone)]
struct Session {
    pub user_id: String,
    pub username: String,
    pub roles: Vec<String>,
    pub expires_at: DateTime<Utc>,
}

/// Authentication service
pub struct AuthService {
    /// Session storage (token -> session)
    sessions: Arc<DashMap<String, Session>>,

    /// User storage (username -> password hash)
    /// In production, this should be persisted in a database
    users: Arc<DashMap<String, String>>,

    /// JWT secret (optional, for JWT-based auth)
    _jwt_secret: Option<String>,

    /// Session timeout duration
    session_timeout: Duration,

    /// RBAC system reference
    rbac: Arc<RoleBasedAccessControl>,
}

impl AuthService {
    /// Create a new authentication service
    pub fn new(
        jwt_secret: Option<String>,
        session_timeout_secs: u64,
        rbac: Arc<RoleBasedAccessControl>,
    ) -> AuthResult<Self> {
        Ok(Self {
            sessions: Arc::new(DashMap::new()),
            users: Arc::new(DashMap::new()),
            _jwt_secret: jwt_secret,
            session_timeout: Duration::seconds(session_timeout_secs as i64),
            rbac,
        })
    }

    /// Create default admin user
    pub async fn create_default_admin(&self, username: &str, password: &str) -> AuthResult<()> {
        // Check if user already exists
        if self.users.contains_key(username) {
            return Ok(()); // Already exists, skip
        }

        // Hash password (in production, use proper password hashing like argon2)
        let password_hash = self.hash_password(password);

        // Store user
        self.users.insert(username.to_string(), password_hash);

        // Create RBAC user with admin role
        let mut user = User::new(username, username);
        user.assign_role("admin");

        self.rbac
            .upsert_user(user)
            .map_err(|e| AuthError::InternalError(e.to_string()))?;

        Ok(())
    }

    /// Authenticate user and create session
    pub async fn authenticate(&self, credentials: Credentials) -> AuthResult<TokenInfo> {
        // Get user password hash
        let password_hash = self
            .users
            .get(&credentials.username)
            .ok_or(AuthError::InvalidCredentials)?
            .clone();

        // Verify password
        if !self.verify_password(&credentials.password, &password_hash) {
            return Err(AuthError::InvalidCredentials);
        }

        // Get user from RBAC
        let user = self
            .rbac
            .get_user(&credentials.username)
            .ok_or(AuthError::UserNotFound)?;

        // Create session token
        let token = self.generate_token();
        let now = Utc::now();
        let expires_at = now + self.session_timeout;

        // Store session
        let roles: Vec<String> = user.roles.clone().into_iter().collect();
        let session = Session {
            user_id: user.id.clone(),
            username: user.name.clone(),
            roles: roles.clone(),
            expires_at,
        };
        self.sessions.insert(token.clone(), session);

        // Return token info
        Ok(TokenInfo {
            token: token.clone(),
            user_id: user.id,
            username: user.name,
            roles,
            issued_at: now,
            expires_at,
        })
    }

    /// Validate token and get session
    pub fn validate_token(&self, token: &str) -> AuthResult<TokenInfo> {
        // Get session
        let session = self
            .sessions
            .get(token)
            .ok_or(AuthError::TokenNotFound)?;

        // Check expiration
        if session.expires_at < Utc::now() {
            self.sessions.remove(token);
            return Err(AuthError::TokenExpired);
        }

        // Return token info
        Ok(TokenInfo {
            token: token.to_string(),
            user_id: session.user_id.clone(),
            username: session.username.clone(),
            roles: session.roles.clone(),
            issued_at: session.expires_at - self.session_timeout,
            expires_at: session.expires_at,
        })
    }

    /// Revoke token
    pub fn revoke_token(&self, token: &str) -> AuthResult<()> {
        self.sessions.remove(token);
        Ok(())
    }

    /// Check if user has permission
    pub fn check_permission(&self, user_id: &str, permission: &Permission) -> AuthResult<bool> {
        self.rbac
            .check_permission(user_id, permission)
            .map_err(|e| AuthError::InternalError(e.to_string()))
    }

    /// Create a new user
    pub async fn create_user(&self, username: &str, password: &str, roles: Vec<String>) -> AuthResult<()> {
        // Check if user already exists
        if self.users.contains_key(username) {
            return Err(AuthError::UserAlreadyExists);
        }

        // Hash password
        let password_hash = self.hash_password(password);

        // Store user
        self.users.insert(username.to_string(), password_hash);

        // Create RBAC user
        let mut user = User::new(username, username);
        for role in roles {
            user.assign_role(role);
        }

        self.rbac
            .upsert_user(user)
            .map_err(|e| AuthError::InternalError(e.to_string()))?;

        Ok(())
    }

    /// Update user roles
    pub async fn update_user_roles(&self, username: &str, roles: Vec<String>) -> AuthResult<()> {
        // Get existing user
        let mut user = self
            .rbac
            .get_user(username)
            .ok_or(AuthError::UserNotFound)?;

        // Update roles
        user.roles.clear();
        for role in roles {
            user.assign_role(role);
        }

        self.rbac
            .upsert_user(user)
            .map_err(|e| AuthError::InternalError(e.to_string()))?;

        Ok(())
    }

    /// Delete user
    pub async fn delete_user(&self, username: &str) -> AuthResult<()> {
        // Remove from users
        self.users.remove(username);

        // Remove from RBAC
        self.rbac
            .delete_user(username)
            .map_err(|e| AuthError::InternalError(e.to_string()))?;

        // Revoke all sessions for this user
        let tokens_to_remove: Vec<String> = self
            .sessions
            .iter()
            .filter(|entry| entry.value().username == username)
            .map(|entry| entry.key().clone())
            .collect();

        for token in tokens_to_remove {
            self.sessions.remove(&token);
        }

        Ok(())
    }

    /// Generate a new token
    fn generate_token(&self) -> String {
        // For now, use UUID. In production, consider JWT
        Uuid::new_v4().to_string()
    }

    /// Hash password (simplified for demo - use argon2 in production)
    fn hash_password(&self, password: &str) -> String {
        // In production, use proper password hashing like argon2
        format!("hashed_{}", password)
    }

    /// Verify password
    fn verify_password(&self, password: &str, hash: &str) -> bool {
        // In production, use proper password verification
        hash == &format!("hashed_{}", password)
    }

    /// Clean up expired sessions
    pub async fn cleanup_expired_sessions(&self) {
        let now = Utc::now();
        let expired_tokens: Vec<String> = self
            .sessions
            .iter()
            .filter(|entry| entry.value().expires_at < now)
            .map(|entry| entry.key().clone())
            .collect();

        for token in expired_tokens {
            self.sessions.remove(&token);
        }
    }
}

#[cfg(test)]
mod tests {
    use super::*;

    #[tokio::test]
    async fn test_auth_flow() {
        let rbac = Arc::new(RoleBasedAccessControl::new());
        let auth = AuthService::new(None, 3600, rbac).unwrap();

        // Create admin user
        auth.create_default_admin("admin", "admin123").await.unwrap();

        // Authenticate
        let creds = Credentials {
            username: "admin".to_string(),
            password: "admin123".to_string(),
        };
        let token_info = auth.authenticate(creds).await.unwrap();

        // Validate token
        let validated = auth.validate_token(&token_info.token).unwrap();
        assert_eq!(validated.username, "admin");
        assert!(validated.roles.contains(&"admin".to_string()));

        // Revoke token
        auth.revoke_token(&token_info.token).unwrap();

        // Should fail to validate revoked token
        assert!(auth.validate_token(&token_info.token).is_err());
    }
}