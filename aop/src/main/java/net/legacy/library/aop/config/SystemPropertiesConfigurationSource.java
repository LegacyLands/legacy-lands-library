package net.legacy.library.aop.config;

import io.fairyproject.container.InjectableComponent;

import java.util.Map;
import java.util.Properties;
import java.util.concurrent.CompletableFuture;

/**
 * Default configuration source that provides basic system properties and environment variables.
 *
 * <p>This implementation serves as a fallback configuration source when no other
 * configuration sources are available. It provides access to Java system properties
 * and environment variables.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
public class SystemPropertiesConfigurationSource implements ConfigurationSource {

    private final Properties systemProperties;

    /**
     * Creates a new system properties configuration source.
     */
    public SystemPropertiesConfigurationSource() {
        this.systemProperties = System.getProperties();
    }

    @Override
    public String getName() {
        return "SystemProperties";
    }

    @Override
    public int getPriority() {
        return 1; // Low priority, can be overridden by other sources
    }

    @Override
    public boolean supportsWriting() {
        return false; // System properties are generally read-only
    }

    @Override
    public boolean supportsMonitoring() {
        return false; // Cannot monitor system property changes
    }

    @Override
    public Map<String, String> load() {
        // Convert system properties to a map
        Map<String, String> result = new java.util.HashMap<>();

        // Add system properties
        for (String propertyName : systemProperties.stringPropertyNames()) {
            String value = systemProperties.getProperty(propertyName);
            result.put(propertyName, value);
        }

        // Add environment variables with a prefix
        Map<String, String> env = System.getenv();
        for (Map.Entry<String, String> entry : env.entrySet()) {
            result.put("env." + entry.getKey(), entry.getValue());
        }

        // Add some default AOP configuration
        result.put("aop.enabled", "true");
        result.put("aop.tracing.enabled", "true");
        result.put("aop.security.enabled", "true");
        result.put("aop.retry.enabled", "true");
        result.put("aop.monitoring.enabled", "true");
        result.put("aop.async-safe.enabled", "true");
        result.put("aop.logging.enabled", "true");
        result.put("aop.fault-tolerance.enabled", "true");
        result.put("aop.transactions.enabled", "true");
        result.put("aop.transaction.timeout", "30");
        result.put("aop.debug", "false");

        return result;
    }

    @Override
    public boolean save(String key, String value) {
        // System properties are read-only in this implementation
        return false;
    }

    @Override
    public boolean delete(String key) {
        // System properties are read-only in this implementation
        return false;
    }

    @Override
    public CompletableFuture<Map<String, String>> loadAsync() {
        return CompletableFuture.supplyAsync(this::load);
    }

    @Override
    public SourceMetadata getMetadata() {
        return new SourceMetadata(
                "SystemProperties",
                "System properties and environment variables configuration source",
                "system",
                true,  // read-only
                false, // not monitorable
                Map.of("version", "1.0", "priority", "1")
        );
    }

    /**
     * Gets a system property by key.
     *
     * @param key the property key
     * @return the property value, or null if not found
     */
    public String getProperty(String key) {
        return systemProperties.getProperty(key);
    }

    /**
     * Gets an environment variable by key.
     *
     * @param key the environment variable key
     * @return the environment variable value, or null if not found
     */
    public String getEnvironmentVariable(String key) {
        return System.getenv(key);
    }

}
