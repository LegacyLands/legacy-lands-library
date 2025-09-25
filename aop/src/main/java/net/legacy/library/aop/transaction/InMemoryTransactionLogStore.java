package net.legacy.library.aop.transaction;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

/**
 * Default in-memory implementation of TransactionLogStore.
 *
 * <p>This implementation stores transaction logs in memory and provides basic
 * recovery capabilities. It's suitable for development and testing environments.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
public class InMemoryTransactionLogStore implements TransactionLogStore {

    private final List<TransactionLogEntry> logEntries = new CopyOnWriteArrayList<>();
    private final Map<String, TransactionContext> transactionContexts = new ConcurrentHashMap<>();

    @Override
    public void logTransactionStart(TransactionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Transaction context cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(context.getTransactionId(), LogType.TRANSACTION_START,
                "Transaction started", Map.of(
                        "participantCount", context.getParticipants().size(),
                        "timeout", context.getTimeout(),
                        "name", context.getName()
                ));

        logEntries.add(entry);
        transactionContexts.put(context.getTransactionId(), context);
        Log.info("Logged transaction start for: %s", context.getTransactionId());
    }

    @Override
    public void logNestedTransactionStart(TransactionContext parentContext, TransactionContext nestedContext) {
        if (parentContext == null || nestedContext == null) {
            throw new IllegalArgumentException("Parent and nested transaction contexts cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(nestedContext.getTransactionId(), LogType.NESTED_TRANSACTION_START,
                "Nested transaction started", Map.of(
                        "parentTransactionId", parentContext.getTransactionId(),
                        "participantCount", nestedContext.getParticipants().size(),
                        "timeout", nestedContext.getTimeout()
                ));

        logEntries.add(entry);
        transactionContexts.put(nestedContext.getTransactionId(), nestedContext);
        Log.info("Logged nested transaction start for: %s (parent: %s)",
                nestedContext.getTransactionId(), parentContext.getTransactionId());
    }

    @Override
    public void logTransactionCompletion(TransactionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Transaction context cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(context.getTransactionId(), LogType.TRANSACTION_COMPLETION,
                "Transaction completed successfully", Map.of(
                        "duration", System.currentTimeMillis() - context.getStartTime(),
                        "participantCount", context.getParticipants().size()
                ));

        logEntries.add(entry);
        Log.info("Logged transaction completion for: %s", context.getTransactionId());
    }

    @Override
    public void logTransactionRollback(TransactionContext context) {
        if (context == null) {
            throw new IllegalArgumentException("Transaction context cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(context.getTransactionId(), LogType.TRANSACTION_ROLLBACK,
                "Transaction rolled back", Map.of(
                        "duration", System.currentTimeMillis() - context.getStartTime(),
                        "participantCount", context.getParticipants().size(),
                        "status", context.getStatus().name()
                ));

        logEntries.add(entry);
        Log.warn("Logged transaction rollback for: %s", context.getTransactionId());
    }

    @Override
    public void logParticipantRegistration(TransactionContext context, TransactionParticipant participant) {
        if (context == null || participant == null) {
            throw new IllegalArgumentException("Transaction context and participant cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(context.getTransactionId(), LogType.PARTICIPANT_REGISTRATION,
                "Participant registered", Map.of(
                        "participantId", participant.getParticipantId(),
                        "participantType", participant.getType().name()
                ));

        logEntries.add(entry);
        Log.info("Logged participant registration for: %s in transaction: %s",
                participant.getParticipantId(), context.getTransactionId());
    }

    @Override
    public void logParticipantStatusChange(TransactionContext context, String participantId,
                                           TransactionParticipant.ParticipantStatus status) {
        if (context == null || participantId == null || status == null) {
            throw new IllegalArgumentException("Transaction context, participant ID, and status cannot be null");
        }

        TransactionLogEntry entry = createLogEntry(context.getTransactionId(), LogType.PARTICIPANT_STATUS_CHANGE,
                "Participant status changed", Map.of(
                        "participantId", participantId,
                        "newStatus", status.name()
                ));

        logEntries.add(entry);
        Log.info("Logged participant status change for: %s to %s in transaction: %s",
                participantId, status, context.getTransactionId());
    }

    @Override
    public CompletableFuture<Iterable<TransactionLogEntry>> getTransactionLogsSince(long sinceTimestamp) {
        return CompletableFuture.supplyAsync(() -> {
            Stream<TransactionLogEntry> filteredStream = logEntries.stream()
                    .filter(entry -> entry.getTimestamp() >= sinceTimestamp);

            List<TransactionLogEntry> result = new ArrayList<>();
            filteredStream.forEach(result::add);

            return result;
        });
    }

    @Override
    public CompletableFuture<TransactionContext> getTransactionContext(String transactionId) {
        if (transactionId == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("Transaction ID cannot be null"));
        }

        return CompletableFuture.supplyAsync(() -> {
            TransactionContext context = transactionContexts.get(transactionId);
            if (context == null) {
                Log.warn("Transaction context not found for ID: %s", transactionId);
                return null;
            }

            return context;
        });
    }

    /**
     * Creates a new transaction log entry with the given parameters.
     *
     * @param transactionId the transaction ID
     * @param logType the log type
     * @param message the log message
     * @param metadata additional metadata
     * @return the created log entry
     */
    private TransactionLogEntry createLogEntry(String transactionId, LogType logType,
                                               String message, Map<String, Object> metadata) {
        return new DefaultTransactionLogEntry(
                transactionId,
                System.currentTimeMillis(),
                logType,
                message,
                metadata
        );
    }

    /**
     * Gets the total number of log entries stored.
     *
     * @return the total log entry count
     */
    public int getTotalLogCount() {
        return logEntries.size();
    }

    /**
     * Gets the number of stored transaction contexts.
     *
     * @return the context count
     */
    public int getContextCount() {
        return transactionContexts.size();
    }

    /**
     * Clears all stored logs and contexts.
     * 
     * <p>This method is primarily used for testing and cleanup purposes.
     */
    public void clear() {
        logEntries.clear();
        transactionContexts.clear();
    }

    /**
     * Default implementation of TransactionLogEntry.
     */
    private static class DefaultTransactionLogEntry implements TransactionLogEntry {

        private final String transactionId;
        private final long timestamp;
        private final LogType logType;
        private final String message;
        private final Map<String, Object> metadata;

        public DefaultTransactionLogEntry(String transactionId, long timestamp, LogType logType,
                                          String message, Map<String, Object> metadata) {
            this.transactionId = transactionId;
            this.timestamp = timestamp;
            this.logType = logType;
            this.message = message;
            this.metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
        }

        @Override
        public String getTransactionId() {
            return transactionId;
        }

        @Override
        public long getTimestamp() {
            return timestamp;
        }

        @Override
        public LogType getLogType() {
            return logType;
        }

        @Override
        public String getMessage() {
            return message;
        }

        @Override
        public Map<String, Object> getMetadata() {
            return metadata;
        }

        @Override
        public String toString() {
            return String.format("TransactionLogEntry{transactionId='%s', timestamp=%d, type=%s, message='%s'}",
                    transactionId, timestamp, logType, message);
        }

    }

}
