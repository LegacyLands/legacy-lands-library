use dashmap::DashMap;
use serde::{Deserialize, Serialize};
use std::collections::HashSet;
use std::sync::Arc;
use thiserror::Error;
use tracing::{debug, info};

/// RBAC errors
#[derive(Debug, Error)]
pub enum RbacError {
    #[error("Role not found: {0}")]
    RoleNotFound(String),

    #[error("User not found: {0}")]
    UserNotFound(String),

    #[error("Permission denied: {0}")]
    PermissionDenied(String),

    #[error("Invalid configuration: {0}")]
    InvalidConfiguration(String),

    #[error("Circular role inheritance detected")]
    CircularInheritance,
}

pub type RbacResult<T> = Result<T, RbacError>;

/// Permission represents an action that can be performed
#[derive(Debug, Clone, Hash, Eq, PartialEq, Serialize, Deserialize)]
pub struct Permission {
    /// Resource type (e.g., "task", "worker", "cluster")
    pub resource: String,

    /// Action (e.g., "create", "read", "update", "delete", "execute")
    pub action: String,

    /// Optional resource identifier pattern (e.g., "task:*", "worker:123")
    pub resource_id: Option<String>,
}

impl Permission {
    /// Create a new permission
    pub fn new(resource: impl Into<String>, action: impl Into<String>) -> Self {
        Self {
            resource: resource.into(),
            action: action.into(),
            resource_id: None,
        }
    }

    /// Create a permission with resource ID pattern
    pub fn with_resource_id(
        resource: impl Into<String>,
        action: impl Into<String>,
        resource_id: impl Into<String>,
    ) -> Self {
        Self {
            resource: resource.into(),
            action: action.into(),
            resource_id: Some(resource_id.into()),
        }
    }

    /// Check if this permission matches another permission
    pub fn matches(&self, other: &Permission) -> bool {
        // Check resource and action
        if self.resource != other.resource || self.action != other.action {
            return false;
        }

        // Check resource ID pattern
        match (&self.resource_id, &other.resource_id) {
            (None, _) => true, // No pattern means all resources
            (Some(_pattern), None) => false, // Pattern doesn't match all
            (Some(pattern), Some(id)) => {
                // Simple pattern matching (supports * wildcard)
                if pattern == "*" {
                    true
                } else if pattern.ends_with('*') {
                    let prefix = &pattern[..pattern.len() - 1];
                    id.starts_with(prefix)
                } else {
                    pattern == id
                }
            }
        }
    }
}

/// Role represents a set of permissions
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct Role {
    /// Unique role name
    pub name: String,

    /// Role description
    pub description: String,

    /// Set of permissions
    pub permissions: HashSet<Permission>,

    /// Parent roles (for inheritance)
    pub parents: Vec<String>,
}

impl Role {
    /// Create a new role
    pub fn new(name: impl Into<String>, description: impl Into<String>) -> Self {
        Self {
            name: name.into(),
            description: description.into(),
            permissions: HashSet::new(),
            parents: Vec::new(),
        }
    }

    /// Add a permission to the role
    pub fn add_permission(&mut self, permission: Permission) {
        self.permissions.insert(permission);
    }

    /// Add a parent role for inheritance
    pub fn add_parent(&mut self, parent: impl Into<String>) {
        self.parents.push(parent.into());
    }

    /// Check if role has a specific permission
    pub fn has_permission(&self, permission: &Permission) -> bool {
        self.permissions.iter().any(|p| p.matches(permission))
    }
}

/// User represents a subject with assigned roles
#[derive(Debug, Clone, Serialize, Deserialize)]
pub struct User {
    /// Unique user ID
    pub id: String,

    /// User name
    pub name: String,

    /// Assigned role names
    pub roles: HashSet<String>,

    /// User attributes for attribute-based access control
    pub attributes: serde_json::Value,
}

impl User {
    /// Create a new user
    pub fn new(id: impl Into<String>, name: impl Into<String>) -> Self {
        Self {
            id: id.into(),
            name: name.into(),
            roles: HashSet::new(),
            attributes: serde_json::Value::Object(serde_json::Map::new()),
        }
    }

    /// Assign a role to the user
    pub fn assign_role(&mut self, role: impl Into<String>) {
        self.roles.insert(role.into());
    }

    /// Remove a role from the user
    pub fn remove_role(&mut self, role: &str) {
        self.roles.remove(role);
    }
}

/// Role-Based Access Control system
pub struct RoleBasedAccessControl {
    /// Role storage
    roles: Arc<DashMap<String, Role>>,

    /// User storage
    users: Arc<DashMap<String, User>>,

    /// Permission cache for performance
    permission_cache: Arc<DashMap<(String, Permission), bool>>,
}

impl RoleBasedAccessControl {
    /// Create a new RBAC system
    pub fn new() -> Self {
        let rbac = Self {
            roles: Arc::new(DashMap::new()),
            users: Arc::new(DashMap::new()),
            permission_cache: Arc::new(DashMap::new()),
        };

        // Initialize default roles
        rbac.init_default_roles();

        rbac
    }

