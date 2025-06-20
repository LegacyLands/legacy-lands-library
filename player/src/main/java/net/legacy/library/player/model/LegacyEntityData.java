package net.legacy.library.player.model;

import com.github.benmanes.caffeine.cache.Cache;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Transient;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import org.bukkit.entity.Player;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Represents a generic entity within the Legacy library's entity framework.
 *
 * <p>This class manages customizable entity data with support for attributes,
 * relationships, and metadata. It ensures thread-safe access and provides
 * a flexible structure for modeling various entity types and their relationships.
 *
 * <p>Data is stored in thread-safe collections, allowing for concurrent operations.
 * The class includes utilities for managing entity attributes and relationships efficiently.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@Getter
@Entity("legacy-entity-data")
@RequiredArgsConstructor
public class LegacyEntityData {
    /**
     * The unique identifier for the entity.
     */
    @Id
    @NonNull
    private final UUID uuid;

    /**
     * A single-entity, in-memory cache that resides on the server and is not persisted to db.
     */
    @Transient
    private transient final CacheServiceInterface<Cache<String, String>, String> rawCache =
            CacheServiceFactory.createCaffeineCache();

    /**
     * A map containing the custom attributes associated with the entity.
     */
    private final Map<String, String> attributes = new ConcurrentHashMap<>();

    /**
     * A map of relationships this entity has with other entities.
     *
     * <p>Key: relationship type (e.g., "member", "owner")
     *
     * <p>Value: Set of entity UUIDs this entity has that relationship with
     */
    private final Map<String, Set<UUID>> relationships = new ConcurrentHashMap<>();

    /**
     * The type of this entity, used for classification and querying.
     */
    @Indexed
    private String entityType;

    /**
     * Version number for optimistic locking and concurrency control.
     *
     * <p>This field is incremented each time the entity is modified, allowing
     * detection of concurrent modifications across different servers.
     */
    @Setter
    private long version = 0;

    /**
     * Last modification timestamp in milliseconds since epoch.
     *
     * <p>Used to resolve conflicts when merging changes from different servers.
     */
    private long lastModifiedTime = System.currentTimeMillis();

    /**
     * No-args constructor for Morphia serialization/deserialization.
     *
     * <p>This constructor should only be used by the ORM framework.
     */
    protected LegacyEntityData() {
        this.uuid = null; // Will be overwritten during deserialization
    }

    /**
     * Creates a new {@link LegacyEntityData} instance with the specified entity type.
     *
     * @param uuid       the unique identifier for the entity
     * @param entityType the type of the entity
     * @return a new {@link LegacyEntityData} instance
     */
    public static LegacyEntityData of(UUID uuid, String entityType) {
        LegacyEntityData entity = new LegacyEntityData(uuid);
        entity.entityType = entityType;
        return entity;
    }

    /**
     * Creates a new {@link LegacyEntityData} instance for a player with type "player".
     *
     * @param player the Bukkit {@link Player} instance
     * @return a new {@link LegacyEntityData} instance associated with the player's UUID
     */
    public static LegacyEntityData ofPlayer(Player player) {
        return of(player.getUniqueId(), "player");
    }

    /**
     * Updates the version and last modified time of this entity.
     * This method should be called whenever the entity is modified.
     */
    public void updateVersionAndTimestamp() {
        this.version++;
        this.lastModifiedTime = System.currentTimeMillis();
    }

    /**
     * Sets the last modified time to the current time.
     */
    public void updateLastModifiedTime() {
        this.lastModifiedTime = System.currentTimeMillis();
    }

    /**
     * Adds an attribute to the entity.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return the current instance for method chaining
     */
    public LegacyEntityData addAttribute(String key, String value) {
        attributes.put(key, value);
        updateVersionAndTimestamp();
        return this;
    }

    /**
     * Adds multiple attributes to the entity.
     *
     * @param attributes the map containing the attributes to add
     * @return the current instance for method chaining
     */
    public LegacyEntityData addAttributes(Map<String, String> attributes) {
        this.attributes.putAll(attributes);
        updateVersionAndTimestamp();
        return this;
    }

    /**
     * Gets an attribute value.
     *
     * @param key the attribute key
     * @return the attribute value, or {@code null} if not present
     */
    public String getAttribute(String key) {
        return attributes.get(key);
    }

    /**
     * Gets an attribute with transformation.
     *
     * @param key      the attribute key
     * @param function the transformation function
     * @param <R>      the return type
     * @return the transformed value, or {@code null} if not present
     */
    public <R> R getAttribute(String key, Function<String, R> function) {
        String value = attributes.get(key);
        return value != null ? function.apply(value) : null;
    }

    /**
     * Removes an attribute from the entity.
     *
     * @param key the attribute key to remove
     * @return the current instance for method chaining
     */
    public LegacyEntityData removeAttribute(String key) {
        attributes.remove(key);
        updateVersionAndTimestamp();
        return this;
    }

    /**
     * Adds a relationship between this entity and another entity.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity
     * @return the current instance for method chaining
     */
    public LegacyEntityData addRelationship(String relationshipType, UUID targetEntityUuid) {
        relationships.computeIfAbsent(relationshipType, k -> ConcurrentHashMap.newKeySet())
                .add(targetEntityUuid);
        updateVersionAndTimestamp();
        return this;
    }

