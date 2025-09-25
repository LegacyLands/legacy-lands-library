package net.legacy.library.aop.annotation;

/**
 * Interface for custom security policy implementations.
 *
 * <p>This interface allows users to define custom security policies
 * that can be applied to secured methods. Policies can implement
 * complex security logic beyond simple role and permission checks.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface SecurityPolicy {

    /**
     * Checks if the current security context is authorized to execute the method.
     *
     * @param context the security context containing authentication and authorization information
     * @return true if authorized, false otherwise
     */
    boolean isAuthorized(SecurityContext context);

    /**
     * Gets the error message to use when authorization fails.
     *
     * @return error message
     */
    String getErrorMessage();

}