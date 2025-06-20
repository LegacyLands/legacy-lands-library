package net.legacy.library.aop.registry;

import lombok.Getter;
import lombok.ToString;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing aspects within a specific ClassLoader context.
 *
 * <p>This class provides a thread-safe registry for storing and retrieving aspect instances
 * and their associated metadata within the scope of a particular ClassLoader. Each registry
 * is isolated to its ClassLoader, ensuring that aspects from different plugins or modules
 * do not interfere with each other.
 *
 * @author qwq-dev
 * @version 1.0
 * @see java.util.concurrent.ConcurrentHashMap
 * @since 2025-06-20 18:45
 */
@Getter
@ToString
public class AspectRegistry {

    /**
     * The ClassLoader associated with this registry.
     *
     * <p>This ClassLoader defines the scope of isolation for this registry.
     * All aspects registered in this registry belong to this ClassLoader context.
     */
    private final ClassLoader classLoader;

    /**
     * Map storing aspect instances by their class type.
     *
     * <p>Uses {@link ConcurrentHashMap} to ensure thread-safe access to aspect instances.
     * The key is the aspect class, and the value is the aspect instance.
     */
    private final Map<Class<?>, Object> aspects = new ConcurrentHashMap<>();

    /**
     * Map storing arbitrary metadata associated with this registry.
     *
     * <p>Uses {@link ConcurrentHashMap} to ensure thread-safe access to metadata.
     * This can be used to store configuration, statistics, or other context-specific data.
     */
    private final Map<String, Object> metadata = new ConcurrentHashMap<>();

    /**
     * Constructs a new AspectRegistry for the specified ClassLoader.
     *
     * @param classLoader the ClassLoader that defines the scope of this registry.
     *                    Must not be {@code null}
     * @throws IllegalArgumentException if classLoader is {@code null}
     */
    public AspectRegistry(ClassLoader classLoader) {
        if (classLoader == null) {
            throw new IllegalArgumentException("ClassLoader cannot be null");
        }
        this.classLoader = classLoader;
    }

    /**
     * Registers an aspect instance with its class type.
     *
     * <p>If an aspect of the same class is already registered, it will be replaced
     * with the new instance. This operation is thread-safe.
     *
     * @param aspectClass    the class type of the aspect. Must not be {@code null}
     * @param aspectInstance the aspect instance to register. Must not be {@code null}
     * @throws IllegalArgumentException if aspectClass or aspectInstance is {@code null}
     * @throws ClassCastException       if aspectInstance is not assignable to aspectClass
     */
    public void registerAspect(Class<?> aspectClass, Object aspectInstance) {
        if (aspectClass == null) {
            throw new IllegalArgumentException("Aspect class cannot be null");
        }
        if (aspectInstance == null) {
            throw new IllegalArgumentException("Aspect instance cannot be null");
        }
        if (!aspectClass.isInstance(aspectInstance)) {
            throw new ClassCastException("Aspect instance must be assignable to aspect class");
        }

        aspects.put(aspectClass, aspectInstance);
    }

    /**
     * Retrieves an aspect instance by its class type.
     *
     * <p>This operation is thread-safe and will return the most recently registered
     * instance for the given class type.
     *
     * @param aspectClass the class type of the aspect to retrieve. Must not be {@code null}
     * @return the aspect instance, or {@code null} if no aspect of the specified type is registered
     * @throws IllegalArgumentException if aspectClass is {@code null}
     */
    public Object getAspect(Class<?> aspectClass) {
        if (aspectClass == null) {
            throw new IllegalArgumentException("Aspect class cannot be null");
        }
        return aspects.get(aspectClass);
    }

    /**
     * Retrieves an aspect instance by its class type with type safety.
     *
     * <p>This is a type-safe convenience method that casts the returned aspect
     * to the specified type.
     *
     * @param <T>         the type of the aspect
     * @param aspectClass the class type of the aspect to retrieve. Must not be {@code null}
     * @return the aspect instance cast to the specified type, or {@code null} if no aspect
     * of the specified type is registered
     * @throws IllegalArgumentException if aspectClass is {@code null}
     * @throws ClassCastException       if the stored aspect cannot be cast to the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getTypedAspect(Class<T> aspectClass) {
        Object aspect = getAspect(aspectClass);
        return aspect != null ? (T) aspect : null;
    }

    /**
     * Checks if an aspect of the specified type is registered.
     *
     * @param aspectClass the class type to check. Must not be {@code null}
     * @return true if an aspect of the specified type is registered, false otherwise
     * @throws IllegalArgumentException if aspectClass is {@code null}
     */
    public boolean hasAspect(Class<?> aspectClass) {
        if (aspectClass == null) {
            throw new IllegalArgumentException("Aspect class cannot be null");
        }
        return aspects.containsKey(aspectClass);
    }

