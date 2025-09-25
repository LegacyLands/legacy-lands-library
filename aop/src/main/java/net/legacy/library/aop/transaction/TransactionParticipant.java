package net.legacy.library.aop.transaction;

import java.util.concurrent.CompletableFuture;

/**
 * Interface representing a participant in a distributed transaction.
 *
 * <p>Transaction participants are services, databases, or other resources that
 * participate in a distributed transaction. Each participant must implement
 * this interface to support the two-phase commit protocol.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface TransactionParticipant {

    /**
     * Gets the unique identifier for this participant.
     *
     * @return the participant ID
     */
    String getParticipantId();

    /**
     * Gets the participant type for classification and monitoring.
     *
     * @return the participant type
     */
    ParticipantType getType();

    /**
     * Prepares the participant for commit (first phase of 2PC).
     *
     * <p>This method should perform all necessary checks to ensure that the
     * transaction can be committed successfully. It should not make any
     * permanent changes to the underlying data store.
     *
     * <p>The participant should return a Vote indicating whether it can
     * commit the transaction or not.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes with the vote
     */
    CompletableFuture<Vote> prepare(TransactionContext context);

    /**
     * Commits the transaction (second phase of 2PC).
     *
     * <p>This method should make permanent changes to the underlying data store.
     * Once this method completes successfully, the changes should be durable.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when the commit is done
     */
    CompletableFuture<Void> commit(TransactionContext context);

    /**
     * Rolls back the transaction.
     *
     * <p>This method should undo any changes made during the transaction
     * and restore the system to its original state.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when the rollback is done
     */
    CompletableFuture<Void> rollback(TransactionContext context);

    /**
     * Gets the status of this participant.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes with the participant status
     */
    CompletableFuture<ParticipantStatus> getStatus(TransactionContext context);

    /**
     * Cleans up resources after transaction completion.
     *
     * <p>This method is called after the transaction has been completed
     * (committed or rolled back) to allow the participant to clean up
     * any temporary resources or state.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when cleanup is done
     */
    CompletableFuture<Void> cleanup(TransactionContext context);

    /**
     * Enumeration of participant types.
     */
    enum ParticipantType {
        /**
         * Database participant (relational or NoSQL).
         */
        DATABASE,

        /**
         * Message queue participant.
         */
        MESSAGE_QUEUE,

        /**
         * External service participant.
         */
        EXTERNAL_SERVICE,

        /**
         * File system participant.
         */
        FILE_SYSTEM,

        /**
         * Cache participant.
         */
        CACHE,

        /**
         * Custom participant type.
         */
        CUSTOM
    }

    /**
     * Enumeration of participant status values.
     */
    enum ParticipantStatus {
        /**
         * Participant is active and ready.
         */
        ACTIVE,

        /**
         * Participant is preparing for commit.
         */
        PREPARING,

        /**
         * Participant has prepared and is ready to commit.
         */
        PREPARED,

        /**
         * Participant is read-only and doesn't need to commit.
         */
        READ_ONLY,

        /**
         * Participant is committing.
         */
        COMMITTING,

        /**
         * Participant has committed.
         */
        COMMITTED,

        /**
         * Participant is rolling back.
         */
        ROLLING_BACK,

        /**
         * Participant has rolled back.
         */
        ROLLED_BACK,

        /**
         * Participant has failed.
         */
        FAILED,

        /**
         * Participant is unknown or unreachable.
         */
        UNKNOWN
    }

    /**
     * Enumeration of vote values for the prepare phase.
     */
    enum Vote {
        /**
         * Participant can commit the transaction.
         */
        COMMIT,

        /**
         * Participant cannot commit the transaction.
         */
        ABORT,

        /**
         * Participant is read-only and doesn't need to vote.
         */
        READ_ONLY
    }

}