    /// Initialize default system roles
    fn init_default_roles(&self) {
        // Admin role - full access
        let mut admin = Role::new("admin", "System administrator with full access");
        admin.add_permission(Permission::new("*", "*"));
        self.roles.insert("admin".to_string(), admin);

        // Operator role - manage tasks and workers
        let mut operator = Role::new("operator", "Task operator with management permissions");
        operator.add_permission(Permission::new("task", "create"));
        operator.add_permission(Permission::new("task", "read"));
        operator.add_permission(Permission::new("task", "update"));
        operator.add_permission(Permission::new("task", "delete"));
        operator.add_permission(Permission::new("task", "execute"));
        operator.add_permission(Permission::new("worker", "read"));
        operator.add_permission(Permission::new("worker", "update"));
        self.roles.insert("operator".to_string(), operator);

        // Viewer role - read-only access
        let mut viewer = Role::new("viewer", "Read-only access to resources");
        viewer.add_permission(Permission::new("task", "read"));
        viewer.add_permission(Permission::new("worker", "read"));
        viewer.add_permission(Permission::new("cluster", "read"));
        self.roles.insert("viewer".to_string(), viewer);

        // Executor role - execute tasks only
        let mut executor = Role::new("executor", "Can execute tasks");
        executor.add_permission(Permission::new("task", "execute"));
        executor.add_permission(Permission::new("task", "read"));
        self.roles.insert("executor".to_string(), executor);

        info!("Initialized default RBAC roles");
    }

    /// Create a new role
    pub fn create_role(&self, role: Role) -> RbacResult<()> {
        // Check for circular inheritance
        self.check_circular_inheritance(&role)?;

        self.roles.insert(role.name.clone(), role.clone());
        self.clear_cache();

        info!("Created role: {}", role.name);
        Ok(())
    }

    /// Update an existing role
    pub fn update_role(&self, role: Role) -> RbacResult<()> {
        if !self.roles.contains_key(&role.name) {
            return Err(RbacError::RoleNotFound(role.name.clone()));
        }

        // Check for circular inheritance
        self.check_circular_inheritance(&role)?;

        self.roles.insert(role.name.clone(), role.clone());
        self.clear_cache();

        info!("Updated role: {}", role.name);
        Ok(())
    }

    /// Delete a role
    pub fn delete_role(&self, role_name: &str) -> RbacResult<()> {
        if self.roles.remove(role_name).is_none() {
            return Err(RbacError::RoleNotFound(role_name.to_string()));
        }

        // Remove role from all users
        for mut user in self.users.iter_mut() {
            user.roles.remove(role_name);
        }

        self.clear_cache();

        info!("Deleted role: {}", role_name);
        Ok(())
    }

    /// Get a role by name
    pub fn get_role(&self, role_name: &str) -> Option<Role> {
        self.roles.get(role_name).map(|r| r.clone())
    }

    /// Create or update a user
    pub fn upsert_user(&self, user: User) -> RbacResult<()> {
        // Validate all roles exist
        for role in &user.roles {
            if !self.roles.contains_key(role) {
                return Err(RbacError::RoleNotFound(role.clone()));
            }
        }

        self.users.insert(user.id.clone(), user.clone());
        self.clear_cache();

        info!("Upserted user: {}", user.id);
        Ok(())
    }

    /// Delete a user
    pub fn delete_user(&self, user_id: &str) -> RbacResult<()> {
        if self.users.remove(user_id).is_none() {
            return Err(RbacError::UserNotFound(user_id.to_string()));
        }

        self.clear_cache();

        info!("Deleted user: {}", user_id);
        Ok(())
    }

    /// Get a user by ID
    pub fn get_user(&self, user_id: &str) -> Option<User> {
        self.users.get(user_id).map(|u| u.clone())
    }

    /// Check if a user has a specific permission
    pub fn check_permission(&self, user_id: &str, permission: &Permission) -> RbacResult<bool> {
        // Check cache first
        let cache_key = (user_id.to_string(), permission.clone());
        if let Some(cached) = self.permission_cache.get(&cache_key) {
            return Ok(*cached);
        }

        // Get user
        let user = self
            .users
            .get(user_id)
            .ok_or_else(|| RbacError::UserNotFound(user_id.to_string()))?;

        // Collect all permissions from user's roles (with inheritance)
        let mut checked_roles = HashSet::new();
        let mut has_permission = false;

        for role_name in &user.roles {
            if self.check_role_permission(role_name, permission, &mut checked_roles)? {
                has_permission = true;
                break;
            }
        }

        // Cache the result
        self.permission_cache.insert(cache_key, has_permission);

        debug!(
            "Permission check for user {} on {:?}: {}",
            user_id, permission, has_permission
        );

        Ok(has_permission)
    }