    /**
     * Removes a relationship between this entity and another entity.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity
     * @return the current instance for method chaining
     */
    public LegacyEntityData removeRelationship(String relationshipType, UUID targetEntityUuid) {
        Set<UUID> relatedEntities = relationships.get(relationshipType);
        if (relatedEntities != null) {
            relatedEntities.remove(targetEntityUuid);
            updateVersionAndTimestamp();
        }
        return this;
    }

    /**
     * Checks if this entity has a specific relationship with another entity.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity
     * @return true if the relationship exists, false otherwise
     */
    public boolean hasRelationship(String relationshipType, UUID targetEntityUuid) {
        Set<UUID> relatedEntities = relationships.get(relationshipType);
        return relatedEntities != null && relatedEntities.contains(targetEntityUuid);
    }

    /**
     * Gets all entities related to this entity by a specific relationship type.
     *
     * @param relationshipType the type of relationship
     * @return a set of entity UUIDs, or an empty set if none
     */
    public Set<UUID> getRelatedEntities(String relationshipType) {
        return relationships.getOrDefault(relationshipType, ConcurrentHashMap.newKeySet());
    }

    /**
     * Counts the number of relationships of a specific type.
     *
     * @param relationshipType the type of relationship to count
     * @return the number of relationships of the specified type
     */
    public int countRelationships(String relationshipType) {
        Set<UUID> relatedEntities = relationships.get(relationshipType);
        return relatedEntities != null ? relatedEntities.size() : 0;
    }

    /**
     * Removes all relationships of a specific type.
     *
     * @param relationshipType the type of relationship to remove
     * @return the current instance for method chaining
     */
    public LegacyEntityData clearRelationships(String relationshipType) {
        relationships.remove(relationshipType);
        updateVersionAndTimestamp();
        return this;
    }

    /**
     * Merges changes from another entity of the same UUID.
     *
     * <p>This method is used for conflict resolution when updates from
     * different servers need to be merged.
     *
     * @param other the other entity to merge changes from
     * @return true if any changes were merged, false otherwise
     */
    public boolean mergeChangesFrom(LegacyEntityData other) {
        if (!this.uuid.equals(other.uuid)) {
            return false;
        }

        boolean changed = false;

        // Merge attributes
        for (Map.Entry<String, String> entry : other.attributes.entrySet()) {
            String key = entry.getKey();
            String otherValue = entry.getValue();
            String currentValue = this.attributes.get(key);

            if (currentValue == null || !currentValue.equals(otherValue)) {
                this.attributes.put(key, otherValue);
                changed = true;
            }
        }

        /*
         * Check for removed attributes.
         * If the remote entity is missing attributes that are present in the local entity,
         * we need to decide whether to keep them based on version comparison
         */
        if (other.version >= this.version) {
            Set<String> localKeys = new HashSet<>(this.attributes.keySet());
            Set<String> remoteKeys = new HashSet<>(other.attributes.keySet());

            // Find keys that exist locally but not in the remote entity
            localKeys.removeAll(remoteKeys);

            /*
             * If the remote version is newer or equal, and it doesn't have these keys,
             * they were likely removed deliberately, so remove them locally too
             */
            if (!localKeys.isEmpty()) {
                for (String key : localKeys) {
                    this.attributes.remove(key);
                    changed = true;
                }
            }
        }

        // Merge relationships
        for (Map.Entry<String, Set<UUID>> entry : other.relationships.entrySet()) {
            String relationshipType = entry.getKey();
            Set<UUID> otherEntities = entry.getValue();
            Set<UUID> currentEntities = this.relationships.get(relationshipType);

            if (currentEntities == null) {
                Set<UUID> newSet = ConcurrentHashMap.newKeySet(otherEntities.size());
                newSet.addAll(otherEntities);
                this.relationships.put(relationshipType, newSet);
                changed = true;
            } else {
                for (UUID entityUuid : otherEntities) {
                    if (!currentEntities.contains(entityUuid)) {
                        currentEntities.add(entityUuid);
                        changed = true;
                    }
                }
            }
        }

        /*
         * Check for removed relationships.
         * Similar to attributes, if relationships exist locally but not remotely,
         * they might have been removed deliberately
         */
        if (other.version >= this.version) {
            // Check for relationship types that exist locally but not remotely
            Set<String> localRelTypes = new HashSet<>(this.relationships.keySet());
            Set<String> remoteRelTypes = new HashSet<>(other.relationships.keySet());
            Set<String> removedRelTypes = new HashSet<>(localRelTypes);
            removedRelTypes.removeAll(remoteRelTypes);

            // Remove relationship types that don't exist remotely
            for (String relType : removedRelTypes) {
                this.relationships.remove(relType);
                changed = true;
            }

            // For relationship types that exist in both, check for removed entities
            for (String relType : localRelTypes) {
                if (remoteRelTypes.contains(relType)) {
                    Set<UUID> localEntities = this.relationships.get(relType);
                    Set<UUID> remoteEntities = other.relationships.get(relType);

                    if (localEntities != null && remoteEntities != null) {
                        Set<UUID> toRemove = new HashSet<>(localEntities);
                        toRemove.removeAll(remoteEntities);

                        if (!toRemove.isEmpty()) {
                            for (UUID entityId : toRemove) {
                                localEntities.remove(entityId);
                                changed = true;
                            }
                        }
                    }
                }
            }
        }

        if (changed) {
            if (other.lastModifiedTime > this.lastModifiedTime) {
                this.lastModifiedTime = other.lastModifiedTime;
            }
            this.version = Math.max(this.version, other.version) + 1;
        }

        return changed;
    }
}