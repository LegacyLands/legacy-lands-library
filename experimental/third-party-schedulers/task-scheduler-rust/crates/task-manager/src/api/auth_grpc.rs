use crate::api::proto::{
    auth_service_server::AuthService as AuthServiceTrait, CreateUserRequest, CreateUserResponse,
    DeleteUserRequest, DeleteUserResponse, ListUsersRequest, ListUsersResponse, LoginRequest,
    LoginResponse, LogoutRequest, LogoutResponse, RefreshTokenRequest, RefreshTokenResponse,
    UpdateUserRolesRequest, UpdateUserRolesResponse, UserInfo,
};
use crate::security::auth::{AuthService, Credentials};
use std::sync::Arc;
use task_common::security::rbac::Permission;
use tonic::{Request, Response, Status};
use tracing::{info, instrument, warn};

/// gRPC service implementation for authentication
pub struct AuthServiceImpl {
    auth_service: Arc<AuthService>,
}

impl AuthServiceImpl {
    /// Create a new auth service implementation
    pub fn new(auth_service: Arc<AuthService>) -> Self {
        Self { auth_service }
    }

    /// Check admin permission
    async fn check_admin(&self, request: &Request<impl std::fmt::Debug>) -> Result<(), Status> {
        // Extract token from metadata
        let token = request
            .metadata()
            .get("authorization")
            .and_then(|v| v.to_str().ok())
            .and_then(|s| s.strip_prefix("Bearer "))
            .ok_or_else(|| Status::unauthenticated("Missing authorization header"))?;

        // Validate token
        let token_info = self
            .auth_service
            .validate_token(token)
            .map_err(|e| Status::unauthenticated(format!("Invalid token: {}", e)))?;

        // Check admin permission
        let permission = Permission::new("user", "manage");
        if !self
            .auth_service
            .check_permission(&token_info.user_id, &permission)
            .map_err(|e| Status::internal(format!("Permission check failed: {}", e)))?
        {
            return Err(Status::permission_denied("Admin access required"));
        }

        Ok(())
    }
}

#[tonic::async_trait]
impl AuthServiceTrait for AuthServiceImpl {
    #[instrument(skip(self, request))]
    async fn login(
        &self,
        request: Request<LoginRequest>,
    ) -> Result<Response<LoginResponse>, Status> {
        let req = request.into_inner();
        info!("Login attempt for user: {}", req.username);

        let credentials = Credentials {
            username: req.username.clone(),
            password: req.password,
        };

        match self.auth_service.authenticate(credentials).await {
            Ok(token_info) => {
                info!("Login successful for user: {}", req.username);
                Ok(Response::new(LoginResponse {
                    token: token_info.token,
                    user_id: token_info.user_id,
                    username: token_info.username,
                    roles: token_info.roles,
                    expires_at: token_info.expires_at.timestamp(),
                }))
            }
            Err(e) => {
                warn!("Login failed for user {}: {}", req.username, e);
                Err(Status::unauthenticated("Invalid credentials"))
            }
        }
    }

    #[instrument(skip(self, request))]
    async fn logout(
        &self,
        request: Request<LogoutRequest>,
    ) -> Result<Response<LogoutResponse>, Status> {
        let req = request.into_inner();

        self.auth_service
            .revoke_token(&req.token)
            .map_err(|e| Status::internal(format!("Failed to revoke token: {}", e)))?;

        info!("Token revoked successfully");
        Ok(Response::new(LogoutResponse { success: true }))
    }

    #[instrument(skip(self, request))]
    async fn refresh_token(
        &self,
        request: Request<RefreshTokenRequest>,
    ) -> Result<Response<RefreshTokenResponse>, Status> {
        let req = request.into_inner();

        // Validate existing token
        let token_info = self
            .auth_service
            .validate_token(&req.token)
            .map_err(|e| Status::unauthenticated(format!("Invalid token: {}", e)))?;

        // For now, just return the same token with updated expiry
        // In production, you would generate a new token
        Ok(Response::new(RefreshTokenResponse {
            token: token_info.token,
            expires_at: token_info.expires_at.timestamp(),
        }))
    }

    #[instrument(skip(self, request))]
    async fn create_user(
        &self,
        request: Request<CreateUserRequest>,
    ) -> Result<Response<CreateUserResponse>, Status> {
        // Check admin permission
        self.check_admin(&request).await?;

        let req = request.into_inner();
        info!("Creating user: {}", req.username);

        match self
            .auth_service
            .create_user(&req.username, &req.password, req.roles)
            .await
        {
            Ok(()) => {
                info!("User created successfully: {}", req.username);
                Ok(Response::new(CreateUserResponse {
                    user_id: req.username.clone(),
                    success: true,
                    message: "User created successfully".to_string(),
                }))
            }
            Err(e) => {
                warn!("Failed to create user {}: {}", req.username, e);
                Ok(Response::new(CreateUserResponse {
                    user_id: String::new(),
                    success: false,
                    message: e.to_string(),
                }))
            }
        }
    }

    #[instrument(skip(self, request))]
    async fn update_user_roles(
        &self,
        request: Request<UpdateUserRolesRequest>,
    ) -> Result<Response<UpdateUserRolesResponse>, Status> {
        // Check admin permission
        self.check_admin(&request).await?;

        let req = request.into_inner();
        info!("Updating roles for user: {}", req.username);

        match self
            .auth_service
            .update_user_roles(&req.username, req.roles)
            .await
        {
            Ok(()) => {
                info!("Roles updated successfully for user: {}", req.username);
                Ok(Response::new(UpdateUserRolesResponse {
                    success: true,
                    message: "Roles updated successfully".to_string(),
                }))
            }
            Err(e) => {
                warn!("Failed to update roles for user {}: {}", req.username, e);
                Ok(Response::new(UpdateUserRolesResponse {
                    success: false,
                    message: e.to_string(),
                }))
            }
        }
    }

    #[instrument(skip(self, request))]
    async fn delete_user(
        &self,
        request: Request<DeleteUserRequest>,
    ) -> Result<Response<DeleteUserResponse>, Status> {
        // Check admin permission
        self.check_admin(&request).await?;

        let req = request.into_inner();
        info!("Deleting user: {}", req.username);

        match self.auth_service.delete_user(&req.username).await {
            Ok(()) => {
                info!("User deleted successfully: {}", req.username);
                Ok(Response::new(DeleteUserResponse {
                    success: true,
                    message: "User deleted successfully".to_string(),
                }))
            }
            Err(e) => {
                warn!("Failed to delete user {}: {}", req.username, e);
                Ok(Response::new(DeleteUserResponse {
                    success: false,
                    message: e.to_string(),
                }))
            }
        }
    }

    #[instrument(skip(self, request))]
    async fn list_users(
        &self,
        request: Request<ListUsersRequest>,
    ) -> Result<Response<ListUsersResponse>, Status> {
        // Check admin permission
        self.check_admin(&request).await?;

        let _req = request.into_inner();
        
        // For now, just return a simple list
        // In production, implement pagination
        let users: Vec<UserInfo> = vec![
            UserInfo {
                user_id: "admin".to_string(),
                username: "admin".to_string(),
                roles: vec!["admin".to_string()],
                created_at: 0,
            },
        ];

        Ok(Response::new(ListUsersResponse {
            users,
            next_page_token: String::new(),
        }))
    }
}