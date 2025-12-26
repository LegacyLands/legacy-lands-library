package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.ExceptionWrapper;
import net.legacy.library.aop.aspect.ExceptionWrapperAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

/**
 * Test class for exception wrapping functionality.
 *
 * <p>This test class focuses on the {@code @ExceptionWrapper} annotation and related
 * exception handling mechanisms. Tests verify exception type wrapping, message formatting,
 * exclusion handling, and logging configuration.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-12-26 10:00
 */
@ModuleTest(
        testName = "aop-exception-wrapper-test",
        description = "Tests exception wrapping functionality for consistent error handling",
        tags = {"aop", "exception", "wrapper", "error-handling", "enterprise"},
        priority = 2,
        timeout = 15000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPExceptionWrapperTest {

    /**
     * Tests basic exception wrapping with custom exception type.
     */
    public static boolean testBasicExceptionWrapping() {
        try {
            TestLogger.logInfo("aop", "Starting basic exception wrapping test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ExceptionWrapperAspect exceptionWrapperAspect = new ExceptionWrapperAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(exceptionWrapperAspect);
            aopService.registerTestInterceptors(TestExceptionService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ExceptionService service = aopFactory.create(TestExceptionService.class);

            try {
                service.operationThatFails("test-input");
                TestLogger.logFailure("aop", "Basic exception wrapping test: expected exception but operation succeeded");
                return false;
            } catch (ServiceException expected) {
                // Verify the exception is wrapped correctly
                boolean hasCorrectCause = expected.getCause() instanceof RuntimeException;
                boolean hasCorrectMessage = expected.getMessage() != null;

                TestLogger.logInfo("aop", "Basic exception wrapping test: caught ServiceException, hasCorrectCause=%s, hasCorrectMessage=%s",
                        hasCorrectCause, hasCorrectMessage);
                return hasCorrectCause && hasCorrectMessage;
            } catch (Exception unexpected) {
                TestLogger.logFailure("aop", "Basic exception wrapping test: caught unexpected exception type: %s",
                        unexpected.getClass().getName());
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Basic exception wrapping test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests message placeholder replacement in wrapped exception.
     */
    public static boolean testMessagePlaceholderReplacement() {
        try {
            TestLogger.logInfo("aop", "Starting message placeholder replacement test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ExceptionWrapperAspect exceptionWrapperAspect = new ExceptionWrapperAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(exceptionWrapperAspect);
            aopService.registerTestInterceptors(TestExceptionService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ExceptionService service = aopFactory.create(TestExceptionService.class);

            try {
                service.operationWithCustomMessage("placeholder-test");
                TestLogger.logFailure("aop", "Message placeholder test: expected exception but operation succeeded");
                return false;
            } catch (ServiceException expected) {
                String message = expected.getMessage();

                // Verify placeholders are replaced
                boolean hasMethodName = message != null && message.contains("operationWithCustomMessage");
                boolean hasArgs = message != null && message.contains("placeholder-test");
                boolean hasOriginal = message != null && message.contains("Simulated failure");

                TestLogger.logInfo("aop", "Message placeholder test: message=%s, hasMethod=%s, hasArgs=%s, hasOriginal=%s",
                        message, hasMethodName, hasArgs, hasOriginal);
                return hasMethodName && hasArgs && hasOriginal;
            } catch (Exception unexpected) {
                TestLogger.logFailure("aop", "Message placeholder test: caught unexpected exception: %s", unexpected.getMessage());
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Message placeholder replacement test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that excluded exceptions are not wrapped.
     */
    public static boolean testExcludeExceptions() {
        try {
            TestLogger.logInfo("aop", "Starting exclude exceptions test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ExceptionWrapperAspect exceptionWrapperAspect = new ExceptionWrapperAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(exceptionWrapperAspect);
            aopService.registerTestInterceptors(TestExceptionService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ExceptionService service = aopFactory.create(TestExceptionService.class);

            try {
                service.operationWithExcludedExceptions("exclude-test");
                TestLogger.logFailure("aop", "Exclude exceptions test: expected exception but operation succeeded");
                return false;
            } catch (IllegalArgumentException expected) {
                // IllegalArgumentException should NOT be wrapped (it's in exclude list)
                TestLogger.logInfo("aop", "Exclude exceptions test: correctly caught unwrapped IllegalArgumentException");
                return true;
            } catch (ServiceException unexpected) {
                TestLogger.logFailure("aop", "Exclude exceptions test: exception was wrapped when it should have been excluded");
                return false;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Exclude exceptions test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that already wrapped exceptions are not double-wrapped.
     */
    public static boolean testAlreadyWrappedException() {
        try {
            TestLogger.logInfo("aop", "Starting already wrapped exception test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ExceptionWrapperAspect exceptionWrapperAspect = new ExceptionWrapperAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(exceptionWrapperAspect);
            aopService.registerTestInterceptors(TestExceptionService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ExceptionService service = aopFactory.create(TestExceptionService.class);

            try {
                service.operationThrowsServiceException("already-wrapped-test");
                TestLogger.logFailure("aop", "Already wrapped exception test: expected exception but operation succeeded");
                return false;
            } catch (ServiceException expected) {
                // Verify it's the original ServiceException, not double-wrapped
                boolean isOriginal = expected.getCause() == null ||
                        !(expected.getCause() instanceof ServiceException);

                TestLogger.logInfo("aop", "Already wrapped exception test: caught ServiceException, isOriginal=%s, cause=%s",
                        isOriginal, expected.getCause() != null ? expected.getCause().getClass().getSimpleName() : "null");
                return isOriginal;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Already wrapped exception test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests log original exception configuration.
     */
    public static boolean testLogOriginalConfiguration() {
        try {
            TestLogger.logInfo("aop", "Starting log original configuration test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ExceptionWrapperAspect exceptionWrapperAspect = new ExceptionWrapperAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(exceptionWrapperAspect);
            aopService.registerTestInterceptors(TestExceptionService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ExceptionService service = aopFactory.create(TestExceptionService.class);

            // Test with logOriginal=false
            try {
                service.operationWithNoLogging("no-log-test");
                TestLogger.logFailure("aop", "Log original configuration test: expected exception but operation succeeded");
                return false;
            } catch (ServiceException expected) {
                // We can't directly verify logging was skipped, but we verify the exception is still wrapped correctly
                TestLogger.logInfo("aop", "Log original configuration test: exception wrapped correctly with logOriginal=false");
            }

            // Test with logOriginal=true (default)
            try {
                service.operationThatFails("log-test");
                return false;
            } catch (ServiceException expected) {
                // Logging should have occurred (verified by log output)
                TestLogger.logInfo("aop", "Log original configuration test: exception wrapped correctly with logOriginal=true");
                return true;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Log original configuration test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing exception wrapping.
     */
    public interface ExceptionService {

        @ExceptionWrapper(wrapWith = ServiceException.class)
        void operationThatFails(String input) throws ServiceException;

        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                message = "Failed in {method} with args {args}: {original}"
        )
        void operationWithCustomMessage(String input) throws ServiceException;

        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                exclude = {IllegalArgumentException.class, IllegalStateException.class}
        )
        void operationWithExcludedExceptions(String input) throws ServiceException;

        @ExceptionWrapper(wrapWith = ServiceException.class)
        void operationThrowsServiceException(String input) throws ServiceException;

        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                logOriginal = false
        )
        void operationWithNoLogging(String input) throws ServiceException;

    }

    /**
     * Test implementation for exception wrapping operations.
     */
    public static class TestExceptionService implements ExceptionService {

        @Override
        @ExceptionWrapper(wrapWith = ServiceException.class)
        public void operationThatFails(String input) throws ServiceException {
            throw new RuntimeException("Simulated failure for: " + input);
        }

        @Override
        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                message = "Failed in {method} with args {args}: {original}"
        )
        public void operationWithCustomMessage(String input) throws ServiceException {
            throw new RuntimeException("Simulated failure for custom message test");
        }

        @Override
        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                exclude = {IllegalArgumentException.class, IllegalStateException.class}
        )
        public void operationWithExcludedExceptions(String input) throws ServiceException {
            // This should NOT be wrapped - IllegalArgumentException is in exclude list
            throw new IllegalArgumentException("Invalid argument: " + input);
        }

        @Override
        @ExceptionWrapper(wrapWith = ServiceException.class)
        public void operationThrowsServiceException(String input) throws ServiceException {
            // Already a ServiceException, should not be double-wrapped
            throw new ServiceException("Already a ServiceException: " + input, null);
        }

        @Override
        @ExceptionWrapper(
                wrapWith = ServiceException.class,
                logOriginal = false
        )
        public void operationWithNoLogging(String input) throws ServiceException {
            throw new RuntimeException("No logging for: " + input);
        }

    }

    /**
     * Custom service exception for testing exception wrapping.
     */
    public static class ServiceException extends Exception {

        /**
         * Constructs a new ServiceException with the specified message and cause.
         *
         * @param message the detail message
         * @param cause   the cause of this exception
         */
        public ServiceException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}
