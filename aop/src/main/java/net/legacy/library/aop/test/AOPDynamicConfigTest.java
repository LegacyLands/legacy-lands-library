package net.legacy.library.aop.test;

import net.legacy.library.aop.annotation.DynamicConfig;
import net.legacy.library.aop.aspect.DynamicConfigAspect;
import net.legacy.library.aop.config.DefaultConfigurationService;
import net.legacy.library.aop.factory.AOPFactory;
import net.legacy.library.aop.proxy.AspectProxyFactory;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

/**
 * Test class for dynamic configuration injection functionality.
 *
 * <p>This test class focuses on the {@code @DynamicConfig} annotation and related
 * configuration injection mechanisms. Tests verify field-level injection,
 * default value handling, and configuration change callbacks.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-12-25 15:00
 */
@ModuleTest(
        testName = "aop-dynamic-config-test",
        description = "Tests dynamic configuration injection functionality",
        tags = {"aop", "dynamic-config", "configuration", "injection", "enterprise"},
        priority = 3,
        timeout = 20000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class AOPDynamicConfigTest {

    /**
     * Tests basic field injection with default values.
     */
    public static boolean testDefaultValueInjection() {
        try {
            TestLogger.logInfo("aop", "Starting default value injection test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DefaultConfigurationService configService = new DefaultConfigurationService();
            DynamicConfigAspect dynamicConfigAspect = new DynamicConfigAspect(configService);

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
                    dynamicConfigAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestConfigService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ConfigService service = aopFactory.create(TestConfigService.class);

            String result = service.getConfiguration();

            boolean resultValid = result != null
                    && result.contains("timeout=30")
                    && result.contains("maxRetries=3")
                    && result.contains("enabled=true");

            TestLogger.logInfo("aop", "Default value injection test: result=%s, resultValid=%s",
                    result, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Default value injection test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests configuration injection from system properties.
     */
    public static boolean testSystemPropertyInjection() {
        String originalValue = System.getProperty("test.custom.timeout");
        try {
            TestLogger.logInfo("aop", "Starting system property injection test");

            // Set system property for configuration
            System.setProperty("test.custom.timeout", "60");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DefaultConfigurationService configService = new DefaultConfigurationService();
            DynamicConfigAspect dynamicConfigAspect = new DynamicConfigAspect(configService);

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
                    dynamicConfigAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestSystemPropertyConfigService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            SystemPropertyConfigService service = aopFactory.create(TestSystemPropertyConfigService.class);

            int timeout = service.getTimeout();

            boolean resultValid = timeout == 60;
            TestLogger.logInfo("aop", "System property injection test: timeout=%d, resultValid=%s",
                    timeout, resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "System property injection test failed: %s", exception.getMessage());
            return false;
        } finally {
            // Restore original value
            if (originalValue != null) {
                System.setProperty("test.custom.timeout", originalValue);
            } else {
                System.clearProperty("test.custom.timeout");
            }
        }
    }

    /**
     * Tests that required configuration throws when missing.
     */
    public static boolean testRequiredConfigurationValidation() {
        try {
            TestLogger.logInfo("aop", "Starting required configuration validation test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DefaultConfigurationService configService = new DefaultConfigurationService();
            DynamicConfigAspect dynamicConfigAspect = new DynamicConfigAspect(configService);

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
                    dynamicConfigAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestRequiredConfigService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            RequiredConfigService service = aopFactory.create(TestRequiredConfigService.class);

            try {
                service.getApiKey();
                TestLogger.logFailure("aop", "Required configuration validation test: expected exception for missing required config");
                return false;
            } catch (IllegalStateException expected) {
                boolean resultValid = expected.getMessage().contains("Required configuration not found");
                TestLogger.logInfo("aop", "Required configuration validation test: caught expected exception: %s, resultValid=%s",
                        expected.getMessage(), resultValid);
                return resultValid;
            }
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Required configuration validation test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests configuration metadata registration.
     */
    public static boolean testConfigurationMetadataRegistration() {
        try {
            TestLogger.logInfo("aop", "Starting configuration metadata registration test");

            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

            DefaultConfigurationService configService = new DefaultConfigurationService();
            DynamicConfigAspect dynamicConfigAspect = new DynamicConfigAspect(configService);

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
                    dynamicConfigAspect
            );

            aopService.initialize();
            aopService.registerTestInterceptors(TestConfigService.class);

            AOPFactory aopFactory = new AOPFactory(aopService);
            ConfigService service = aopFactory.create(TestConfigService.class);

            // Trigger configuration injection
            service.getConfiguration();

            // Verify metadata was registered
            var metadata = configService.getMetadata("app.timeout");

            boolean resultValid = metadata != null
                    && "30".equals(metadata.getDefaultValue())
                    && "Timeout in seconds".equals(metadata.getDescription());

            TestLogger.logInfo("aop", "Configuration metadata registration test: metadata=%s, resultValid=%s",
                    metadata != null ? metadata.getKey() : "null", resultValid);

            return resultValid;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Configuration metadata registration test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Service interface for testing configuration injection.
     */
    public interface ConfigService {

        String getConfiguration();

    }

    /**
     * Service interface for testing system property injection.
     */
    public interface SystemPropertyConfigService {

        int getTimeout();

    }

    /**
     * Service interface for testing required configuration.
     */
    public interface RequiredConfigService {

        String getApiKey();

    }

    /**
     * Test implementation with default value configuration.
     */
    public static class TestConfigService implements ConfigService {

        @DynamicConfig(
                key = "app.timeout",
                defaultValue = "30",
                description = "Timeout in seconds"
        )
        private int timeout;

        @DynamicConfig(
                key = "app.maxRetries",
                defaultValue = "3",
                description = "Maximum retry attempts"
        )
        private int maxRetries;

        @DynamicConfig(
                key = "app.enabled",
                defaultValue = "true",
                description = "Feature enabled flag"
        )
        private boolean enabled;

        @Override
        public String getConfiguration() {
            return "timeout=" + timeout + ", maxRetries=" + maxRetries + ", enabled=" + enabled;
        }

    }

    /**
     * Test implementation with system property configuration.
     */
    public static class TestSystemPropertyConfigService implements SystemPropertyConfigService {

        @DynamicConfig(
                key = "test.custom.timeout",
                defaultValue = "10",
                source = "system"
        )
        private int customTimeout;

        @Override
        public int getTimeout() {
            return customTimeout;
        }

    }

    /**
     * Test implementation with required configuration.
     */
    public static class TestRequiredConfigService implements RequiredConfigService {

        @DynamicConfig(
                key = "api.key",
                required = true,
                description = "Required API key"
        )
        private String apiKey;

        @Override
        public String getApiKey() {
            return apiKey;
        }

    }

}
