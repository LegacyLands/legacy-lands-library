package net.legacy.library.aop.config;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.Getter;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Default implementation of ConfigurationService.
 *
 * <p>This implementation provides comprehensive configuration management with support
 * for multiple sources, hot-reloading, validation, and change monitoring.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
@InjectableComponent
public class DefaultConfigurationService implements ConfigurationService {

    private static final String GLOBAL_LISTENER_KEY = "__GLOBAL__";

    private final List<ConfigurationSource> sources = new CopyOnWriteArrayList<>();
    private final Map<String, Object> configurationCache = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationMetadata> metadataCache = new ConcurrentHashMap<>();
    private final Map<String, ConfigurationMetadata> registeredMetadata = new ConcurrentHashMap<>();
    private final Map<String, List<ConfigurationChangeListener>> changeListeners = new ConcurrentHashMap<>();
    private final ReentrantReadWriteLock lock = new ReentrantReadWriteLock();

    private final ScheduledExecutorService refreshExecutor = Executors.newScheduledThreadPool(2);
    private final Map<String, ScheduledFuture<?>> refreshTasks = new ConcurrentHashMap<>();

    // Statistics
    private final AtomicLong totalRequests = new AtomicLong(0);
    private final AtomicLong cacheHits = new AtomicLong(0);
    private final AtomicLong totalChanges = new AtomicLong(0);
    private final AtomicLong lastRefreshTime = new AtomicLong(0);

    /**
     * Default constructor that initializes with basic configuration sources.
     */
    public DefaultConfigurationService() {
        addConfigurationSource(new SystemPropertiesConfigurationSource());
    }

    @Override
    public void registerMetadata(ConfigurationMetadata metadata) {
        Objects.requireNonNull(metadata, "metadata");
        registeredMetadata.put(metadata.getKey(), metadata);
        metadataCache.put(metadata.getKey(), metadata);
        scheduleRefresh(metadata);
    }

    /**
     * Adds a configuration source to the service.
     *
     * @param source the configuration source to add
     */
    public void addConfigurationSource(ConfigurationSource source) {
        if (source != null && !sources.contains(source)) {
            sources.add(source);
            Log.info("Added configuration source: %s (priority: %d)", source.getName(), source.getPriority());

            if (source.supportsMonitoring()) {
                source.startMonitoring(changes -> onSourceChange(source, changes));
            }
        }
    }

    /**
     * Removes a configuration source from the service.
     *
     * @param source the configuration source to remove
     */
    public void removeConfigurationSource(ConfigurationSource source) {
        if (sources.remove(source)) {
            Log.info("Removed configuration source: %s", source.getName());
        }
    }

    @Override
    public <T> T get(String key, Class<T> type) {
        return get(key, type, null);
    }

