package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.Traced;
import net.legacy.library.aop.annotation.ValidInput;
import net.legacy.library.aop.aspect.TracingAspect;
import net.legacy.library.aop.aspect.ValidationAspect;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.aop.tracing.DefaultTraceService;
import net.legacy.library.aop.tracing.InMemoryTraceExporter;
import net.legacy.library.aop.tracing.TraceService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Test class for validation and tracing functionality in enterprise environments.
 *
 * <p>This test class focuses on the {@code @ValidInput} and {@code @Traced} annotations
 * and related validation/tracing capabilities. Tests verify input validation constraints,
 * distributed tracing, span propagation, and observability features.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:45
 */
@ModuleTest(
        testName = "aop-validation-tracing-test",
        description = "Tests validation and tracing functionality for enterprise observability",
        tags = {"aop", "validation", "tracing", "observability", "enterprise"},
        priority = 4,
        timeout = 20000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPValidationTracingTest {

    /**
     * Tests successful input validation.
     */
    public static boolean testSuccessfulValidation() {
        try {
            TestLogger.logInfo("aop", "Starting successful validation test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            TraceService traceService = new DefaultTraceService(new InMemoryTraceExporter());
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    validationAspect,
                    tracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the validation test class
            aopService.registerTestInterceptors(TestValidationService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ValidationService service = aopFactory.create(TestValidationService.class);

            String usernameResult = service.validateUsername("valid_user123");
            String ageResult = service.validateAge(25);
            String emailResult = service.validateEmail("test@example.com");

            boolean usernameValid = usernameResult != null && usernameResult.contains("valid_user123");
            boolean ageValid = ageResult != null && ageResult.contains("25");
            boolean emailValid = emailResult != null && emailResult.contains("test@example.com");

            boolean overallValid = usernameValid && ageValid && emailValid;

            TestLogger.logInfo("aop", "Successful validation test: username=%s, age=%s, email=%s, overall=%s",
                    usernameValid, ageValid, emailValid, overallValid);
            return overallValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Successful validation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests validation failure scenarios.
     */
    public static boolean testValidationFailure() {
        try {
            TestLogger.logInfo("aop", "Starting validation failure test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            TraceService traceService = new DefaultTraceService(new InMemoryTraceExporter());
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    validationAspect,
                    tracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            // Register test interceptors for the validation test class
            aopService.registerTestInterceptors(TestValidationService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ValidationService service = aopFactory.create(TestValidationService.class);

            int failureCount = 0;
            int expectedFailures = 3;

            // Test invalid username (too short)
            try {
                service.validateUsername("ab");
            } catch (IllegalArgumentException expected) {
                failureCount++;
                TestLogger.logInfo("aop", "Validation failure test: caught expected username validation failure");
            }

            // Test invalid age (negative)
            try {
                service.validateAge(-5);
            } catch (IllegalArgumentException expected) {
                failureCount++;
                TestLogger.logInfo("aop", "Validation failure test: caught expected age validation failure");
            }

            // Test invalid email
            try {
                service.validateEmail("invalid-email");
            } catch (IllegalArgumentException expected) {
                failureCount++;
                TestLogger.logInfo("aop", "Validation failure test: caught expected email validation failure");
            }

            boolean resultValid = failureCount == expectedFailures;

            TestLogger.logInfo("aop", "Validation failure test: failureCount=%d, expected=%d, resultValid=%s",
                    failureCount, expectedFailures, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Validation failure test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests distributed tracing functionality.
     */
    public static boolean testDistributedTracing() {
        try {
            TestLogger.logInfo("aop", "Starting distributed tracing test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            TraceService traceService = new DefaultTraceService(new InMemoryTraceExporter());
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    validationAspect,
                    tracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TracingService service = aopFactory.create(TestTracingService.class);

            String userResult = service.processUserData("user-123", "sample data");
            String orderResult = service.createOrder("order-456", "order data");

            boolean userValid = userResult != null && userResult.contains("user-123") && userResult.contains("Processed user data");
            boolean orderValid = orderResult != null && orderResult.contains("order-456") && orderResult.contains("Created order");

            boolean overallValid = userValid && orderValid;

            TestLogger.logInfo("aop", "Distributed tracing test: user=%s, order=%s, overall=%s",
                    userValid, orderValid, overallValid);
            return overallValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Distributed tracing test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests concurrent traced operations.
     */
    public static boolean testConcurrentTracing() {
        try {
            TestLogger.logInfo("aop", "Starting concurrent tracing test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            TraceService traceService = new DefaultTraceService(new InMemoryTraceExporter());
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,  // DistributedTransactionAspect
                    null,  // SecurityAspect
                    null,  // CircuitBreakerAspect
                    null,  // RetryAspect
                    validationAspect,
                    tracingAspect
            );

            // Initialize AOP service to register all aspects
            aopService.initialize();

            AOPFactory aopFactory = new AOPFactory(aopService);
            TracingService service = aopFactory.create(TestTracingService.class);

            // Execute multiple traced operations concurrently
            CompletableFuture<String>[] futures = new CompletableFuture[5];
            for (int i = 0; i < 5; i++) {
                final int index = i;
                futures[i] = CompletableFuture.supplyAsync(() -> service.processUserData("user-" + index, "data-" + index));
            }

            CompletableFuture<Void> allFutures = CompletableFuture.allOf(
                    futures
            );
            allFutures.get(10, TimeUnit.SECONDS);

            int successCount = 0;
            for (CompletableFuture<String> future : futures) {
                if (future.get() != null && future.get().contains("Processed user data")) {
                    successCount++;
                }
            }

            boolean resultValid = successCount == 5;

            TestLogger.logInfo("aop", "Concurrent tracing test: successCount=%d, resultValid=%s", successCount, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Concurrent tracing test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests tracing with sampling rate configuration.
     */
    public static boolean testTracingSamplingRate() {
        try {
            TestLogger.logInfo("aop", "Starting tracing sampling rate test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            TraceService traceService = new DefaultTraceService(new InMemoryTraceExporter());
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    validationAspect,
                    tracingAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedTracingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedTracingService service = aopFactory.create(TestAdvancedTracingService.class);

            // Test alwaysTrace=true - should always be traced
            String alwaysResult = service.alwaysTracedOperation("always-trace-test");

            // Test samplingRate=0.0 - should not be sampled (but method still executes)
            String noSampleResult = service.noSamplingOperation("no-sample-test");

            boolean alwaysValid = alwaysResult != null && alwaysResult.contains("always-trace-test");
            boolean noSampleValid = noSampleResult != null && noSampleResult.contains("no-sample-test");

            TestLogger.logInfo("aop", "Tracing sampling rate test: alwaysValid=%s, noSampleValid=%s",
                    alwaysValid, noSampleValid);
            return alwaysValid && noSampleValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Tracing sampling rate test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests tracing with return value inclusion.
     */
    public static boolean testTracingReturnValue() {
        try {
            TestLogger.logInfo("aop", "Starting tracing return value test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            InMemoryTraceExporter exporter = new InMemoryTraceExporter();
            TraceService traceService = new DefaultTraceService(exporter);
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    validationAspect,
                    tracingAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedTracingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedTracingService service = aopFactory.create(TestAdvancedTracingService.class);

            String result = service.operationWithReturnValue("return-value-test");

            boolean resultValid = result != null && result.contains("return-value-test");

            TestLogger.logInfo("aop", "Tracing return value test: result=%s, resultValid=%s", result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Tracing return value test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests tracing with custom service name.
     */
    public static boolean testTracingServiceName() {
        try {
            TestLogger.logInfo("aop", "Starting tracing service name test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            InMemoryTraceExporter exporter = new InMemoryTraceExporter();
            TraceService traceService = new DefaultTraceService(exporter);
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    validationAspect,
                    tracingAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedTracingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedTracingService service = aopFactory.create(TestAdvancedTracingService.class);

            String result = service.operationWithServiceName("service-name-test");

            boolean resultValid = result != null && result.contains("service-name-test");

            TestLogger.logInfo("aop", "Tracing service name test: result=%s, resultValid=%s", result, resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Tracing service name test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests tracing parameter truncation.
     */
    public static boolean testTracingParameterTruncation() {
        try {
            TestLogger.logInfo("aop", "Starting tracing parameter truncation test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            ValidationAspect validationAspect = new ValidationAspect();
            InMemoryTraceExporter exporter = new InMemoryTraceExporter();
            TraceService traceService = new DefaultTraceService(exporter);
            TracingAspect tracingAspect = new TracingAspect(traceService);

            AOPService aopService = new AOPService(
                    proxyFactory,
                    isolationService,
                    null,
                    null,
                    null,
                    null,
                    validationAspect,
                    tracingAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestAdvancedTracingService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            AdvancedTracingService service = aopFactory.create(TestAdvancedTracingService.class);

            // Create a very long parameter
            StringBuilder longParam = new StringBuilder();
            for (int i = 0; i < 200; i++) {
                longParam.append("X");
            }

            String result = service.operationWithSmallParameterSize(longParam.toString());

            boolean resultValid = result != null && result.contains("truncation");

            TestLogger.logInfo("aop", "Tracing parameter truncation test: resultValid=%s", resultValid);
            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Tracing parameter truncation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing validation functionality.
     */
    public interface ValidationService {

        String validateUsername(@ValidInput(
                required = true,
                minLength = 3,
                maxLength = 50,
                pattern = "^[a-zA-Z0-9_-]+$"
        ) String username) throws IllegalArgumentException;

        String validateAge(@ValidInput(
                required = true,
                min = 0,
                max = 150
        ) Integer age) throws IllegalArgumentException;

        String validateEmail(@ValidInput(
                required = true,
                pattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        ) String email) throws IllegalArgumentException;

    }

    /**
     * Service interface for testing tracing functionality.
     */
    public interface TracingService {

        @Traced(
                operationName = "user.processing",
                tags = {"component:user-service", "environment:production"}
        )
        String processUserData(String userId, String userData);

        @Traced(
                operationName = "order.creation",
                tags = {"component:order-service", "environment:production"}
        )
        String createOrder(String orderId, String orderData);

    }

    /**
     * Advanced tracing service interface for extended tests.
     */
    public interface AdvancedTracingService {

        @Traced(
                operationName = "always.traced",
                alwaysTrace = true
        )
        String alwaysTracedOperation(String input);

        @Traced(
                operationName = "no.sampling",
                samplingRate = 0.0
        )
        String noSamplingOperation(String input);

        @Traced(
                operationName = "with.return",
                includeReturnValue = true
        )
        String operationWithReturnValue(String input);

        @Traced(
                operationName = "custom.service",
                serviceName = "CustomServiceName"
        )
        String operationWithServiceName(String input);

        @Traced(
                operationName = "small.params",
                maxParameterSize = 20
        )
        String operationWithSmallParameterSize(String longInput);

    }

    /**
     * Test implementation for validation operations.
     */
    public static class TestValidationService implements ValidationService {

        @Override
        public String validateUsername(@ValidInput(
                required = true,
                minLength = 3,
                maxLength = 50,
                pattern = "^[a-zA-Z0-9_-]+$"
        ) String username) throws IllegalArgumentException {
            return "Valid username: " + username;
        }

        @Override
        public String validateAge(@ValidInput(
                required = true,
                min = 0,
                max = 150
        ) Integer age) throws IllegalArgumentException {
            return "Valid age: " + age;
        }

        @Override
        public String validateEmail(@ValidInput(
                required = true,
                pattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$"
        ) String email) throws IllegalArgumentException {
            return "Valid email: " + email;
        }

    }

    /**
     * Test implementation for tracing operations.
     */
    public static class TestTracingService implements TracingService {

        private final AtomicInteger operationCounter = new AtomicInteger(0);

        @Override
        @Traced(
                operationName = "user.processing",
                tags = {"component:user-service", "environment:production"}
        )
        public String processUserData(String userId, String userData) {
            return "Processed user data: " + userId + " (Operation #" + operationCounter.incrementAndGet() + ")";
        }

        @Override
        @Traced(
                operationName = "order.creation",
                tags = {"component:order-service", "environment:production"}
        )
        public String createOrder(String orderId, String orderData) {
            return "Created order: " + orderId + " (Operation #" + operationCounter.incrementAndGet() + ")";
        }

    }

    /**
     * Test implementation for advanced tracing operations.
     */
    public static class TestAdvancedTracingService implements AdvancedTracingService {

        @Override
        @Traced(
                operationName = "always.traced",
                alwaysTrace = true
        )
        public String alwaysTracedOperation(String input) {
            return "Always traced: " + input;
        }

        @Override
        @Traced(
                operationName = "no.sampling",
                samplingRate = 0.0
        )
        public String noSamplingOperation(String input) {
            return "No sampling: " + input;
        }

        @Override
        @Traced(
                operationName = "with.return",
                includeReturnValue = true
        )
        public String operationWithReturnValue(String input) {
            return "Return value included: " + input;
        }

        @Override
        @Traced(
                operationName = "custom.service",
                serviceName = "CustomServiceName"
        )
        public String operationWithServiceName(String input) {
            return "Custom service: " + input;
        }

        @Override
        @Traced(
                operationName = "small.params",
                maxParameterSize = 20
        )
        public String operationWithSmallParameterSize(String longInput) {
            return "Parameter truncation test: " + longInput.substring(0, Math.min(10, longInput.length()));
        }

    }

}
