package net.legacy.library.player.model;

import com.github.benmanes.caffeine.cache.Cache;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.Transient;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import org.bukkit.entity.Player;

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
     * Key: relationship type (e.g., "member", "owner")
     * Value: Set of entity UUIDs this entity has that relationship with
     */
    private final Map<String, Set<UUID>> relationships = new ConcurrentHashMap<>();

    /**
     * The type of this entity, used for classification and querying.
     */
    @Indexed
    private String entityType;

    /**
     * No-args constructor for Morphia serialization/deserialization.
     * This constructor should only be used by the ORM framework.
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
     * Adds an attribute to the entity.
     *
     * @param key   the attribute key
     * @param value the attribute value
     * @return the current instance for method chaining
     */
    public LegacyEntityData addAttribute(String key, String value) {
        attributes.put(key, value);
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
        return this;
    }

    /**
     * Gets an attribute value.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not present
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
     * @return the transformed value, or null if not present
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
        return this;
    }
} 