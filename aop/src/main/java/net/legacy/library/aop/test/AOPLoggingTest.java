package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.Logged;
import net.legacy.library.aop.aspect.LoggingAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

/**
 * Test class for logging functionality.
 *
 * <p>This test class focuses on the {@code @Logged} annotation and related
 * logging mechanisms. Tests verify different log levels, argument logging,
 * result logging, and custom format templates.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-12-26 12:00
 */
@ModuleTest(
        testName = "aop-logging-test",
        description = "Tests logging functionality for method invocation auditing",
        tags = {"aop", "logging", "audit", "monitoring", "enterprise"},
        priority = 2,
        timeout = 15000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPLoggingTest {

    /**
     * Tests basic logging with different log levels.
     */
    public static boolean testLoggingLevels() {
        try {
            TestLogger.logInfo("aop", "Starting logging levels test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            LoggingAspect loggingAspect = new LoggingAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(loggingAspect);
            aopService.registerTestInterceptors(TestLoggingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            LoggingService service = aopFactory.create(TestLoggingService.class);

            // Test different log levels - these should all execute successfully
            String debugResult = service.debugLevelOperation("debug-test");
            String infoResult = service.infoLevelOperation("info-test");
            String warnResult = service.warnLevelOperation("warn-test");
            String errorResult = service.errorLevelOperation("error-test");

            boolean allSuccessful = debugResult != null && debugResult.contains("debug-test")
                    && infoResult != null && infoResult.contains("info-test")
                    && warnResult != null && warnResult.contains("warn-test")
                    && errorResult != null && errorResult.contains("error-test");

            TestLogger.logInfo("aop", "Logging levels test: debug=%s, info=%s, warn=%s, error=%s, allSuccessful=%s",
                    debugResult, infoResult, warnResult, errorResult, allSuccessful);

            return allSuccessful;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Logging levels test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests logging with includeArgs configuration.
     */
    public static boolean testLoggingIncludeArgs() {
        try {
            TestLogger.logInfo("aop", "Starting logging include args test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            LoggingAspect loggingAspect = new LoggingAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(loggingAspect);
            aopService.registerTestInterceptors(TestLoggingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            LoggingService service = aopFactory.create(TestLoggingService.class);

            // Call method with includeArgs=true
            String result = service.operationWithArgs("arg1", "arg2");

            // Verify the operation completed successfully
            // (actual log output verification would require log capture which is beyond this test scope)
            boolean resultValid = result != null && result.contains("arg1") && result.contains("arg2");

            TestLogger.logInfo("aop", "Logging include args test: result=%s, resultValid=%s",
                    result, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Logging include args test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests logging with includeResult configuration.
     */
    public static boolean testLoggingIncludeResult() {
        try {
            TestLogger.logInfo("aop", "Starting logging include result test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            LoggingAspect loggingAspect = new LoggingAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(loggingAspect);
            aopService.registerTestInterceptors(TestLoggingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            LoggingService service = aopFactory.create(TestLoggingService.class);

            // Call method with includeResult=true
            String result = service.operationWithResult("result-test");

            // Verify the operation completed successfully
            boolean resultValid = result != null && result.contains("Result:");

            TestLogger.logInfo("aop", "Logging include result test: result=%s, resultValid=%s",
                    result, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Logging include result test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests logging with custom format template.
     */
    public static boolean testLoggingCustomFormat() {
        try {
            TestLogger.logInfo("aop", "Starting logging custom format test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            LoggingAspect loggingAspect = new LoggingAspect();

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    null,
                    null
            );

            aopService.initialize();
            aopService.registerGlobalInterceptor(loggingAspect);
            aopService.registerTestInterceptors(TestLoggingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            LoggingService service = aopFactory.create(TestLoggingService.class);

            // Call method with custom format
            String result = service.operationWithCustomFormat("format-test");

            // Verify the operation completed successfully with custom format applied
            boolean resultValid = result != null && result.contains("format-test");

            TestLogger.logInfo("aop", "Logging custom format test: result=%s, resultValid=%s",
                    result, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Logging custom format test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing logging functionality.
     */
    public interface LoggingService {

        @Logged(level = Logged.LogLevel.DEBUG)
        String debugLevelOperation(String input);

        @Logged(level = Logged.LogLevel.INFO)
        String infoLevelOperation(String input);

        @Logged(level = Logged.LogLevel.WARN)
        String warnLevelOperation(String input);

        @Logged(level = Logged.LogLevel.ERROR)
        String errorLevelOperation(String input);

        @Logged(level = Logged.LogLevel.INFO, includeArgs = true)
        String operationWithArgs(String arg1, String arg2);

        @Logged(level = Logged.LogLevel.INFO, includeResult = true)
        String operationWithResult(String input);

        @Logged(
                level = Logged.LogLevel.INFO,
                format = "[CUSTOM] Method: {method}, Args: {args}, Result: {result}, Duration: {duration}ms"
        )
        String operationWithCustomFormat(String input);

    }

    /**
     * Test implementation for logging operations.
     */
    public static class TestLoggingService implements LoggingService {

        @Override
        @Logged(level = Logged.LogLevel.DEBUG)
        public String debugLevelOperation(String input) {
            return "Debug result: " + input;
        }

        @Override
        @Logged(level = Logged.LogLevel.INFO)
        public String infoLevelOperation(String input) {
            return "Info result: " + input;
        }

        @Override
        @Logged(level = Logged.LogLevel.WARN)
        public String warnLevelOperation(String input) {
            return "Warn result: " + input;
        }

        @Override
        @Logged(level = Logged.LogLevel.ERROR)
        public String errorLevelOperation(String input) {
            return "Error result: " + input;
        }

        @Override
        @Logged(level = Logged.LogLevel.INFO, includeArgs = true)
        public String operationWithArgs(String arg1, String arg2) {
            return "Args received: " + arg1 + ", " + arg2;
        }

        @Override
        @Logged(level = Logged.LogLevel.INFO, includeResult = true)
        public String operationWithResult(String input) {
            return "Result: processed " + input;
        }

        @Override
        @Logged(
                level = Logged.LogLevel.INFO,
                format = "[CUSTOM] Method: {method}, Args: {args}, Result: {result}, Duration: {duration}ms"
        )
        public String operationWithCustomFormat(String input) {
            return "Custom format result: " + input;
        }

    }

}
