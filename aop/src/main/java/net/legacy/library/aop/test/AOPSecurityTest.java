package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.Secured;
import net.legacy.library.aop.annotation.SecurityContext;
import net.legacy.library.aop.annotation.SecurityPolicy;
import net.legacy.library.aop.aspect.SecurityAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.security.SecurityProviderRegistry;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for security functionality in enterprise environments.
 *
 * <p>This test class focuses on the {@code @Secured} annotation and related
 * security capabilities. Tests verify authentication, authorization, role-based
 * access control, permission checking, and security policy enforcement.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-security-test",
        description = "Tests security functionality including authentication and authorization",
        tags = {"aop", "security", "authentication", "authorization", "enterprise"},
        priority = 4,
        timeout = 25000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPSecurityTest {

    /**
     * Tests successful authentication and authorization.
     */
    public static boolean testSuccessfulAuthorization() {
        try {
            TestLogger.logInfo("aop", "Starting successful authorization test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(provider);

            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            // Set up security context for successful authorization
            provider.setCurrentContext("test-user", new String[]{"USER", "ADMIN", "MANAGER"}, new String[]{"user:read"});

            String result = service.readUser("user-123");

            boolean resultValid = result != null && result.contains("user-123") && result.contains("User data read");

            TestLogger.logInfo("aop", "Successful authorization test: resultValid=%s, result=%s", resultValid, result);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Successful authorization test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests public access without authentication.
     */
    public static boolean testPublicAccess() {
        try {
            TestLogger.logInfo("aop", "Starting public access test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(provider);

            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            String result = service.publicInfo();

            boolean resultValid = result != null && result.contains("Public information");

            TestLogger.logInfo("aop", "Public access test: resultValid=%s, result=%s", resultValid, result);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Public access test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests authentication failure scenarios.
     */
    public static boolean testAuthenticationFailure() {
        try {
            TestLogger.logInfo("aop", "Starting authentication failure test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider defaultProvider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(defaultProvider);

            // Create a custom security provider that always returns unauthenticated
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider() {
                        @Override
                        public boolean isAuthenticated() {
                            // Force unauthenticated state for this test
                            return false;
                        }

                        @Override
                        public Object getPrincipal() {
                            // Return null to indicate no authentication
                            return null;
                        }

                        @Override
                        public String[] getRoles() {
                            return new String[0];
                        }

                        @Override
                        public String[] getPermissions() {
                            return new String[0];
                        }
                    };

            // Ensure context is cleared and set unauthenticated state
            provider.clearContext();
            provider.setCurrentContext(null, new String[0], new String[0]);

            // Verify authentication state
            TestLogger.logInfo("aop", "Authentication failure test: provider authentication state = %s", provider.isAuthenticated());

            securityProviderRegistry.registerProvider(provider);

            // Create security aspect with local registry
            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the security test class
            aopService.registerTestInterceptors(TestSecureService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            try {
                // This should fail due to authentication requirements
                service.createUser("test-user");
                TestLogger.logFailure("aop", "Authentication failure test: expected security exception but operation succeeded");
                return false;
            } catch (SecurityException expected) {
                TestLogger.logInfo("aop", "Authentication failure test: successfully caught expected security exception: %s", expected.getMessage());
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Authentication failure test failed with unexpected error: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests role-based access control.
     */
    public static boolean testRoleBasedAccess() {
        try {
            TestLogger.logInfo("aop", "Starting role-based access test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider defaultProvider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(defaultProvider);

            // Create and register security provider with USER role only
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider() {
                        @Override
                        public boolean isAuthenticated() {
                            return true; // User is authenticated but has limited roles
                        }

                        @Override
                        public String[] getRoles() {
                            return new String[]{"USER"}; // Only USER role, not ADMIN
                        }

                        @Override
                        public String[] getPermissions() {
                            return new String[]{"user:read"}; // Limited permissions
                        }
                    };

            // Set up security context with USER role only
            provider.clearContext();
            provider.setCurrentContext("test-user", new String[]{"USER"}, new String[]{"user:read"});
            securityProviderRegistry.registerProvider(provider);

            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the security test class
            aopService.registerTestInterceptors(TestSecureService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            // Test admin-only operation - should fail
            try {
                service.adminOperation("system-config");
                TestLogger.logFailure("aop", "Role-based access test: expected security exception but admin operation succeeded");
                return false;
            } catch (SecurityException expected) {
                TestLogger.logInfo("aop", "Role-based access test: successfully caught expected security exception for admin operation");
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Role-based access test failed with unexpected error: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent security checks.
     */
    public static boolean testConcurrentSecurityChecks() {
        try {
            TestLogger.logInfo("aop", "Starting concurrent security checks test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(provider);

            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            // Execute multiple security checks concurrently
            CompletableFuture<Boolean>[] futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                final int index = i;
                futures[i] = CompletableFuture.supplyAsync(() -> {
                    try {
                        if (index % 2 == 0) {
                            String result = service.publicInfo();
                            return result != null && result.contains("Public information");
                        } else {
                            String result = service.readUser("user-" + index);
                            return result != null && result.contains("user-" + index);
                        }
                    } catch (Exception exception) {
                        return false;
                    }
                });
            }

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures
            );
            allFutures.get(10, java.util.concurrent.TimeUnit.SECONDS);

            int successCount = 0;
            for (CompletableFuture<Boolean> future : futures) {
                if (future.get()) {
                    successCount++;
                }
            }

            boolean resultValid = successCount >= 3; // At least 3 should succeed

            TestLogger.logInfo("aop", "Concurrent security checks test: successCount=%d, resultValid=%s", successCount, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Concurrent security checks test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests security policy integration.
     */
    public static boolean testSecurityPolicyIntegration() {
        try {
            TestLogger.logInfo("aop", "Starting security policy integration test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            // Create security aspect with dependencies
            net.legacy.library.aop.security.DefaultSecurityProvider defaultProvider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider();
            SecurityProviderRegistry securityProviderRegistry = new SecurityProviderRegistry(defaultProvider);
            SecurityAspect securityAspect = new SecurityAspect(securityProviderRegistry);

            // Create and register security provider with controlled behavior
            net.legacy.library.aop.security.DefaultSecurityProvider provider =
                    new net.legacy.library.aop.security.DefaultSecurityProvider() {
                        private boolean isAuthenticated = false;
                        private String[] roles = new String[0];
                        private String[] permissions = new String[0];

                        @Override
                        public boolean isAuthenticated() {
                            return isAuthenticated;
                        }

                        @Override
                        public String[] getRoles() {
                            return roles;
                        }

                        @Override
                        public String[] getPermissions() {
                            return permissions;
                        }

                        @Override
                        public Object getPrincipal() {
                            return isAuthenticated ? "test-user" : null;
                        }

                        @Override
                        public void setCurrentContext(Object principal, String[] roles, String[] permissions) {
                            this.isAuthenticated = principal != null;
                            this.roles = roles != null ? roles : new String[0];
                            this.permissions = permissions != null ? permissions : new String[0];
                        }

                        // Helper method to set security state
                        public void setSecurityState(boolean authenticated, String[] roles, String[] permissions) {
                            setCurrentContext(authenticated ? "test-user" : null, roles, permissions);
                        }
                    };
            securityProviderRegistry.registerProvider(provider);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    securityAspect,
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    null,  // ValidationAspect
                    null   // TracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            SecureService service = aopFactory.create(TestSecureService.class);

            // Test different security scenarios
            boolean publicAccessSuccess = false;
            boolean userReadSuccess = false;
            boolean adminOperationFailure = false;

            // Test public access (no authentication required)
            try {
                provider.setCurrentContext(null, new String[0], new String[0]); // No authentication
                String publicResult = service.publicInfo();
                publicAccessSuccess = publicResult != null && publicResult.contains("Public information");
                TestLogger.logInfo("aop", "Security policy test: public access result = %s", publicAccessSuccess);
            } catch (Exception exception) {
                TestLogger.logFailure("aop", "Security policy test: public access failed unexpectedly: %s", exception.getMessage());
            }

            // Test user read with proper authentication
            try {
                provider.setCurrentContext("test-user", new String[]{"USER", "ADMIN", "MANAGER"}, new String[]{"user:read"});
                String userResult = service.readUser("test-user");
                userReadSuccess = userResult != null && userResult.contains("test-user");
                TestLogger.logInfo("aop", "Security policy test: user read result = %s", userReadSuccess);
            } catch (Exception exception) {
                TestLogger.logFailure("aop", "Security policy test: user read failed unexpectedly: %s", exception.getMessage());
            }

            // Test admin operation with insufficient permissions
            try {
                provider.setCurrentContext("test-user", new String[]{"USER"}, new String[]{"user:read"}); // No ADMIN role
                service.adminOperation("test-config");
                TestLogger.logFailure("aop", "Security policy test: admin operation should have failed but succeeded");
            } catch (SecurityException expected) {
                adminOperationFailure = true;
                TestLogger.logInfo("aop", "Security policy test: admin operation correctly failed with SecurityException");
            } catch (Exception exception) {
                TestLogger.logFailure("aop", "Security policy test: admin operation failed with unexpected exception: %s", exception.getMessage());
            }

            boolean overallResult = publicAccessSuccess && userReadSuccess && adminOperationFailure;

            TestLogger.logInfo("aop", "Security policy integration test: publicAccess=%s, userRead=%s, adminFailure=%s, overall=%s",
                    publicAccessSuccess, userReadSuccess, adminOperationFailure, overallResult);
            return overallResult;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Security policy integration test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing security functionality.
     */
    public interface SecureService {

        @Secured(
                roles = {"ADMIN", "MANAGER"},
                permissions = {"user:create", "user:update"}
        )
        void createUser(String userData) throws SecurityException;

        @Secured(
                roles = {"USER", "ADMIN", "MANAGER"},
                permissions = {"user:read"}
        )
        String readUser(String userId) throws SecurityException;

        @Secured(
                authenticated = false
        )
        String publicInfo() throws SecurityException;

        @Secured(
                roles = {"ADMIN"},
                permissions = {"system:admin"},
                audit = true
        )
        void adminOperation(String operationData) throws SecurityException;

        @Secured(
                policy = BusinessHoursAdminPolicy.class
        )
        void policyProtectedOperation(String data) throws SecurityException;

    }

    /**
     * Test implementation for secure operations.
     */
    public static class TestSecureService implements SecureService {

        private final AtomicInteger operationCounter = new AtomicInteger(0);

        @Override
        @Secured(
                roles = {"ADMIN", "MANAGER"},
                permissions = {"user:create", "user:update"}
        )
        public void createUser(String userData) throws SecurityException {
            TestLogger.logInfo("aop", "User created: %s (operation #%d)", userData, operationCounter.incrementAndGet());
        }

        @Override
        @Secured(
                roles = {"USER", "ADMIN", "MANAGER"},
                permissions = {"user:read"}
        )
        public String readUser(String userId) throws SecurityException {
            return "User data read: " + userId;
        }

        @Override
        @Secured(
                authenticated = false
        )
        public String publicInfo() throws SecurityException {
            return "Public information available to everyone";
        }

        @Override
        @Secured(
                roles = {"ADMIN"},
                permissions = {"system:admin"},
                audit = true
        )
        public void adminOperation(String operationData) throws SecurityException {
            TestLogger.logInfo("aop", "Admin operation completed: %s", operationData);
        }

        @Override
        @Secured(
                policy = BusinessHoursAdminPolicy.class
        )
        public void policyProtectedOperation(String data) throws SecurityException {
            TestLogger.logInfo("aop", "Policy protected operation executed: %s", data);
        }

    }

    /**
     * Test security policy that only allows ADMIN role during business hours.
     */
    public static class BusinessHoursAdminPolicy implements SecurityPolicy {

        @Override
        public boolean isAuthorized(SecurityContext context) {
            // Check if user has ADMIN role
            boolean hasAdminRole = false;
            for (String role : context.getRoles()) {
                if ("ADMIN".equals(role)) {
                    hasAdminRole = true;
                    break;
                }
            }

            // Check if current time is during business hours (9 AM - 5 PM)
            java.time.LocalTime now = java.time.LocalTime.now();
            boolean isBusinessHours = !now.isBefore(java.time.LocalTime.of(9, 0)) &&
                    !now.isAfter(java.time.LocalTime.of(17, 0));

            return hasAdminRole && isBusinessHours;
        }

        @Override
        public String getErrorMessage() {
            return "Access denied: ADMIN role required during business hours (9 AM - 5 PM) only";
        }

    }

}
