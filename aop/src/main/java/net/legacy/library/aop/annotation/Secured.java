package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for securing method execution with authentication and authorization.
 *
 * <p>This annotation provides comprehensive security controls including authentication
 * requirements, role-based authorization, permission checking, and audit logging.
 * It supports various security providers and custom authentication strategies.
 *
 * <p>Usage examples:
 * <pre>
 * {@code
 * @Secured(
 *     authenticated = true,
 *     roles = {"ADMIN", "MANAGER"},
 *     permissions = {"user:create", "user:update"}
 * )
 * public void createUser(User user) {
 *     // Method implementation
 * }
 *
 * @Secured(
 *     authenticated = true,
 *     provider = "oauth2",
 *     audit = true
 * )
 * public void deleteAccount(String accountId) {
 *     // Method implementation
 * }
 * }
 * </pre>
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target(ElementType.METHOD)
@Retention(RetentionPolicy.RUNTIME)
public @interface Secured {

    /**
     * Whether authentication is required for this method.
     *
     * @return true if authentication is required
     */
    boolean authenticated() default true;

    /**
     * Roles required to access this method.
     *
     * @return array of required role names
     */
    String[] roles() default {};

    /**
     * Permissions required to access this method.
     *
     * @return array of required permission names
     */
    String[] permissions() default {};

    /**
     * Security provider to use for authentication and authorization.
     *
     * @return security provider name
     */
    String provider() default "default";

    /**
     * Whether to audit this method call.
     *
     * @return true if audit logging is enabled
     */
    boolean audit() default false;

    /**
     * Whether to enable rate limiting for this method.
     *
     * @return true if rate limiting is enabled
     */
    boolean rateLimited() default false;

    /**
     * Maximum number of requests per time window when rate limited.
     *
     * @return maximum requests per window
     */
    int maxRequests() default 100;

    /**
     * Time window in seconds for rate limiting.
     *
     * @return time window in seconds
     */
    int timeWindow() default 60;

    /**
     * Whether to enable CSRF protection.
     *
     * @return true if CSRF protection is enabled
     */
    boolean csrfProtected() default false;

    /**
     * Whether to validate input parameters.
     *
     * @return true if input validation is enabled
     */
    boolean validateInput() default true;

    /**
     * Custom security policy to apply.
     *
     * @return security policy class
     */
    Class<? extends SecurityPolicy> policy() default SecurityPolicy.class;

    /**
     * Exception to throw when authorization fails.
     *
     * @return authorization exception class
     */
    Class<? extends Throwable> onAuthorizationFailure() default SecurityException.class;

}