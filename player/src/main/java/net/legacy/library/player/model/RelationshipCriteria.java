package net.legacy.library.player.model;

import lombok.Getter;
import lombok.NonNull;

import java.util.UUID;

/**
 * Criteria for querying entity relationships.
 *
 * <p>This class defines criteria for filtering entities based on their relationships.
 * It can be used to create complex queries involving multiple relationship conditions.
 *
 * @author qwq-dev
 * @since 2025-3-30 23:40
 */
@Getter
public class RelationshipCriteria {
    /**
     * The type of relationship to query (e.g., "parent", "child", "member", etc.)
     */
    @NonNull
    private final String relationshipType;

    /**
     * The UUID of the target entity in the relationship.
     */
    @NonNull
    private final UUID targetEntityUuid;

    /**
     * Whether to negate these criteria (i.e., find entities that do NOT have this relationship).
     */
    private final boolean negated;

    /**
     * Constructor for creating RelationshipCriteria instances.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @param negated          whether to negate the criteria
     */
    public RelationshipCriteria(String relationshipType, UUID targetEntityUuid, boolean negated) {
        this.relationshipType = relationshipType;
        this.targetEntityUuid = targetEntityUuid;
        this.negated = negated;
    }

    /**
     * Creates a new RelationshipCriteria instance.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @param negated          whether to negate the criteria
     * @return a new RelationshipCriteria instance
     */
    public static RelationshipCriteria of(String relationshipType, UUID targetEntityUuid, boolean negated) {
        return new RelationshipCriteria(relationshipType, targetEntityUuid, negated);
    }

    /**
     * Static factory method to create criteria for entities that have a specific relationship.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @return a new RelationshipCriteria instance
     */
    public static RelationshipCriteria has(String relationshipType, UUID targetEntityUuid) {
        return of(relationshipType, targetEntityUuid, false);
    }

    /**
     * Static factory method to create criteria for entities that do NOT have a specific relationship.
     *
     * @param relationshipType the type of relationship
     * @param targetEntityUuid the UUID of the target entity in the relationship
     * @return a new RelationshipCriteria instance
     */
    public static RelationshipCriteria hasNot(String relationshipType, UUID targetEntityUuid) {
        return of(relationshipType, targetEntityUuid, true);
    }
} 