package net.legacy.library.aop.transaction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Interface for storing and retrieving transaction logs.
 *
 * <p>This interface provides methods for persisting transaction state,
 * which is crucial for recovery and audit purposes in distributed transactions.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface TransactionLogStore {

    /**
     * Logs the start of a transaction.
     *
     * @param context the transaction context
     */
    void logTransactionStart(TransactionContext context);

    /**
     * Logs the start of a nested transaction.
     *
     * @param parentContext the parent transaction context
     * @param nestedContext the nested transaction context
     */
    void logNestedTransactionStart(TransactionContext parentContext, TransactionContext nestedContext);

    /**
     * Logs the completion of a transaction.
     *
     * @param context the transaction context
     */
    void logTransactionCompletion(TransactionContext context);

    /**
     * Logs the rollback of a transaction.
     *
     * @param context the transaction context
     */
    void logTransactionRollback(TransactionContext context);

    /**
     * Logs participant registration.
     *
     * @param context the transaction context
     * @param participant the registered participant
     */
    void logParticipantRegistration(TransactionContext context, TransactionParticipant participant);

    /**
     * Logs participant status changes.
     *
     * @param context the transaction context
     * @param participantId the participant ID
     * @param status the new participant status
     */
    void logParticipantStatusChange(TransactionContext context, String participantId,
                                    TransactionParticipant.ParticipantStatus status);

    /**
     * Retrieves transaction logs for recovery purposes.
     *
     * @param sinceTimestamp the timestamp to retrieve logs from
     * @return a CompletableFuture that completes with the transaction logs
     */
    CompletableFuture<Iterable<TransactionLogEntry>> getTransactionLogsSince(long sinceTimestamp);

    /**
     * Gets the transaction context for a given transaction ID.
     *
     * @param transactionId the transaction ID
     * @return a CompletableFuture that completes with the transaction context
     */
    CompletableFuture<TransactionContext> getTransactionContext(String transactionId);

    /**
     * Enumeration of log entry types.
     */
    enum LogType {
        TRANSACTION_START,
        NESTED_TRANSACTION_START,
        TRANSACTION_COMPLETION,
        TRANSACTION_ROLLBACK,
        PARTICIPANT_REGISTRATION,
        PARTICIPANT_STATUS_CHANGE,
        TIMEOUT,
        FAILURE
    }

    /**
     * Represents a single transaction log entry.
     */
    interface TransactionLogEntry {

        String getTransactionId();

        long getTimestamp();

        LogType getLogType();

        String getMessage();

        Map<String, Object> getMetadata();

    }

}