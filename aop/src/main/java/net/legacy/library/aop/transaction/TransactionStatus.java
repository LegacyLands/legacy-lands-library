package net.legacy.library.aop.transaction;

import lombok.Getter;

/**
 * Enumeration representing the possible states of a distributed transaction.
 *
 * <p>This enum defines the lifecycle states that a distributed transaction can be in,
 * from initial creation through completion or failure. These states are used for
 * tracking transaction progress and making coordination decisions.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public enum TransactionStatus {

    /**
     * Transaction is currently active and can accept new participants.
     */
    ACTIVE(true),

    /**
     * Transaction is preparing for commit (first phase of 2PC).
     */
    PREPARING(true),

    /**
     * All participants have voted to commit.
     */
    PREPARED(true),

    /**
     * Transaction is committing (second phase of 2PC).
     */
    COMMITTING(false),

    /**
     * Transaction has been successfully committed.
     */
    COMMITTED(false),

    /**
     * Transaction is rolling back due to failure or timeout.
     */
    ROLLING_BACK(false),

    /**
     * Transaction has been rolled back.
     */
    ROLLED_BACK(false),

    /**
     * Transaction has failed and cannot be recovered.
     */
    FAILED(false),

    /**
     * Transaction has timed out and will be rolled back.
     */
    TIMEOUT(false),

    /**
     * Transaction is in doubt (waiting for coordinator decision).
     */
    IN_DOUBT(false);

    private final boolean active;

    TransactionStatus(boolean active) {
        this.active = active;
    }

    /**
     * Checks if the transaction is in a completion state.
     *
     * @return true if the transaction is completed (committed or rolled back)
     */
    public boolean isCompleted() {
        return this == COMMITTED || this == ROLLED_BACK || this == FAILED;
    }

    /**
     * Checks if the transaction is in a failed state.
     *
     * @return true if the transaction has failed
     */
    public boolean isFailed() {
        return this == FAILED || this == ROLLED_BACK || this == TIMEOUT;
    }

    /**
     * Checks if the transaction can accept new participants.
     *
     * @return true if new participants can be added
     */
    public boolean canAcceptParticipants() {
        return this == ACTIVE || this == PREPARING;
    }

    /**
     * Checks if the transaction can be committed.
     *
     * @return true if the transaction can be committed
     */
    public boolean canCommit() {
        return this == PREPARED;
    }

    /**
     * Checks if the transaction can be rolled back.
     *
     * @return true if the transaction can be rolled back
     */
    public boolean canRollback() {
        return this == ACTIVE || this == PREPARING || this == PREPARED || this == IN_DOUBT;
    }
}