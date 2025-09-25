package net.legacy.library.aop.security;

/**
 * Interface for security providers.
 *
 * <p>This interface defines the contract for security providers that handle
 * authentication and authorization. Implementations can integrate with various
 * security frameworks such as Spring Security, Apache Shiro, or custom solutions.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface SecurityProvider {

    /**
     * Gets the name of this security provider.
     *
     * @return provider name
     */
    String getName();

    /**
     * Gets the currently authenticated principal.
     *
     * @return the principal, or null if not authenticated
     */
    Object getPrincipal();

    /**
     * Checks if the current context is authenticated.
     *
     * @return true if authenticated
     */
    boolean isAuthenticated();

    /**
     * Gets the roles assigned to the current principal.
     *
     * @return array of role names
     */
    String[] getRoles();

    /**
     * Gets the permissions assigned to the current principal.
     *
     * @return array of permission names
     */
    String[] getPermissions();

    /**
     * Checks if the current principal has the specified role.
     *
     * @param role the role to check
     * @return true if the principal has the role
     */
    boolean hasRole(String role);

    /**
     * Checks if the current principal has the specified permission.
     *
     * @param permission the permission to check
     * @return true if the principal has the permission
     */
    boolean hasPermission(String permission);

    /**
     * Initializes the security provider.
     *
     * @param config the configuration for the provider
     */
    void initialize(java.util.Map<String, Object> config);

    /**
     * Shuts down the security provider.
     */
    void shutdown();

}