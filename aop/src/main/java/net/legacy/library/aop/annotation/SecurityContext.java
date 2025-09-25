package net.legacy.library.aop.annotation;

import lombok.AccessLevel;
import lombok.Getter;

import java.util.Map;

/**
 * Context object containing security information for method execution.
 *
 * <p>This class encapsulates all security-related information including
 * authentication details, roles, permissions, and method context.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class SecurityContext {

    private final Object principal;
    private final boolean authenticated;
    
    @Getter(AccessLevel.NONE)
    private final String[] roles;
    
    @Getter(AccessLevel.NONE)
    private final String[] permissions;
    private final String provider;
    private final String methodName;
    
    @Getter(AccessLevel.NONE)
    private final Object[] arguments;
    private final Map<String, Object> attributes;

    public SecurityContext(Object principal, boolean authenticated, String[] roles, String[] permissions,
                           String provider, String methodName, Object[] arguments, Map<String, Object> attributes) {
        this.principal = principal;
        this.authenticated = authenticated;
        this.roles = roles != null ? roles.clone() : new String[0];
        this.permissions = permissions != null ? permissions.clone() : new String[0];
        this.provider = provider;
        this.methodName = methodName;
        this.arguments = arguments != null ? arguments.clone() : new Object[0];
        this.attributes = attributes;
    }

    /**
     * Gets the roles assigned to the principal.
     *
     * @return array of role names
     */
    public String[] getRoles() {
        return roles.clone();
    }

    /**
     * Checks if the principal has the specified role.
     *
     * @param role the role to check
     * @return true if the principal has the role
     */
    public boolean hasRole(String role) {
        if (roles == null) {
            return false;
        }
        for (String r : roles) {
            if (r.equals(role)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the permissions assigned to the principal.
     *
     * @return array of permission names
     */
    public String[] getPermissions() {
        return permissions.clone();
    }

    /**
     * Checks if the principal has the specified permission.
     *
     * @param permission the permission to check
     * @return true if the principal has the permission
     */
    public boolean hasPermission(String permission) {
        if (permissions == null) {
            return false;
        }
        for (String p : permissions) {
            if (p.equals(permission)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Gets the method arguments.
     *
     * @return array of method arguments
     */
    public Object[] getArguments() {
        return arguments.clone();
    }

    /**
     * Gets an attribute value.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not found
     */
    public Object getAttribute(String key) {
        return attributes != null ? attributes.get(key) : null;
    }

}
