package net.legacy.library.player.model;

import com.github.benmanes.caffeine.cache.Cache;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.annotations.Indexed;
import dev.morphia.annotations.PostLoad;
import dev.morphia.annotations.Transient;
import lombok.AccessLevel;
import lombok.Getter;
import lombok.NonNull;
import lombok.RequiredArgsConstructor;
import lombok.Setter;
import net.legacy.library.cache.factory.CacheServiceFactory;
import net.legacy.library.cache.service.CacheServiceInterface;
import org.apache.commons.lang3.tuple.Pair;
import org.bukkit.entity.Player;

import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;
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
 * <p>Every attribute and relationship carries a stamp of its last change: the entity version the change made, its
 * time, and the server it was made on. Copies of an entity, on this server or another, are merged one attribute and
 * one relationship at a time, the later stamp winning, so a merge keeps every change either side made and settles
 * the same way in any order. A removal leaves its stamp behind, so it is merged like any other change.
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
     * Identifies the server this class was loaded on, in the stamps of the changes made here.
     */
    public static final String LOCAL_ORIGIN = UUID.randomUUID().toString();

    /**
     * Orders stamps: the later version, then the later time, then the higher origin.
     */
    private static final Comparator<Stamp> STAMP_ORDER = Comparator.comparingLong(Stamp::version)
            .thenComparingLong(Stamp::time)
            .thenComparing(Stamp::origin);

    /**
     * A map containing the custom attributes associated with the entity.
     */
    private Map<String, String> attributes = new ConcurrentHashMap<>();

    /**
     * The stamp of the last change of each attribute, set or removed, encoded by {@link Stamp#encode()}.
     *
     * <p>A key stamped here but absent from {@link #attributes} was removed. An attribute without a stamp, such as
     * one stored before stamps existed, is older than any stamped change.
     */
    private Map<String, String> attributeStamps = new ConcurrentHashMap<>();

    /**
     * A map of relationships this entity has with other entities.
     *
     * <p>Key: relationship type (e.g., "member", "owner")
     *
     * <p>Value: Set of entity UUIDs this entity has that relationship with
     */
    private Map<String, Set<UUID>> relationships = new ConcurrentHashMap<>();

    /**
     * The stamp of the last change of each relationship, added or removed, keyed by {@link #relationshipKey}, and of
     * the last {@link #clearRelationships clearing} of each type, keyed by {@link #relationshipClearKey}.
     */
    private Map<String, String> relationshipStamps = new ConcurrentHashMap<>();

    /**
     * The version up to which this instance's changes were published; the next publication carries what is newer.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private transient long lastPublishedVersion = 0;

    /**
     * Keeps each change and its stamp together, and a merge from seeing half of one.
     */
    @Transient
    @Getter(AccessLevel.NONE)
    private transient final ReentrantLock stateLock = new ReentrantLock();

    /**
     * The type of this entity, used for classification and querying.
     */
    @Indexed
    private String entityType;

    /**
     * Version number for optimistic locking and concurrency control.
     *
     * <p>This field is incremented each time the entity is modified, allowing
     * detection of concurrent modifications across different servers. It is a logical (Lamport) clock, not a time:
     * a merge keeps the higher of two versions, so a change made after seeing another is always stamped past it.
     */
    @Setter
    private long version = 0;

    /**
     * Last modification timestamp in milliseconds since epoch.
     *
     * <p>Used to resolve conflicts when merging changes from different servers.
     */
    @Setter
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
     * The stamp of a change: the entity version it made, its time in milliseconds, and the server it was made on.
     *
     * @param version the entity version the change made
     * @param time    when the change was made, in milliseconds since epoch
     * @param origin  the {@link #LOCAL_ORIGIN} of the server it was made on
     */
    public record Stamp(long version, long time, String origin) {

        /**
         * The stamp of anything stored before stamps existed: older than every stamped change.
         */
        public static final Stamp NONE = new Stamp(0, 0, "");

        /**
         * Decodes a stamp written by {@link #encode()}.
         *
         * @param encoded the encoded stamp, or {@code null}
         * @return the stamp, or {@link #NONE} for {@code null} or anything unreadable
         */
        public static Stamp decode(String encoded) {
            if (encoded == null) {
                return NONE;
            }

            String[] parts = encoded.split(":", 3);
            if (parts.length != 3) {
                return NONE;
            }

            try {
                return new Stamp(Long.parseLong(parts[0]), Long.parseLong(parts[1]), parts[2]);
            } catch (NumberFormatException exception) {
                return NONE;
            }
        }

        /**
         * Encodes this stamp as {@code version:time:origin}.
         *
         * @return the encoded stamp
         */
        public String encode() {
            return version + ":" + time + ":" + origin;
        }

    }

    /**
     * Gets the key a relationship's stamp is kept under.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity
     * @return the stamp key
     */
    public static String relationshipKey(String relationshipType, UUID targetEntityUuid) {
        return relationshipType + "|" + targetEntityUuid;
    }

    /**
     * Gets the key the stamp of a relationship type's last clearing is kept under.
     *
     * @param relationshipType the type of relationship
     * @return the stamp key
     */
    public static String relationshipClearKey(String relationshipType) {
        return relationshipType + "|*";
    }

    /**
     * The changes of an entity not yet published: the attributes and relationships stamped past a version, their
     * stamps, removals and clearings included, and the entity's version and modification time.
     *
     * @param attributes         the current values of the changed attributes; a removed one is absent
     * @param attributeStamps    the stamps of the changed attributes
     * @param relationships      the {@link #relationshipKey keys} of the changed relationships that are present
     * @param relationshipStamps the stamps of the changed relationships and clearings
     * @param version            the entity's version
     * @param lastModifiedTime   the entity's modification time
     * @param since              the version past which the changes were taken; 0 for changes received
     */
    public record Changes(Map<String, String> attributes, Map<String, String> attributeStamps, Set<String> relationships,
                          Map<String, String> relationshipStamps, long version, long lastModifiedTime, long since) {
    }

    /**
     * Decides between two stamped values of the same key: the later stamp wins, and of two equal stamps, which only
     * arise from data stored before stamps, the greater value, a removal counting as the least.
     */
    private static boolean wins(Stamp candidate, String candidateValue, Stamp current, String currentValue) {
        int order = STAMP_ORDER.compare(candidate, current);
        if (order != 0) {
            return order > 0;
        }

        return Comparator.nullsFirst(Comparator.<String>naturalOrder()).compare(candidateValue, currentValue) > 0;
    }

    /**
     * Records a change made here: the version moves on and the change is stamped with it.
     */
    private Stamp stampLocalChange() {
        updateVersionAndTimestamp();
        return new Stamp(version, lastModifiedTime, LOCAL_ORIGIN);
    }

    /**
     * Restores the thread-safe collections after this instance was read back from L2 or the database.
     *
     * <p>Gson and Morphia fill the maps with types of their own, which are not safe for concurrent use; an entity read
     * back that way and then changed while another thread reads it, such as the stream accepter and a plugin thread,
     * would fail or lose changes. Morphia calls this after loading; {@code LegacyEntitySerializable} after Gson.
     * Idempotent.
     */
    @PostLoad
    public void restoreConcurrentCollections() {
        stateLock.lock();
        try {
            if (!(attributes instanceof ConcurrentHashMap)) {
                attributes = new ConcurrentHashMap<>(attributes == null ? Map.of() : attributes);
            }
            if (!(attributeStamps instanceof ConcurrentHashMap)) {
                attributeStamps = new ConcurrentHashMap<>(attributeStamps == null ? Map.of() : attributeStamps);
            }
            if (!(relationshipStamps instanceof ConcurrentHashMap)) {
                relationshipStamps = new ConcurrentHashMap<>(relationshipStamps == null ? Map.of() : relationshipStamps);
            }

            Map<String, Set<UUID>> restoredRelationships = new ConcurrentHashMap<>();
            if (relationships != null) {
                relationships.forEach((type, targets) -> {
                    Set<UUID> restoredTargets = ConcurrentHashMap.newKeySet();
                    restoredTargets.addAll(targets);
                    restoredRelationships.put(type, restoredTargets);
                });
            }
            relationships = restoredRelationships;
        } finally {
            stateLock.unlock();
        }
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
        stateLock.lock();
        try {
            attributes.put(key, value);
            attributeStamps.put(key, stampLocalChange().encode());
        } finally {
            stateLock.unlock();
        }
        return this;
    }

    /**
     * Adds multiple attributes to the entity.
     *
     * @param attributes the map containing the attributes to add
     * @return the current instance for method chaining
     */
    public LegacyEntityData addAttributes(Map<String, String> attributes) {
        stateLock.lock();
        try {
            this.attributes.putAll(attributes);
            String stamp = stampLocalChange().encode();
            attributes.keySet().forEach(key -> attributeStamps.put(key, stamp));
        } finally {
            stateLock.unlock();
        }
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
        stateLock.lock();
        try {
            attributes.remove(key);
            attributeStamps.put(key, stampLocalChange().encode());
        } finally {
            stateLock.unlock();
        }
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
        stateLock.lock();
        try {
            relationships.computeIfAbsent(relationshipType, k -> ConcurrentHashMap.newKeySet())
                    .add(targetEntityUuid);
            relationshipStamps.put(relationshipKey(relationshipType, targetEntityUuid), stampLocalChange().encode());
        } finally {
            stateLock.unlock();
        }
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
        stateLock.lock();
        try {
            Set<UUID> relatedEntities = relationships.get(relationshipType);
            if (relatedEntities != null) {
                relatedEntities.remove(targetEntityUuid);
            }

            // Stamped even when not known here, so the removal also beats an addition this server has not seen yet
            relationshipStamps.put(relationshipKey(relationshipType, targetEntityUuid), stampLocalChange().encode());
        } finally {
            stateLock.unlock();
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
        stateLock.lock();
        try {
            relationships.remove(relationshipType);

            // One stamp for the whole type: it also removes additions made before it that this server has not seen
            relationshipStamps.put(relationshipClearKey(relationshipType), stampLocalChange().encode());
        } finally {
            stateLock.unlock();
        }
        return this;
    }

    /**
     * Takes a consistent copy of the attributes and their stamps, for publishing or merging elsewhere.
     *
     * @return the attributes on the left, their stamps on the right, both copies
     */
    public Pair<Map<String, String>, Map<String, String>> snapshotAttributes() {
        stateLock.lock();
        try {
            return Pair.of(new HashMap<>(attributes), new HashMap<>(attributeStamps));
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Merges a stamped state of this entity received from elsewhere, one attribute at a time: each attribute takes
     * the value, or the removal, with the later stamp.
     *
     * <p>Attributes the state does not stamp, as in an update from a version without stamps, are taken as stamped
     * with {@code defaultStamp}. Attributes the state neither holds nor stamps are left alone, so a state that does
     * not know of an attribute never removes it. The version becomes the higher of the two, and the modification
     * time the later; the change is not one made here, so nothing is stamped anew.
     *
     * @param attributes   the attributes of the received state
     * @param stamps       their stamps, removals included; may be empty
     * @param version      the version of the received state
     * @param modifiedTime the modification time of the received state, 0 if unknown
     * @param defaultStamp the stamp of an attribute the state holds without stamping it
     * @return {@code true} if any attribute changed
     */
    public boolean mergeAttributes(Map<String, String> attributes, Map<String, String> stamps,
                                   long version, long modifiedTime, Stamp defaultStamp) {
        return mergeAttributes(attributes, stamps, version, modifiedTime, defaultStamp, Long.MIN_VALUE);
    }

    /**
     * Merges a stamped state of this entity received from elsewhere, one attribute at a time, as
     * {@link #mergeAttributes(Map, Map, long, long, Stamp)} does, forgetting removal stamps older than a cutoff.
     *
     * <p>A removal stamped before the cutoff still removes an attribute stamped before it, but its stamp is not kept:
     * a copy that has not yet forgotten a removal, such as one in L2, must not bring back a stamp this side already
     * forgot (see {@link #pruneTombstones(long)}).
     *
     * @param attributes      the attributes of the received state
     * @param stamps          their stamps, removals included; may be empty
     * @param version         the version of the received state
     * @param modifiedTime    the modification time of the received state, 0 if unknown
     * @param defaultStamp    the stamp of an attribute the state holds without stamping it
     * @param tombstoneCutoff the time in milliseconds since epoch before which removal stamps are forgotten
     * @return {@code true} if any attribute changed
     */
    public boolean mergeAttributes(Map<String, String> attributes, Map<String, String> stamps,
                                   long version, long modifiedTime, Stamp defaultStamp, long tombstoneCutoff) {
        stateLock.lock();
        try {
            Set<String> keys = new HashSet<>(attributes.keySet());
            keys.addAll(stamps.keySet());

            boolean changed = false;
            for (String key : keys) {
                String incomingValue = attributes.get(key);
                Stamp incomingStamp = stamps.containsKey(key) ? Stamp.decode(stamps.get(key)) : defaultStamp;
                String currentValue = this.attributes.get(key);
                Stamp currentStamp = Stamp.decode(attributeStamps.get(key));

                if (!wins(incomingStamp, incomingValue, currentStamp, currentValue)) {
                    continue;
                }

                if (incomingValue == null) {
                    this.attributes.remove(key);
                } else {
                    this.attributes.put(key, incomingValue);
                }

                // A removal past the retention removes, but leaves no stamp behind to be forgotten again
                if (incomingValue == null && isExpiredTombstone(incomingStamp, tombstoneCutoff)) {
                    attributeStamps.remove(key);
                } else {
                    attributeStamps.put(key, incomingStamp.encode());
                }
                changed |= !Objects.equals(incomingValue, currentValue);
            }

            this.version = Math.max(this.version, version);
            this.lastModifiedTime = Math.max(this.lastModifiedTime, modifiedTime);
            return changed;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Merges changes from another entity of the same UUID.
     *
     * <p>This method is used for conflict resolution when updates from different servers, or different copies on
     * one server, need to be merged. Attributes and relationships are merged one at a time by their stamps (see the
     * class description), so neither side's changes are lost and a stale copy cannot undo newer ones. The version
     * becomes the higher of the two, so the next change made here is stamped past everything merged in, and two
     * servers that merged the same changes reach the same version.
     *
     * @param other the other entity to merge changes from
     * @return true if any changes were merged, false otherwise
     */
    public boolean mergeChangesFrom(LegacyEntityData other) {
        return mergeChangesFrom(other, Long.MIN_VALUE);
    }

    /**
     * Merges changes from another entity of the same UUID, as {@link #mergeChangesFrom(LegacyEntityData)} does,
     * forgetting removal stamps older than a cutoff instead of taking them in again.
     *
     * @param other           the other entity to merge changes from
     * @param tombstoneCutoff the time in milliseconds since epoch before which removal stamps are forgotten
     * @return true if any changes were merged, false otherwise
     */
    public boolean mergeChangesFrom(LegacyEntityData other, long tombstoneCutoff) {
        if (!this.uuid.equals(other.uuid) || this == other) {
            return false;
        }

        // Copy the other side first, under its own lock, so two opposite merges never wait on each other
        Pair<Map<String, String>, Map<String, String>> otherAttributes = other.snapshotAttributes();
        Map<String, Set<UUID>> otherRelationships = new HashMap<>();
        Map<String, String> otherRelationshipStamps;
        long otherVersion;
        long otherModifiedTime;
        other.stateLock.lock();
        try {
            other.relationships.forEach((type, targets) -> otherRelationships.put(type, new HashSet<>(targets)));
            otherRelationshipStamps = new HashMap<>(other.relationshipStamps);
            otherVersion = other.version;
            otherModifiedTime = other.lastModifiedTime;
        } finally {
            other.stateLock.unlock();
        }

        stateLock.lock();
        try {
            long ownVersion = this.version;
            boolean changed = mergeAttributes(otherAttributes.getLeft(), otherAttributes.getRight(),
                    otherVersion, otherModifiedTime, Stamp.NONE, tombstoneCutoff);

            Set<String> otherPresent = new HashSet<>();
            otherRelationships.forEach((type, targets) -> targets.forEach(target -> otherPresent.add(relationshipKey(type, target))));
            Set<String> keys = new HashSet<>(otherRelationshipStamps.keySet());
            keys.addAll(otherPresent);
            relationships.forEach((type, targets) -> targets.forEach(target -> keys.add(relationshipKey(type, target))));
            changed |= mergeRelationships(keys, otherPresent, otherRelationshipStamps, true, tombstoneCutoff);

            // The stamps decide every merge, so the version only has to stay past every stamp merged in
            this.version = Math.max(ownVersion, otherVersion);
            return changed;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Merges relationships one at a time, the later stamp winning, a type's clearing included: a relationship stamped
     * before the latest clearing of its type, on either side, is absent.
     *
     * @param keys          the relationship keys and clear keys to consider
     * @param otherPresent  the relationship keys present on the other side
     * @param otherStamps   the other side's stamps, clearings included
     * @param unstampedKept   whether a relationship unstamped on both sides is kept if either side has it, as the
     *                        union merge did before stamps
     * @param tombstoneCutoff the time in milliseconds since epoch before which removal and clearing stamps are
     *                        forgotten rather than kept
     * @return {@code true} if any relationship changed
     */
    private boolean mergeRelationships(Set<String> keys, Set<String> otherPresent, Map<String, String> otherStamps,
                                       boolean unstampedKept, long tombstoneCutoff) {
        boolean changed = false;
        Set<String> considered = new HashSet<>(keys);

        // Clearings first: the later one of each type is kept, and removes what was stamped before it
        for (String key : keys) {
            if (!key.endsWith("|*")) {
                continue;
            }
            Stamp otherClear = Stamp.decode(otherStamps.get(key));
            if (!isExpiredTombstone(otherClear, tombstoneCutoff)
                    && STAMP_ORDER.compare(otherClear, Stamp.decode(relationshipStamps.get(key))) > 0) {
                relationshipStamps.put(key, otherClear.encode());
            }

            // Every relationship of the type held here is weighed against the clearing
            String type = key.substring(0, key.length() - 2);
            relationships.getOrDefault(type, Set.of()).forEach(target -> considered.add(relationshipKey(type, target)));
        }

        for (String key : considered) {
            if (key.endsWith("|*")) {
                continue;
            }
            int separator = key.lastIndexOf('|');
            String type = key.substring(0, separator);
            UUID target = UUID.fromString(key.substring(separator + 1));
            Stamp clear = Stamp.decode(relationshipStamps.get(relationshipClearKey(type)));
            boolean otherHas = otherPresent.contains(key);
            boolean thisHas = hasRelationship(type, target);
            Stamp otherStamp = Stamp.decode(otherStamps.get(key));
            Stamp thisStamp = Stamp.decode(relationshipStamps.get(key));

            boolean result;
            Stamp resultStamp;
            if (otherStamp.equals(Stamp.NONE) && thisStamp.equals(Stamp.NONE)) {
                result = unstampedKept ? otherHas || thisHas : thisHas;
                resultStamp = Stamp.NONE;
            } else if (wins(otherStamp, otherHas ? "present" : null, thisStamp, thisHas ? "present" : null)) {
                result = otherHas;
                resultStamp = otherStamp;
            } else {
                result = thisHas;
                resultStamp = thisStamp;
            }

            // Anything stamped before the latest clearing of its type is gone
            if (result && STAMP_ORDER.compare(resultStamp, clear) <= 0 && !clear.equals(Stamp.NONE)) {
                result = false;
            }

            if (result != thisHas) {
                if (result) {
                    relationships.computeIfAbsent(type, k -> ConcurrentHashMap.newKeySet()).add(target);
                } else {
                    Set<UUID> targets = relationships.get(type);
                    if (targets != null) {
                        targets.remove(target);
                    }
                }
                changed = true;
            }
            if (!result && isExpiredTombstone(resultStamp, tombstoneCutoff)) {
                relationshipStamps.remove(key);
            } else if (!resultStamp.equals(Stamp.NONE)) {
                relationshipStamps.put(key, resultStamp.encode());
            }
        }

        return changed;
    }

    /**
     * Takes the changes not yet published: everything this server stamped past the version of the last publication,
     * which then becomes the current version. A change made concurrently is stamped past that version and goes with
     * the next.
     *
     * <p>Changes merged in from other servers are left out: their own servers published them, and the version they
     * raised here would otherwise send them again with every later change, a message growing with every server that
     * writes the entity. The first publication from this instance carries everything stamped, as a copy loaded from
     * L2 may hold changes no server published.
     *
     * @return the unpublished changes; empty maps and sets if there are none
     */
    public Changes takeUnpublishedChanges() {
        stateLock.lock();
        try {
            long since = lastPublishedVersion;
            Map<String, String> changedAttributes = new HashMap<>();
            Map<String, String> changedAttributeStamps = new HashMap<>();
            attributeStamps.forEach((key, encoded) -> {
                if (isUnpublished(Stamp.decode(encoded), since)) {
                    changedAttributeStamps.put(key, encoded);
                    String value = attributes.get(key);
                    if (value != null) {
                        changedAttributes.put(key, value);
                    }
                }
            });

            Set<String> changedRelationships = new HashSet<>();
            Map<String, String> changedRelationshipStamps = new HashMap<>();
            relationshipStamps.forEach((key, encoded) -> {
                if (isUnpublished(Stamp.decode(encoded), since)) {
                    changedRelationshipStamps.put(key, encoded);
                    int separator = key.lastIndexOf('|');
                    if (!key.endsWith("|*")
                            && hasRelationship(key.substring(0, separator), UUID.fromString(key.substring(separator + 1)))) {
                        changedRelationships.add(key);
                    }
                }
            });

            lastPublishedVersion = version;
            return new Changes(changedAttributes, changedAttributeStamps, changedRelationships, changedRelationshipStamps,
                    version, lastModifiedTime, since);
        } finally {
            stateLock.unlock();
        }
    }

    private static boolean isUnpublished(Stamp stamp, long since) {
        return since == 0 ? stamp.version() > 0 : stamp.version() > since && LOCAL_ORIGIN.equals(stamp.origin());
    }

    private static boolean isExpiredTombstone(Stamp stamp, long tombstoneCutoff) {
        return !stamp.equals(Stamp.NONE) && stamp.time() < tombstoneCutoff;
    }

    /**
     * Puts back changes whose publication failed, so the next publication carries them again.
     *
     * @param changes the changes taken with {@link #takeUnpublishedChanges()}
     */
    public void restoreUnpublishedChanges(Changes changes) {
        stateLock.lock();
        try {
            lastPublishedVersion = Math.min(lastPublishedVersion, changes.since());
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Merges changes published by another server, one attribute and relationship at a time by their stamps.
     *
     * <p>Only what the changes carry is merged; everything else is left as it is. Nothing is stamped anew.
     *
     * @param changes the changes received
     * @return {@code true} if any attribute or relationship changed
     */
    public boolean mergeChanges(Changes changes) {
        return mergeChanges(changes, Long.MIN_VALUE);
    }

    /**
     * Merges changes published by another server, as {@link #mergeChanges(Changes)} does, forgetting removal stamps
     * older than a cutoff.
     *
     * @param changes         the changes received
     * @param tombstoneCutoff the time in milliseconds since epoch before which removal stamps are forgotten
     * @return {@code true} if any attribute or relationship changed
     */
    public boolean mergeChanges(Changes changes, long tombstoneCutoff) {
        stateLock.lock();
        try {
            boolean changed = mergeAttributes(changes.attributes(), changes.attributeStamps(), changes.version(),
                    changes.lastModifiedTime(), Stamp.NONE, tombstoneCutoff);
            changed |= mergeRelationships(changes.relationshipStamps().keySet(), changes.relationships(),
                    changes.relationshipStamps(), false, tombstoneCutoff);
            return changed;
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Forgets the stamps of removals and clearings older than a cutoff, which otherwise stay forever.
     *
     * <p>A removal is merged by its stamp; once forgotten, an older copy still holding the removed attribute or
     * relationship would bring it back. The cutoff must therefore lie further back than any server or copy can be
     * out of date: longer than any server may be offline.
     *
     * @param cutoff the time in milliseconds since epoch before which removal stamps are forgotten
     * @return how many stamps were forgotten
     */
    public int pruneTombstones(long cutoff) {
        stateLock.lock();
        try {
            int before = attributeStamps.size() + relationshipStamps.size();
            attributeStamps.entrySet().removeIf(entry -> !attributes.containsKey(entry.getKey())
                    && Stamp.decode(entry.getValue()).time() < cutoff);
            relationshipStamps.entrySet().removeIf(entry -> {
                String key = entry.getKey();
                if (Stamp.decode(entry.getValue()).time() >= cutoff) {
                    return false;
                }
                if (key.endsWith("|*")) {
                    return true;
                }
                int separator = key.lastIndexOf('|');
                return !hasRelationship(key.substring(0, separator), UUID.fromString(key.substring(separator + 1)));
            });
            return before - attributeStamps.size() - relationshipStamps.size();
        } finally {
            stateLock.unlock();
        }
    }

    /**
     * Applies a relationship change received from another server as a change made here, unless it is already in
     * place: a change read twice is not stamped twice.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity
     * @param remove           {@code true} to remove the relationship, {@code false} to add it
     * @return the current instance for method chaining
     */
    public LegacyEntityData applyRelationshipChange(String relationshipType, UUID targetEntityUuid, boolean remove) {
        // A change already in place, such as one read again, is not stamped anew
        if (hasRelationship(relationshipType, targetEntityUuid) != remove) {
            return this;
        }
        return remove ? removeRelationship(relationshipType, targetEntityUuid) : addRelationship(relationshipType, targetEntityUuid);
    }

}