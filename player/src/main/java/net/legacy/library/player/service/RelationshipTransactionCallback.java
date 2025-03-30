package net.legacy.library.player.service;

/**
 * Callback interface for executing relationship operations within a transaction.
 *
 * <p>This interface allows for grouping multiple relationship modifications
 * into a single logical transaction with appropriate error handling.
 *
 * @author qwq-dev
 * @since 2025-3-30 20:47
 */
@FunctionalInterface
public interface RelationshipTransactionCallback {

    /**
     * Executes relationship operations using the provided transaction context.
     *
     * <p>The implementation can use the transaction context to perform multiple
     * relationship operations that should be treated as a single atomic unit.
     *
     * @param transaction the transaction context that provides methods for modifying relationships
     * @throws Exception if an error occurs during the transaction
     */
    void execute(RelationshipTransaction transaction) throws Exception;

    /**
     * Interface representing a relationship transaction context.
     * Provides methods for modifying entity relationships within a transaction.
     */
    interface RelationshipTransaction {

        /**
         * Adds a relationship between two entities.
         *
         * @param sourceEntityId   the UUID of the source entity
         * @param relationshipType the type of relationship to create
         * @param targetEntityId   the UUID of the target entity
         * @return the transaction context for method chaining
         */
        RelationshipTransaction addRelationship(java.util.UUID sourceEntityId, String relationshipType, java.util.UUID targetEntityId);

        /**
         * Removes a relationship between two entities.
         *
         * @param sourceEntityId   the UUID of the source entity
         * @param relationshipType the type of relationship to remove
         * @param targetEntityId   the UUID of the target entity
         * @return the transaction context for method chaining
         */
        RelationshipTransaction removeRelationship(java.util.UUID sourceEntityId, String relationshipType, java.util.UUID targetEntityId);

        /**
         * Creates a bidirectional relationship between two entities.
         *
         * @param entity1Id         the UUID of the first entity
         * @param relationshipType1 the relationship type from entity1 to entity2
         * @param entity2Id         the UUID of the second entity
         * @param relationshipType2 the relationship type from entity2 to entity1
         * @return the transaction context for method chaining
         */
        RelationshipTransaction createBidirectionalRelationship(
                java.util.UUID entity1Id, String relationshipType1,
                java.util.UUID entity2Id, String relationshipType2);
    }
} 