    /// Recursively check role permissions with inheritance
    fn check_role_permission(
        &self,
        role_name: &str,
        permission: &Permission,
        checked_roles: &mut HashSet<String>,
    ) -> RbacResult<bool> {
        // Avoid infinite recursion
        if checked_roles.contains(role_name) {
            return Ok(false);
        }
        checked_roles.insert(role_name.to_string());

        // Get role
        let role = self
            .roles
            .get(role_name)
            .ok_or_else(|| RbacError::RoleNotFound(role_name.to_string()))?;

        // Check direct permissions
        if role.has_permission(permission) {
            return Ok(true);
        }

        // Check parent roles
        for parent in &role.parents {
            if self.check_role_permission(parent, permission, checked_roles)? {
                return Ok(true);
            }
        }

        Ok(false)
    }

    /// Check for circular inheritance in roles
    fn check_circular_inheritance(&self, role: &Role) -> RbacResult<()> {
        let mut visited = HashSet::new();
        let mut stack = HashSet::new();

        self.dfs_check_circular(&role.name, &mut visited, &mut stack)?;

        Ok(())
    }

    /// DFS helper for circular inheritance check
    fn dfs_check_circular(
        &self,
        role_name: &str,
        visited: &mut HashSet<String>,
        stack: &mut HashSet<String>,
    ) -> RbacResult<()> {
        if stack.contains(role_name) {
            return Err(RbacError::CircularInheritance);
        }

        if visited.contains(role_name) {
            return Ok(());
        }

        visited.insert(role_name.to_string());
        stack.insert(role_name.to_string());

        if let Some(role) = self.roles.get(role_name) {
            for parent in &role.parents {
                self.dfs_check_circular(parent, visited, stack)?;
            }
        }

        stack.remove(role_name);

        Ok(())
    }

    /// Clear permission cache
    fn clear_cache(&self) {
        self.permission_cache.clear();
    }

    /// Enforce a permission check, returning error if denied
    pub fn enforce(&self, user_id: &str, permission: &Permission) -> RbacResult<()> {
        if !self.check_permission(user_id, permission)? {
            return Err(RbacError::PermissionDenied(format!(
                "User {} does not have permission to {} on {}",
                user_id, permission.action, permission.resource
            )));
        }
        Ok(())
    }

    /// List all roles
    pub fn list_roles(&self) -> Vec<Role> {
        self.roles.iter().map(|r| r.clone()).collect()
    }

    /// List all users
    pub fn list_users(&self) -> Vec<User> {
        self.users.iter().map(|u| u.clone()).collect()
    }
}

impl Default for RoleBasedAccessControl {
    fn default() -> Self {
        Self::new()
    }
}

/// Helper macro for creating permissions
#[macro_export]
macro_rules! permission {
    ($resource:expr, $action:expr) => {
        Permission::new($resource, $action)
    };
    ($resource:expr, $action:expr, $id:expr) => {
        Permission::with_resource_id($resource, $action, $id)
    };
}

#[cfg(test)]
mod tests {
    use super::*;

    #[test]
    fn test_permission_matching() {
        let perm1 = Permission::new("task", "read");
        let perm2 = Permission::new("task", "read");
        assert!(perm1.matches(&perm2));

        let perm3 = Permission::with_resource_id("task", "read", "task:123");
        let perm4 = Permission::with_resource_id("task", "read", "task:*");
        assert!(perm4.matches(&perm3));

        let perm5 = Permission::with_resource_id("task", "read", "task:prod-*");
        let perm6 = Permission::with_resource_id("task", "read", "task:prod-123");
        assert!(perm5.matches(&perm6));
    }

    #[test]
    fn test_rbac_basic_flow() {
        let rbac = RoleBasedAccessControl::new();

        // Create a user
        let mut user = User::new("user1", "Test User");
        user.assign_role("operator");

        rbac.upsert_user(user).unwrap();

        // Check permissions
        let perm = Permission::new("task", "create");
        assert!(rbac.check_permission("user1", &perm).unwrap());

        let perm2 = Permission::new("cluster", "delete");
        assert!(!rbac.check_permission("user1", &perm2).unwrap());
    }

    #[test]
    fn test_role_inheritance() {
        let rbac = RoleBasedAccessControl::new();

        // Create a parent role
        let mut parent = Role::new("task-manager", "Can manage tasks");
        parent.add_permission(Permission::new("task", "create"));
        parent.add_permission(Permission::new("task", "read"));
        rbac.create_role(parent).unwrap();

        // Create a child role
        let mut child = Role::new("advanced-operator", "Advanced operator");
        child.add_parent("task-manager");
        child.add_permission(Permission::new("worker", "manage"));
        rbac.create_role(child).unwrap();

        // Create user with child role
        let mut user = User::new("user2", "Test User 2");
        user.assign_role("advanced-operator");
        rbac.upsert_user(user).unwrap();

        // Check inherited permissions
        let perm = Permission::new("task", "create");
        assert!(rbac.check_permission("user2", &perm).unwrap());
    }
}