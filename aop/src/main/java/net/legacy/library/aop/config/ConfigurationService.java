package net.legacy.library.aop.config;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Service for managing dynamic configuration.
 *
 * <p>This service provides methods for accessing, managing, and monitoring
 * configuration properties at runtime. It supports multiple configuration
 * sources, hot-reloading, and validation.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface ConfigurationService {

    /**
     * Gets a configuration value by key.
     *
     * @param key  the configuration key
     * @param type the target type
     * @param <T>  the type parameter
     * @return the configuration value
     */
    <T> T get(String key, Class<T> type);

    /**
     * Gets a configuration value by key with default value.
     *
     * @param key          the configuration key
     * @param type         the target type
     * @param defaultValue the default value
     * @param <T>          the type parameter
     * @return the configuration value
     */
    <T> T get(String key, Class<T> type, T defaultValue);

    /**
     * Gets a configuration value by key asynchronously.
     *
     * @param key  the configuration key
     * @param type the target type
     * @param <T>  the type parameter
     * @return a CompletableFuture that completes with the configuration value
     */
    <T> CompletableFuture<T> getAsync(String key, Class<T> type);

    /**
     * Sets a configuration value.
     *
     * @param key   the configuration key
     * @param value the configuration value
     * @return true if the configuration was updated successfully
     */
    boolean set(String key, Object value);

    /**
     * Sets a configuration value asynchronously.
     *
     * @param key   the configuration key
     * @param value the configuration value
     * @return a CompletableFuture that completes when the configuration is updated
     */
    CompletableFuture<Boolean> setAsync(String key, Object value);

    /**
     * Checks if a configuration key exists.
     *
     * @param key the configuration key
     * @return true if the key exists
     */
    boolean contains(String key);

    /**
     * Gets all configuration properties.
     *
     * @return a map of all configuration properties
     */
    Map<String, Object> getAll();

    /**
     * Gets all configuration properties starting with a prefix.
     *
     * @param prefix the key prefix
     * @return a map of configuration properties with the given prefix
     */
    Map<String, Object> getPropertiesWithPrefix(String prefix);

    /**
     * Refreshes the configuration from sources.
     *
     * @return true if the configuration was refreshed successfully
     */
    boolean refresh();

    /**
     * Refreshes the configuration from sources asynchronously.
     *
     * @return a CompletableFuture that completes when the configuration is refreshed
     */
    CompletableFuture<Boolean> refreshAsync();

    /**
     * Adds a configuration change listener.
     *
     * @param key      the configuration key to listen for, or null for all keys
     * @param listener the change listener
     */
    void addChangeListener(String key, ConfigurationChangeListener listener);

    /**
     * Removes a configuration change listener.
     *
     * @param listener the change listener to remove
     */
    void removeChangeListener(ConfigurationChangeListener listener);

    /**
     * Validates a configuration value.
     *
     * @param key   the configuration key
     * @param value the configuration value
     * @return the validation result
     */
    ValidationResult validate(String key, Object value);

    /**
     * Gets the configuration metadata.
     *
     * @param key the configuration key
     * @return the configuration metadata
     */
    ConfigurationMetadata getMetadata(String key);

    /**
     * Registers metadata for a configuration key so that advanced features (refresh intervals, caching policies) can be applied.
     *
     * @param metadata the metadata describing the configuration key
     */
    void registerMetadata(ConfigurationMetadata metadata);

    /**
     * Gets the configuration statistics.
     *
     * @return the configuration statistics
     */
    ConfigurationStatistics getStatistics();

    /**
     * Shuts down the configuration service.
     */
    void shutdown();

    /**
     * Interface for configuration change listeners.
     */
    @FunctionalInterface
    interface ConfigurationChangeListener {

        /**
         * Called when a configuration value changes.
         *
         * @param key      the configuration key
         * @param oldValue the old value
         * @param newValue the new value
         */
        void onChange(String key, Object oldValue, Object newValue);

    }

    /**
     * Configuration statistics.
     */
    interface ConfigurationStatistics {

        long getTotalRequests();

        long getCacheHits();

        long getCacheMisses();

        double getCacheHitRate();

        long getTotalChanges();

        long getLastRefreshTime();

        int getActiveListeners();

    }

    /**
     * Configuration validation result.
     */
    final class ValidationResult {

        private final boolean valid;
        private final String errorMessage;
        private final Object normalizedValue;

        private ValidationResult(boolean valid, String errorMessage, Object normalizedValue) {
            this.valid = valid;
            this.errorMessage = errorMessage;
            this.normalizedValue = normalizedValue;
        }

        public static ValidationResult valid() {
            return new ValidationResult(true, null, null);
        }

        public static ValidationResult valid(Object normalizedValue) {
            return new ValidationResult(true, null, normalizedValue);
        }

        public static ValidationResult invalid(String errorMessage) {
            return new ValidationResult(false, errorMessage, null);
        }

        public boolean isValid() {
            return valid;
        }

        public String getErrorMessage() {
            return errorMessage;
        }

        public Object getNormalizedValue() {
            return normalizedValue;
        }

    }

    /**
     * Configuration metadata.
     */
    final class ConfigurationMetadata {

        private final String key;
        private final String description;
        private final String defaultValue;
        private final String source;
        private final boolean required;
        private final String type;
        private final String[] validationRules;
        private final String version;
        private final long refreshIntervalMillis;
        private final boolean cacheEnabled;
        private final boolean watchChanges;

        public ConfigurationMetadata(String key,
                                     String description,
                                     String defaultValue,
                                     String source,
                                     boolean required,
                                     String type,
                                     String[] validationRules,
                                     String version,
                                     long refreshIntervalMillis,
                                     boolean cacheEnabled,
                                     boolean watchChanges) {
            this.key = key;
            this.description = description;
            this.defaultValue = defaultValue;
            this.source = source;
            this.required = required;
            this.type = type;
            this.validationRules = validationRules;
            this.version = version;
            this.refreshIntervalMillis = refreshIntervalMillis;
            this.cacheEnabled = cacheEnabled;
            this.watchChanges = watchChanges;
        }

        public String getKey() {
            return key;
        }

        public String getDescription() {
            return description;
        }

        public String getDefaultValue() {
            return defaultValue;
        }

        public String getSource() {
            return source;
        }

        public boolean isRequired() {
            return required;
        }

        public String getType() {
            return type;
        }

        public String[] getValidationRules() {
            return validationRules;
        }

        public String getVersion() {
            return version;
        }

        public long getRefreshIntervalMillis() {
            return refreshIntervalMillis;
        }

        public boolean isCacheEnabled() {
            return cacheEnabled;
        }

        public boolean isWatchChanges() {
            return watchChanges;
        }

    }

}
