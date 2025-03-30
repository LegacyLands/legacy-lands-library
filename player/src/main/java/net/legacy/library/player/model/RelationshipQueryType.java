package net.legacy.library.player.model;

/**
 * Defines logical operation types for complex relationship queries.
 *
 * <p>This enum represents different logical operations that can be applied
 * when combining multiple relationship criteria in a query.
 *
 * @author qwq-dev
 * @since 2025-3-30 20:47
 */
public enum RelationshipQueryType {

    /**
     * Requires that an entity must satisfy ALL criteria (logical AND).
     * Entity must match every single relationship criteria to be included in results.
     */
    AND,

    /**
     * Requires that an entity must satisfy AT LEAST ONE criteria (logical OR).
     * Entity needs to match just one of the relationship criteria to be included.
     */
    OR,

    /**
     * Finds entities that match the first criteria but none of the following criteria (logical AND NOT).
     * Entity must match the first criteria but not match any other criteria to be included.
     */
    AND_NOT
} 