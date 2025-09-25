package net.legacy.library.aop.transaction;

import lombok.Getter;
import net.legacy.library.aop.annotation.DistributedTransaction;

import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Context object representing a distributed transaction.
 *
 * <p>This class maintains the state and context information for a distributed
 * transaction across multiple service boundaries. It includes transaction metadata,
 * participant information, and execution status tracking.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
public class TransactionContext {

    private final String transactionId;
    private final String parentTransactionId;
    private final long startTime;
    private final ConcurrentMap<String, TransactionParticipant> participants;
    private final ConcurrentMap<String, Object> attributes;
    private final int timeout;
    private final boolean readOnly;
    private final DistributedTransaction.Isolation isolation;
    private TransactionStatus status;
    private String name;

    /**
     * Creates a new transaction context.
     *
     * @param transactionId the unique transaction identifier
     * @param name the transaction name
     * @param timeout the transaction timeout in seconds
     * @param readOnly whether the transaction is read-only
     */
    public TransactionContext(String transactionId,
                              String name,
                              int timeout,
                              boolean readOnly,
                              DistributedTransaction.Isolation isolation) {
        this.transactionId = transactionId;
        this.name = name;
        this.timeout = timeout;
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.parentTransactionId = null;
        this.startTime = System.currentTimeMillis();
        this.participants = new ConcurrentHashMap<>();
        this.attributes = new ConcurrentHashMap<>();
        this.status = TransactionStatus.ACTIVE;
    }

    /**
     * Creates a new transaction context with a parent transaction.
     *
     * @param transactionId the unique transaction identifier
     * @param parentTransactionId the parent transaction identifier
     * @param name the transaction name
     * @param timeout the transaction timeout in seconds
     * @param readOnly whether the transaction is read-only
     */
    public TransactionContext(String transactionId,
                              String parentTransactionId,
                              String name,
                              int timeout,
                              boolean readOnly,
                              DistributedTransaction.Isolation isolation) {
        this.transactionId = transactionId;
        this.parentTransactionId = parentTransactionId;
        this.name = name;
        this.timeout = timeout;
        this.readOnly = readOnly;
        this.isolation = isolation;
        this.startTime = System.currentTimeMillis();
        this.participants = new ConcurrentHashMap<>();
        this.attributes = new ConcurrentHashMap<>();
        this.status = TransactionStatus.ACTIVE;
    }

    /**
     * Creates a new transaction context with a generated transaction ID.
     *
     * @param name the transaction name
     * @param timeout the transaction timeout in seconds
     * @param readOnly whether the transaction is read-only
     * @return a new transaction context
     */
    public static TransactionContext create(String name,
                                            int timeout,
                                            boolean readOnly,
                                            DistributedTransaction.Isolation isolation) {
        String transactionId = generateTransactionId();
        return new TransactionContext(transactionId, name, timeout, readOnly, isolation);
    }

    /**
     * Creates a new transaction context with a generated transaction ID and parent.
     *
     * @param parentTransactionId the parent transaction ID
     * @param name the transaction name
     * @param timeout the transaction timeout in seconds
     * @param readOnly whether the transaction is read-only
     * @return a new transaction context
     */
    public static TransactionContext createChild(String parentTransactionId,
                                                 String name,
                                                 int timeout,
                                                 boolean readOnly,
                                                 DistributedTransaction.Isolation isolation) {
        String transactionId = generateTransactionId();
        return new TransactionContext(transactionId, parentTransactionId, name, timeout, readOnly, isolation);
    }

    /**
     * Generates a unique transaction ID.
     *
     * @return a unique transaction ID
     */
    private static String generateTransactionId() {
        return "tx-" + UUID.randomUUID().toString().replace("-", "");
    }

    /**
     * Sets the transaction name.
     *
     * @param name the transaction name
     */
    public void setName(String name) {
        this.name = Objects.requireNonNull(name, "Transaction name cannot be null");
    }

    /**
     * Sets the transaction status.
     *
     * @param status the new transaction status
     */
    public void setStatus(TransactionStatus status) {
        this.status = Objects.requireNonNull(status, "Transaction status cannot be null");
    }

    /**
     * Checks if the transaction is completed.
     *
     * @return true if the transaction is completed (COMMITTED, ROLLED_BACK, or FAILED)
     */
    public boolean isCompleted() {
        return status == TransactionStatus.COMMITTED ||
                status == TransactionStatus.ROLLED_BACK ||
                status == TransactionStatus.FAILED;
    }

    /**
     * Checks if the transaction is active.
     *
     * @return true if the transaction is active (not completed)
     */
    public boolean isActive() {
        return !isCompleted();
    }

    /**
     * Gets the transaction duration in milliseconds.
     *
     * @return the duration in milliseconds
     */
    public long getDuration() {
        return System.currentTimeMillis() - startTime;
    }

    /**
     * Checks if the transaction has timed out.
     *
     * @return true if the transaction has timed out
     */
    public boolean isTimedOut() {
        return getDuration() > (timeout * 1000L);
    }

    /**
     * Registers a transaction participant.
     *
     * @param participant the transaction participant to register
     */
    public void registerParticipant(TransactionParticipant participant) {
        participants.put(participant.getParticipantId(), participant);
    }

    /**
     * Gets a transaction participant by ID.
     *
     * @param participantId the participant ID
     * @return the transaction participant, or null if not found
     */
    public TransactionParticipant getParticipant(String participantId) {
        return participants.get(participantId);
    }

    /**
     * Gets the number of registered participants.
     *
     * @return the participant count
     */
    public int getParticipantCount() {
        return participants.size();
    }

    /**
     * Sets an attribute in the transaction context.
     *
     * @param key the attribute key
     * @param value the attribute value
     */
    public void setAttribute(String key, Object value) {
        attributes.put(key, value);
    }

    /**
     * Gets an attribute from the transaction context.
     *
     * @param key the attribute key
     * @return the attribute value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T getAttribute(String key) {
        return (T) attributes.get(key);
    }

    /**
     * Removes an attribute from the transaction context.
     *
     * @param key the attribute key
     * @return the removed attribute value, or null if not found
     */
    @SuppressWarnings("unchecked")
    public <T> T removeAttribute(String key) {
        return (T) attributes.remove(key);
    }

    @Override
    public String toString() {
        return String.format("TransactionContext[id=%s, name=%s, status=%s, participants=%d, duration=%dms]",
                transactionId, name, status, participants.size(), getDuration());
    }

}
