package net.legacy.library.aop.config;

import lombok.Getter;

import java.util.Map;

/**
 * Interface for configuration sources.
 *
 * <p>This interface provides methods for loading configuration data from various
 * sources such as files, databases, environment variables, and remote services.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface ConfigurationSource {

    /**
     * Gets the name of the configuration source.
     *
     * @return the source name
     */
    String getName();

    /**
     * Gets the priority of the configuration source.
     *
     * <p>Higher priority sources override lower priority sources.
     *
     * @return the priority (higher values have higher priority)
     */
    int getPriority();

    /**
     * Loads all configuration properties from this source.
     *
     * @return a map of configuration properties
     */
    Map<String, String> load();

    /**
     * Loads all configuration properties from this source asynchronously.
     *
     * @return a CompletableFuture that completes with the configuration properties
     */
    java.util.concurrent.CompletableFuture<Map<String, String>> loadAsync();

    /**
     * Checks if this source supports monitoring for changes.
     *
     * @return true if the source supports change monitoring
     */
    boolean supportsMonitoring();

    /**
     * Starts monitoring for configuration changes.
     *
     * @param listener the change listener
     */
    default void startMonitoring(ConfigurationChangeListener listener) {
        java.util.Objects.requireNonNull(listener, "listener");
        throw new UnsupportedOperationException("Monitoring is not supported by " + getName());
    }

    /**
     * Stops monitoring for configuration changes.
     */
    default void stopMonitoring() {
        throw new UnsupportedOperationException("Monitoring is not supported by " + getName());
    }

    /**
     * Checks if this source supports writing configuration changes.
     *
     * @return true if the source supports writing
     */
    boolean supportsWriting();

    /**
     * Saves a configuration property to this source.
     *
     * @param key the configuration key
     * @param value the configuration value
     * @return true if the property was saved successfully
     */
    boolean save(String key, String value);

    /**
     * Deletes a configuration property from this source.
     *
     * @param key the configuration key
     * @return true if the property was deleted successfully
     */
    boolean delete(String key);

    /**
     * Gets the metadata for this configuration source.
     *
     * @return the source metadata
     */
    SourceMetadata getMetadata();

    /**
     * Interface for configuration change listeners.
     */
    @FunctionalInterface
    interface ConfigurationChangeListener {

        /**
         * Called when configuration properties change.
         *
         * @param changes the map of changed properties (key -> new value)
         */
        void onChange(Map<String, String> changes);

    }

    /**
     * Metadata for configuration sources.
     */
    @Getter
    final class SourceMetadata {

        private final String name;
        private final String description;
        private final String type;
        private final boolean readOnly;
        private final boolean monitorable;
        private final Map<String, String> properties;

        public SourceMetadata(String name,
                              String description,
                              String type,
                              boolean readOnly,
                              boolean monitorable,
                              Map<String, String> properties) {
            this.name = name;
            this.description = description;
            this.type = type;
            this.readOnly = readOnly;
            this.monitorable = monitorable;
            this.properties = properties;
        }

    }

}