    @Override
    public <T> T get(String key, Class<T> type, T defaultValue) {
        totalRequests.incrementAndGet();

        ConfigurationMetadata metadata = getMetadata(key);
        boolean cacheEnabled = metadata.isCacheEnabled();

        try {
            lock.readLock().lock();

            // Check cache first when enabled
            if (cacheEnabled && configurationCache.containsKey(key)) {
                cacheHits.incrementAndGet();
                return type.cast(configurationCache.get(key));
            }

            // Load from sources
            Object value = loadFromSources(key);
            if (value == null) {
                return defaultValue;
            }

            // Validate and convert the value
            ValidationResult validationResult = validate(key, value);
            if (validationResult.isValid()) {
                // Use normalized value if available
                Object finalValue = validationResult.getNormalizedValue() != null
                        ? validationResult.getNormalizedValue()
                        : value;

                // Convert to target type
                T convertedValue = convertValue(finalValue, type);

                // Cache the value when enabled
                if (cacheEnabled) {
                    configurationCache.put(key, convertedValue);
                } else {
                    configurationCache.remove(key);
                }

                return convertedValue;
            }

            Log.warn("Configuration validation failed for key '%s': %s", key, validationResult.getErrorMessage());
            return defaultValue;

        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public <T> CompletableFuture<T> getAsync(String key, Class<T> type) {
        return CompletableFuture.supplyAsync(() -> get(key, type));
    }

    @Override
    public boolean set(String key, Object value) {
        try {
            lock.writeLock().lock();

            // Validate the value
            ValidationResult validationResult = validate(key, value);
            if (validationResult.isValid()) {
                Object oldValue = configurationCache.get(key);
                Object newValue = validationResult.getNormalizedValue() != null
                        ? validationResult.getNormalizedValue()
                        : value;

                // Set in all writable sources
                boolean success = false;
                for (ConfigurationSource source : sources) {
                    if (source.supportsWriting()) {
                        success = source.save(key, String.valueOf(newValue)) || success;
                    }
                }

                if (success) {
                    configurationCache.put(key, newValue);
                    notifyChangeListeners(key, oldValue, newValue);
                    totalChanges.incrementAndGet();
                }

                return success;
            }

            Log.warn("Cannot set configuration '%s': %s", key, validationResult.getErrorMessage());
            return false;

        } finally {
            lock.writeLock().unlock();
        }
    }

    @Override
    public CompletableFuture<Boolean> setAsync(String key, Object value) {
        return CompletableFuture.supplyAsync(() -> set(key, value));
    }

    @Override
    public boolean contains(String key) {
        return configurationCache.containsKey(key) || loadFromSources(key) != null;
    }

    @Override
    public Map<String, Object> getAll() {
        try {
            lock.readLock().lock();
            return new HashMap<>(configurationCache);
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public Map<String, Object> getPropertiesWithPrefix(String prefix) {
        try {
            lock.readLock().lock();
            Map<String, Object> result = new HashMap<>();
            configurationCache.forEach((key, value) -> {
                if (key.startsWith(prefix)) {
                    result.put(key, value);
                }
            });
            return result;
        } finally {
            lock.readLock().unlock();
        }
    }

    @Override
    public boolean refresh() {
        try {
            Map<String, Object> previousSnapshot;
            lock.readLock().lock();
            try {
                previousSnapshot = new HashMap<>(configurationCache);
            } finally {
                lock.readLock().unlock();
            }

            Map<String, String> aggregatedProperties = new HashMap<>();
            for (ConfigurationSource source : sources) {
                aggregatedProperties.putAll(source.load());
            }

            aggregatedProperties.forEach((key, stringValue) -> {
                try {
                    Object rawValue = convertStringToValue(stringValue);
                    ConfigurationMetadata metadata = getMetadata(key);
                    Object finalValue = convertForMetadata(key, rawValue, metadata);
                    Object previousValue = updateCachedValue(key, finalValue, metadata.isCacheEnabled());
                    previousSnapshot.remove(key);
                    if (!Objects.equals(previousValue, finalValue)) {
                        totalChanges.incrementAndGet();
                        notifyChangeListeners(key, previousValue, finalValue);
                    }
                } catch (Exception exception) {
                    Log.warn("Failed to refresh configuration '%s': %s", key, exception.getMessage());
                }
            });

            previousSnapshot.forEach((removedKey, oldValue) -> {
                ConfigurationMetadata metadata = getMetadata(removedKey);
                Object previousValue = updateCachedValue(removedKey, null, metadata.isCacheEnabled());
                if (previousValue != null) {
                    totalChanges.incrementAndGet();
                    notifyChangeListeners(removedKey, previousValue, null);
                }
            });

            lastRefreshTime.set(System.currentTimeMillis());
            return true;
        } catch (Exception exception) {
            Log.error("Failed to refresh configuration: %s", exception.getMessage());
            return false;
        }
    }

    @Override
    public CompletableFuture<Boolean> refreshAsync() {
        return CompletableFuture.supplyAsync(this::refresh);
    }

    @Override
    public void addChangeListener(String key, ConfigurationChangeListener listener) {
        String listenerKey = key != null ? key : GLOBAL_LISTENER_KEY;
        changeListeners.computeIfAbsent(listenerKey, k -> new CopyOnWriteArrayList<>()).add(listener);
    }

    @Override
    public void removeChangeListener(ConfigurationChangeListener listener) {
        changeListeners.values().forEach(listeners -> listeners.remove(listener));
    }

    @Override
    public ValidationResult validate(String key, Object value) {
        ConfigurationMetadata metadata = getMetadata(key);
        if (metadata == null) {
            return ValidationResult.valid();
        }

        // Check if value is required
        if (metadata.isRequired() && value == null) {
            return ValidationResult.invalid("Configuration '" + key + "' is required");
        }

        // Apply validation rules
        for (String rule : metadata.getValidationRules()) {
            ValidationResult result = applyValidationRule(key, value, rule);
            if (result.isValid()) {
                continue;
            }
            return result;
        }

        return ValidationResult.valid();
    }

    @Override
    public ConfigurationMetadata getMetadata(String key) {
        ConfigurationMetadata metadata = registeredMetadata.get(key);
        if (metadata != null) {
            scheduleRefresh(metadata);
            return metadata;
        }

        metadata = metadataCache.computeIfAbsent(key, this::createMetadataFromAnnotation);
        scheduleRefresh(metadata);
        return metadata;
    }

    @Override
    public ConfigurationStatistics getStatistics() {
        return new DefaultConfigurationStatistics(
                totalRequests.get(),
                cacheHits.get(),
                totalRequests.get() - cacheHits.get(),
                totalChanges.get(),
                lastRefreshTime.get(),
                changeListeners.values().stream().mapToInt(List::size).sum()
        );
    }

    @Override
    public void shutdown() {
        refreshTasks.values().forEach(future -> future.cancel(true));
        refreshTasks.clear();
        refreshExecutor.shutdown();
        sources.stream()
                .filter(Objects::nonNull)
                .forEach(source -> {
                    if (!source.supportsMonitoring()) {
                        return;
                    }
                    try {
                        source.stopMonitoring();
                    } catch (UnsupportedOperationException unsupportedOperationException) {
                        Log.info("Skipping monitoring shutdown for source '%s': %s", source.getName(), unsupportedOperationException.getMessage());
                    } catch (Exception exception) {
                        Log.warn("Failed to stop monitoring for source '%s': %s", source.getName(), exception.getMessage());
                    }
                });
        registeredMetadata.clear();
        metadataCache.clear();
    }

    /**
     * Loads a configuration value from all sources.
     *
     * @param key the configuration key
     * @return the configuration value, or null if not found
     */
    private Object loadFromSources(String key) {
        // Sort sources by priority (highest first)
        List<ConfigurationSource> sortedSources = new ArrayList<>(sources);
        sortedSources.sort((s1, s2) -> Integer.compare(s2.getPriority(), s1.getPriority()));

        for (ConfigurationSource source : sortedSources) {
            Map<String, String> properties = source.load();
            if (properties.containsKey(key)) {
                return convertStringToValue(properties.get(key));
            }
        }

        return null;
    }

    /**
     * Converts a string value to the appropriate type.
     *
     * @param stringValue the string value
     * @return the converted value
     */
    private Object convertStringToValue(String stringValue) {
        if (stringValue == null) {
            return null;
        }

        // Try to convert to appropriate type
        if (stringValue.equalsIgnoreCase("true") || stringValue.equalsIgnoreCase("false")) {
            return Boolean.parseBoolean(stringValue);
        }

        try {
            return Integer.parseInt(stringValue);
        } catch (NumberFormatException exception) {
            // Not an integer
        }

        try {
            return Long.parseLong(stringValue);
        } catch (NumberFormatException exception) {
            // Not a long
        }

        try {
            return Double.parseDouble(stringValue);
        } catch (NumberFormatException exception) {
            // Not a double
        }

        return stringValue;
    }

    /**
     * Converts a value to the target type.
     *
     * @param value the value to convert
     * @param type  the target type
     * @param <T>   the type parameter
     * @return the converted value
     */
    @SuppressWarnings("unchecked")
    private <T> T convertValue(Object value, Class<T> type) {
        if (value == null) {
            return null;
        }

        if (type.isInstance(value)) {
            return (T) value;
        }

        // Handle common type conversions
        if (type == String.class) {
            return (T) String.valueOf(value);
        } else if (type == Integer.class || type == int.class) {
            return (T) Integer.valueOf(String.valueOf(value));
        } else if (type == Long.class || type == long.class) {
            return (T) Long.valueOf(String.valueOf(value));
        } else if (type == Double.class || type == double.class) {
            return (T) Double.valueOf(String.valueOf(value));
        } else if (type == Boolean.class || type == boolean.class) {
            return (T) Boolean.valueOf(String.valueOf(value));
        }

        throw new IllegalArgumentException("Cannot convert value " + value + " to type " + type);
    }

    /**
     * Applies a validation rule to a configuration value.
     *
     * @param key   the configuration key
     * @param value the value to validate
     * @param rule  the validation rule
     * @return the validation result
     */
    private ValidationResult applyValidationRule(String key, Object value, String rule) {
        if (value == null) {
            return ValidationResult.valid();
        }

        String[] parts = rule.split("=", 2);
        if (parts.length != 2) {
            return ValidationResult.invalid("Invalid validation rule format: " + rule);
        }

        String ruleType = parts[0];
        String ruleValue = parts[1];

        try {
            switch (ruleType) {
                case "min":
                    double minValue = Double.parseDouble(ruleValue);
                    double doubleValue = ((Number) value).doubleValue();
                    if (doubleValue < minValue) {
                        return ValidationResult.invalid("Configuration '" + key + "' must be >= " + minValue);
                    }
                    break;

                case "max":
                    double maxValue = Double.parseDouble(ruleValue);
                    double valueDouble = ((Number) value).doubleValue();
                    if (valueDouble > maxValue) {
                        return ValidationResult.invalid("Configuration '" + key + "' must be <= " + maxValue);
                    }
                    break;

                case "regex":
                    String stringValue = String.valueOf(value);
                    if (!stringValue.matches(ruleValue)) {
                        return ValidationResult.invalid("Configuration '" + key + "' does not match pattern: " + ruleValue);
                    }
                    break;

                default:
                    Log.warn("Unknown validation rule type: %s", ruleType);
            }
        } catch (Exception exception) {
            return ValidationResult.invalid("Invalid validation rule value: " + rule);
        }

        return ValidationResult.valid();
    }

    /**
     * Creates metadata from @DynamicConfig annotation.
     *
     * @param key the configuration key
     * @return the configuration metadata
     */
    private ConfigurationMetadata createMetadataFromAnnotation(String key) {
        // This is a simplified implementation
        // In a real implementation, you would scan classes for @DynamicConfig annotations
        return new ConfigurationMetadata(
                key,
                "Dynamic configuration for " + key,
                "",
                "dynamic",
                false,
                "String",
                new String[0],
                "1.0",
                0L,
                true,
                false
        );
    }

    /**
     * Notifies change listeners of a configuration change.
     *
     * @param key      the configuration key
     * @param oldValue the old value
     * @param newValue the new value
     */
    private void notifyChangeListeners(String key, Object oldValue, Object newValue) {
        if (key == null) {
            return;
        }

        List<ConfigurationChangeListener> listeners = changeListeners.get(key);
        if (listeners != null) {
            listeners.forEach(listener -> {
                try {
                    listener.onChange(key, oldValue, newValue);
                } catch (Exception exception) {
                    Log.error("Error in configuration change listener for key '%s': %s", key, exception.getMessage());
                }
            });
        }

        // Also notify global listeners (null key)
        List<ConfigurationChangeListener> globalListeners = changeListeners.get(GLOBAL_LISTENER_KEY);
        if (globalListeners != null) {
            globalListeners.forEach(listener -> {
                try {
                    listener.onChange(key, oldValue, newValue);
                } catch (Exception exception) {
                    Log.error("Error in global configuration change listener for key '%s': %s", key, exception.getMessage());
                }
            });
        }
    }

    private void onSourceChange(ConfigurationSource source, Map<String, String> changes) {
        if (changes == null || changes.isEmpty()) {
            return;
        }

        Map<String, Object> changedValues = new HashMap<>();

        try {
            lock.writeLock().lock();
            changes.forEach((key, rawValue) -> {
                Object normalizedValue = convertStringToValue(rawValue);
                Object previousValue = configurationCache.put(key, normalizedValue);
                changedValues.put(key, previousValue);
            });
        } finally {
            lock.writeLock().unlock();
        }

        changedValues.forEach((key, oldValue) -> {
            Object newValue = configurationCache.get(key);
            notifyChangeListeners(key, oldValue, newValue);
        });

        Log.info("Applied %d configuration change(s) from source %s", changes.size(), source.getName());
    }

    private Object convertForMetadata(String key, Object value, ConfigurationMetadata metadata) {
        if (value == null || metadata == null) {
            return value;
        }

        String targetTypeName = metadata.getType();
        if (targetTypeName == null || targetTypeName.isBlank()) {
            return value;
        }

        try {
            Class<?> targetType = resolveType(targetTypeName);
            @SuppressWarnings("unchecked")
            Class<Object> castTarget = (Class<Object>) targetType;
            return convertValue(value, castTarget);
        } catch (IllegalArgumentException exception) {
            Log.warn("Failed to convert configuration '%s' to type %s: %s", key, targetTypeName, exception.getMessage());
            return value;
        }
    }

    private Class<?> resolveType(String typeName) {
        return switch (typeName) {
            case "String" -> String.class;
            case "Integer", "int" -> Integer.class;
            case "Long", "long" -> Long.class;
            case "Double", "double" -> Double.class;
            case "Boolean", "boolean" -> Boolean.class;
            default -> {
                try {
                    yield Class.forName(typeName);
                } catch (ClassNotFoundException exception) {
                    throw new IllegalArgumentException("Unknown configuration type: " + typeName, exception);
                }
            }
        };
    }

    private Object updateCachedValue(String key, Object value, boolean cacheEnabled) {
        lock.writeLock().lock();
        try {
            if (cacheEnabled) {
                if (value == null) {
                    return configurationCache.remove(key);
                }
                return configurationCache.put(key, value);
            }
            return configurationCache.remove(key);
        } finally {
            lock.writeLock().unlock();
        }
    }

    private void scheduleRefresh(ConfigurationMetadata metadata) {
        if (metadata == null) {
            return;
        }

        long interval = metadata.getRefreshIntervalMillis();
        String key = metadata.getKey();
        if (key == null) {
            return;
        }

        ScheduledFuture<?> existing = refreshTasks.remove(key);
        if (existing != null) {
            existing.cancel(true);
        }

        if (interval <= 0) {
            return;
        }

        Runnable task = () -> {
            ConfigurationMetadata activeMetadata = registeredMetadata.getOrDefault(key, metadata);
            refreshKey(activeMetadata);
        };

        ScheduledFuture<?> scheduledFuture = refreshExecutor.scheduleAtFixedRate(
                task,
                interval,
                interval,
                TimeUnit.MILLISECONDS
        );
        refreshTasks.put(key, scheduledFuture);
    }

    private void refreshKey(ConfigurationMetadata metadata) {
        if (metadata == null) {
            return;
        }

        String key = metadata.getKey();
        if (key == null) {
            return;
        }
        try {
            Object value = loadFromSources(key);
            if (value == null) {
                return;
            }

            ValidationResult validationResult = validate(key, value);
            if (validationResult.isValid()) {
                Object resolvedValue = validationResult.getNormalizedValue() != null
                        ? validationResult.getNormalizedValue()
                        : value;

                Object finalValue = convertForMetadata(key, resolvedValue, metadata);
                Object previousValue = updateCachedValue(key, finalValue, metadata.isCacheEnabled());

                if (!Objects.equals(previousValue, finalValue)) {
                    totalChanges.incrementAndGet();
                    notifyChangeListeners(key, previousValue, finalValue);
                }
                return;
            }

            Log.warn("Configuration validation failed during refresh for key '%s': %s", key, validationResult.getErrorMessage());
        } catch (Exception exception) {
            Log.warn("Failed to auto-refresh configuration '%s': %s", key, exception.getMessage());
        }
    }

    /**
     * Default implementation of ConfigurationStatistics.
     */
    private static class DefaultConfigurationStatistics implements ConfigurationStatistics {

        private final long totalRequests;
        private final long cacheHits;
        private final long cacheMisses;
        private final long totalChanges;
        private final long lastRefreshTime;
        private final int activeListeners;

        private DefaultConfigurationStatistics(long totalRequests,
                                               long cacheHits,
                                               long cacheMisses,
                                               long totalChanges,
                                               long lastRefreshTime,
                                               int activeListeners) {
            this.totalRequests = totalRequests;
            this.cacheHits = cacheHits;
            this.cacheMisses = cacheMisses;
            this.totalChanges = totalChanges;
            this.lastRefreshTime = lastRefreshTime;
            this.activeListeners = activeListeners;
        }

        @Override
        public long getTotalRequests() {
            return totalRequests;
        }

        @Override
        public long getCacheHits() {
            return cacheHits;
        }

        @Override
        public long getCacheMisses() {
            return cacheMisses;
        }

        @Override
        public double getCacheHitRate() {
            return totalRequests == 0 ? 0.0 : (double) cacheHits / totalRequests;
        }

        @Override
        public long getTotalChanges() {
            return totalChanges;
        }

        @Override
        public long getLastRefreshTime() {
            return lastRefreshTime;
        }

        @Override
        public int getActiveListeners() {
            return activeListeners;
        }

    }

}
