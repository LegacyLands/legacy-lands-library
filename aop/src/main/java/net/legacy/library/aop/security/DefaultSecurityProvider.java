package net.legacy.library.aop.security;

import io.fairyproject.container.InjectableComponent;
import net.legacy.library.aop.annotation.SecurityContext;

import java.util.Map;

/**
 * Default implementation of SecurityProvider for testing and simple use cases.
 *
 * <p>This provider uses thread-local storage to maintain security context and
 * provides basic authentication and authorization functionality. It's suitable
 * for testing environments and simple applications that don't require complex
 * security integration.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
public class DefaultSecurityProvider implements SecurityProvider {

    private static final ThreadLocal<SecurityContext> contextHolder = new ThreadLocal<>();

    /**
     * Creates a simple security context for testing.
     *
     * @param username the username
     * @param roles the roles
     * @return security context
     */
    public static SecurityContext createTestContext(String username, String... roles) {
        return new SecurityContext(
                username,
                true,
                roles,
                new String[0],
                "default",
                "test",
                new Object[0],
                Map.of()
        );
    }

    @Override
    public String getName() {
        return "default";
    }

    @Override
    public Object getPrincipal() {
        SecurityContext context = contextHolder.get();
        return context != null ? context.getPrincipal() : null;
    }

    @Override
    public boolean isAuthenticated() {
        SecurityContext context = contextHolder.get();
        return context != null && context.isAuthenticated();
    }

    @Override
    public String[] getRoles() {
        SecurityContext context = contextHolder.get();
        return context != null ? context.getRoles() : new String[0];
    }

    @Override
    public String[] getPermissions() {
        SecurityContext context = contextHolder.get();
        return context != null ? context.getPermissions() : new String[0];
    }

    @Override
    public boolean hasRole(String role) {
        SecurityContext context = contextHolder.get();
        return context != null && context.hasRole(role);
    }

    @Override
    public boolean hasPermission(String permission) {
        SecurityContext context = contextHolder.get();
        return context != null && context.hasPermission(permission);
    }

    @Override
    public void initialize(Map<String, Object> config) {
        // Initialize with default configuration
        // In a real implementation, this would load configuration from the provided map
    }

    @Override
    public void shutdown() {
        contextHolder.remove();
    }

    /**
     * Sets the current security context.
     *
     * @param principal the principal
     * @param roles the roles
     * @param permissions the permissions
     */
    public void setCurrentContext(Object principal, String[] roles, String[] permissions) {
        SecurityContext context = new SecurityContext(
                principal,
                true,
                roles != null ? roles : new String[0],
                permissions != null ? permissions : new String[0],
                getName(),
                "unknown",
                new Object[0],
                Map.of()
        );
        contextHolder.set(context);
    }

    /**
     * Clears the current security context.
     */
    public void clearContext() {
        contextHolder.remove();
    }

    /**
     * Gets the current security context.
     *
     * @return the current security context
     */
    public SecurityContext getCurrentContext() {
        return contextHolder.get();
    }

}