    /**
     * Removes an aspect of the specified type from the registry.
     *
     * @param aspectClass the class type of the aspect to remove. Must not be {@code null}
     * @return the removed aspect instance, or {@code null} if no aspect of the specified type was registered
     * @throws IllegalArgumentException if aspectClass is {@code null}
     */
    public Object removeAspect(Class<?> aspectClass) {
        if (aspectClass == null) {
            throw new IllegalArgumentException("Aspect class cannot be null");
        }
        return aspects.remove(aspectClass);
    }

    /**
     * Sets metadata with the specified key and value.
     *
     * <p>If metadata with the same key already exists, it will be replaced.
     * This operation is thread-safe.
     *
     * @param key   the metadata key. Must not be {@code null}
     * @param value the metadata value. Can be {@code null}
     * @throws IllegalArgumentException if key is {@code null}
     */
    public void setMetadata(String key, Object value) {
        if (key == null) {
            throw new IllegalArgumentException("Metadata key cannot be null");
        }
        metadata.put(key, value);
    }

    /**
     * Retrieves metadata by its key.
     *
     * <p>This operation is thread-safe.
     *
     * @param key the metadata key. Must not be {@code null}
     * @return the metadata value, or null if no metadata with the specified key exists
     * @throws IllegalArgumentException if key is {@code null}
     */
    public Object getMetadata(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Metadata key cannot be null");
        }
        return metadata.get(key);
    }

    /**
     * Retrieves metadata by its key with type safety.
     *
     * <p>This is a type-safe convenience method that casts the returned metadata
     * to the specified type.
     *
     * @param <T>  the type of the metadata value
     * @param key  the metadata key. Must not be {@code null}.
     * @param type the expected type of the metadata value. Must not be {@code null}
     * @return the metadata value cast to the specified type, or null if no metadata
     * with the specified key exists
     * @throws IllegalArgumentException if key or type is null
     * @throws ClassCastException       if the stored metadata cannot be cast to the specified type
     */
    @SuppressWarnings("unchecked")
    public <T> T getMetadata(String key, Class<T> type) {
        if (type == null) {
            throw new IllegalArgumentException("Type cannot be null");
        }
        Object value = getMetadata(key);
        return value != null ? (T) value : null;
    }

    /**
     * Checks if metadata with the specified key exists.
     *
     * @param key the metadata key to check. Must not be {@code null}
     * @return true if metadata with the specified key exists, false otherwise
     * @throws IllegalArgumentException if key is null
     */
    public boolean hasMetadata(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Metadata key cannot be null");
        }
        return metadata.containsKey(key);
    }

    /**
     * Removes metadata with the specified key.
     *
     * @param key the metadata key to remove. Must not be {@code null}
     * @return the removed metadata value, or null if no metadata with the specified key existed
     * @throws IllegalArgumentException if key is null
     */
    public Object removeMetadata(String key) {
        if (key == null) {
            throw new IllegalArgumentException("Metadata key cannot be null");
        }
        return metadata.remove(key);
    }

    /**
     * Returns the number of registered aspects.
     *
     * @return the number of aspects in this registry
     */
    public int getAspectCount() {
        return aspects.size();
    }

    /**
     * Returns the number of metadata entries.
     *
     * @return the number of metadata entries in this registry
     */
    public int getMetadataCount() {
        return metadata.size();
    }

    /**
     * Checks if this registry is empty (contains no aspects and no metadata).
     *
     * @return true if the registry contains no aspects and no metadata, false otherwise
     */
    public boolean isEmpty() {
        return aspects.isEmpty() && metadata.isEmpty();
    }

    /**
     * Clears all aspects and metadata from this registry.
     *
     * <p>This method removes all registered aspects and metadata, effectively
     * resetting the registry to its initial empty state. This is useful for
     * resource cleanup when the associated ClassLoader is no longer needed.
     */
    public void clear() {
        aspects.clear();
        metadata.clear();
    }
}