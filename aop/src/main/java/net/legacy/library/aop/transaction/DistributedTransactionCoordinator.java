package net.legacy.library.aop.transaction;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.Getter;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.annotation.DistributedTransaction;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Central coordinator for managing distributed transactions across multiple participants.
 *
 * <p>This coordinator implements a two-phase commit protocol with compensation mechanisms
 * to ensure data consistency across distributed services and data sources. It handles
 * transaction lifecycle management, participant coordination, and failure recovery.
 *
 * <p>The coordinator supports various transaction propagation behaviors and provides
 * comprehensive monitoring and logging capabilities.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
@InjectableComponent
@RequiredArgsConstructor
public class DistributedTransactionCoordinator {

    private final TransactionLogStore transactionLogStore;
    private final CompensationRegistry compensationRegistry;
    private final ParticipantRegistry participantRegistry;

    private final ConcurrentMap<String, TransactionContext> activeTransactions = new ConcurrentHashMap<>();
    private final ScheduledExecutorService timeoutExecutor = Executors.newScheduledThreadPool(4);
    private final ExecutorService coordinatorExecutor = Executors.newFixedThreadPool(16);

    /**
     * Begins a new distributed transaction.
     *
     * @param annotation the distributed transaction annotation
     * @param methodName the method name for transaction identification
     * @return the transaction context
     */
    public TransactionContext beginTransaction(DistributedTransaction annotation, String methodName) {
        String transactionName = annotation.name().isEmpty() ? methodName : annotation.name();
        TransactionContext context = TransactionContext.create(
                transactionName,
                annotation.timeout(),
                annotation.readOnly(),
                annotation.isolation()
        );

        // Store in active transactions
        activeTransactions.put(context.getTransactionId(), context);

        // Log transaction start
        transactionLogStore.logTransactionStart(context);

        // Schedule timeout check
        scheduleTimeoutCheck(context);

        Log.info("Began distributed transaction: %s", context);

        return context;
    }

    /**
     * Begins a nested distributed transaction.
     *
     * @param parentContext the parent transaction context
     * @param annotation    the distributed transaction annotation
     * @param methodName    the method name for transaction identification
     * @return the nested transaction context
     */
    public TransactionContext beginNestedTransaction(TransactionContext parentContext,
                                                     DistributedTransaction annotation, String methodName) {
        String transactionName = annotation.name().isEmpty() ? methodName : annotation.name();
        TransactionContext context = TransactionContext.createChild(
                parentContext.getTransactionId(),
                transactionName,
                annotation.timeout(),
                annotation.readOnly(),
                annotation.isolation()
        );

        // Store in active transactions
        activeTransactions.put(context.getTransactionId(), context);

        // Log nested transaction start
        transactionLogStore.logNestedTransactionStart(parentContext, context);

        // Schedule timeout check
        scheduleTimeoutCheck(context);

        Log.info("Began nested distributed transaction: %s (parent: %s)",
                context, parentContext.getTransactionId());

        return context;
    }

    /**
     * Commits a distributed transaction using two-phase commit protocol.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when the transaction is committed
     */
    public CompletableFuture<Void> commit(TransactionContext context) {
        if (context.isCompleted()) {
            Log.warn("Transaction %s is already completed with status: %s",
                    context.getTransactionId(), context.getStatus());
            return CompletableFuture.completedFuture(null);
        }

        Log.info("Committing distributed transaction: %s", context);

        return CompletableFuture.supplyAsync(() -> {
            try {
                // Phase 1: Prepare
                if (!prepareParticipants(context)) {
                    // Prepare failed, rollback all participants
                    rollbackParticipants(context).thenAccept(v -> {
                        throw new TransactionException("Prepare phase failed, transaction rolled back");
                    }).join();
                    throw new TransactionException("Prepare phase failed, transaction rolled back");
                }

                // Phase 2: Commit
                commitParticipants(context).thenAccept(v -> {
                    context.setStatus(TransactionStatus.COMMITTED);
                    transactionLogStore.logTransactionCompletion(context);
                    Log.info("Successfully committed distributed transaction: %s", context);
                }).join();

            } catch (Exception exception) {
                Log.error("Failed to commit transaction %s: %s", context.getTransactionId(), exception.getMessage(), exception);
                try {
                    rollbackParticipants(context).thenAccept(v -> {
                        throw new TransactionException("Transaction commit failed", exception);
                    }).join();
                } catch (Exception rollbackEx) {
                    throw new TransactionException("Transaction commit failed", exception);
                }
                throw new TransactionException("Transaction commit failed", exception);
            } finally {
                // Cleanup
                activeTransactions.remove(context.getTransactionId());
                cleanupParticipants(context);
            }
            return null;
        }, coordinatorExecutor);
    }

    /**
     * Rolls back a distributed transaction.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when the transaction is rolled back
     */
    public CompletableFuture<Void> rollback(TransactionContext context) {
        if (context.isCompleted()) {
            Log.warn("Transaction %s is already completed with status: %s",
                    context.getTransactionId(), context.getStatus());
            return CompletableFuture.completedFuture(null);
        }

        Log.info("Rolling back distributed transaction: %s", context);

        return CompletableFuture.supplyAsync(() -> {
            try {
                context.setStatus(TransactionStatus.ROLLING_BACK);

                // Rollback all participants
                rollbackParticipants(context).thenAccept(v -> {
                    context.setStatus(TransactionStatus.ROLLED_BACK);
                    transactionLogStore.logTransactionRollback(context);
                    Log.info("Successfully rolled back distributed transaction: %s", context);
                }).join();

            } catch (Exception exception) {
                Log.error("Failed to rollback transaction %s: %s", context.getTransactionId(), exception.getMessage(), exception);
                context.setStatus(TransactionStatus.FAILED);
                throw new TransactionException("Transaction rollback failed", exception);
            } finally {
                // Cleanup
                activeTransactions.remove(context.getTransactionId());
                cleanupParticipants(context);
            }
            return null;
        }, coordinatorExecutor);
    }

    /**
     * Phase 1: Prepare all participants.
     *
     * @param context the transaction context
     * @return true if all participants voted to commit
     */
    private boolean prepareParticipants(TransactionContext context) {
        context.setStatus(TransactionStatus.PREPARING);

        Map<String, TransactionParticipant> participants = context.getParticipants();
        if (participants.isEmpty()) {
            // No participants, transaction is effectively read-only
            context.setStatus(TransactionStatus.COMMITTED);
            return true;
        }

        AtomicInteger commitVotes = new AtomicInteger(0);
        AtomicInteger abortVotes = new AtomicInteger(0);
        AtomicInteger readOnlyVotes = new AtomicInteger(0);

        // Create futures for all prepare operations
        // noinspection unchecked
        CompletableFuture<Void>[] prepareFutures = participants.values().stream()
                .map(participant -> participant.prepare(context)
                        .thenAccept(vote -> {
                            switch (vote) {
                                case COMMIT -> commitVotes.incrementAndGet();
                                case ABORT -> abortVotes.incrementAndGet();
                                case READ_ONLY -> readOnlyVotes.incrementAndGet();
                            }
                        })
                        .exceptionally(throwable -> {
                            Log.error("Participant %s failed to prepare: %s",
                                    participant.getParticipantId(), throwable.getMessage());
                            abortVotes.incrementAndGet();
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        // Wait for all prepare operations to complete
        try {
            CompletableFuture.allOf(prepareFutures).get(30, TimeUnit.SECONDS);
        } catch (Exception exception) {
            Log.error("Timeout or error during prepare phase: %s", exception.getMessage());
            return false;
        }

        // Check vote results
        if (abortVotes.get() > 0) {
            Log.warn("Transaction %s prepare phase failed: %d abort votes",
                    context.getTransactionId(), abortVotes.get());
            return false;
        }

        if (commitVotes.get() == 0 && readOnlyVotes.get() > 0) {
            // All participants are read-only
            context.setStatus(TransactionStatus.COMMITTED);
            return true;
        }

        context.setStatus(TransactionStatus.PREPARED);
        Log.info("Transaction %s prepare phase completed: %d commit votes, %d read-only votes",
                context.getTransactionId(), commitVotes.get(), readOnlyVotes.get());

        return true;
    }

    /**
     * Phase 2: Commit all participants.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when all participants are committed
     */
    private CompletableFuture<Void> commitParticipants(TransactionContext context) {
        context.setStatus(TransactionStatus.COMMITTING);

        Map<String, TransactionParticipant> participants = context.getParticipants();

        // Create futures for all commit operations
        // noinspection unchecked
        CompletableFuture<Void>[] commitFutures = participants.values().stream()
                .filter(participant -> !isReadOnlyParticipant(participant, context))
                .map(participant -> participant.commit(context)
                        .exceptionally(throwable -> {
                            Log.error("Participant %s failed to commit: %s",
                                    participant.getParticipantId(), throwable.getMessage());
                            // Try compensation for failed commit
                            executeCompensation(participant, context);
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        if (commitFutures.length == 0) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(commitFutures);
    }

    /**
     * Rollback all participants.
     *
     * @param context the transaction context
     * @return a CompletableFuture that completes when all participants are rolled back
     */
    private CompletableFuture<Void> rollbackParticipants(TransactionContext context) {
        Map<String, TransactionParticipant> participants = context.getParticipants();

        // Create futures for all rollback operations
        // noinspection unchecked
        CompletableFuture<Void>[] rollbackFutures = participants.values().stream()
                .map(participant -> participant.rollback(context)
                        .exceptionally(throwable -> {
                            Log.error("Participant %s failed to rollback: %s",
                                    participant.getParticipantId(), throwable.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        if (rollbackFutures.length == 0) {
            return CompletableFuture.completedFuture(null);
        }

        return CompletableFuture.allOf(rollbackFutures);
    }

    /**
     * Cleanup all participants.
     *
     * @param context the transaction context
     */
    private void cleanupParticipants(TransactionContext context) {
        Map<String, TransactionParticipant> participants = context.getParticipants();

        // noinspection unchecked
        CompletableFuture<Void>[] cleanupFutures = participants.values().stream()
                .map(participant -> participant.cleanup(context)
                        .exceptionally(throwable -> {
                            Log.error("Participant %s failed to cleanup: %s",
                                    participant.getParticipantId(), throwable.getMessage());
                            return null;
                        }))
                .toArray(CompletableFuture[]::new);

        if (cleanupFutures.length > 0) {
            try {
                CompletableFuture.allOf(cleanupFutures).get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                Log.warn("Cleanup operations timed out for transaction %s",
                        context.getTransactionId());
            }
        }
    }

    /**
     * Checks if a participant is read-only.
     *
     * @param participant the participant to check
     * @param context     the transaction context
     * @return true if the participant is read-only
     */
    private boolean isReadOnlyParticipant(TransactionParticipant participant, TransactionContext context) {
        try {
            return participant.getStatus(context)
                    .thenApply(status -> status == TransactionParticipant.ParticipantStatus.READ_ONLY)
                    .get(5, TimeUnit.SECONDS);
        } catch (Exception exception) {
            Log.warn("Failed to check participant %s status: %s",
                    participant.getParticipantId(), exception.getMessage());
            return false;
        }
    }

    /**
     * Executes compensation for a failed participant.
     *
     * @param participant the failed participant
     * @param context     the transaction context
     */
    private void executeCompensation(TransactionParticipant participant, TransactionContext context) {
        try {
            compensationRegistry.executeCompensation(participant, context);
        } catch (Exception exception) {
            Log.error("Compensation failed for participant %s: %s",
                    participant.getParticipantId(), exception.getMessage());
        }
    }

    /**
     * Schedules a timeout check for the transaction.
     *
     * @param context the transaction context
     */
    private void scheduleTimeoutCheck(TransactionContext context) {
        timeoutExecutor.schedule(() -> {
            if (context.isActive() && context.isTimedOut()) {
                Log.warn("Transaction %s timed out after %d seconds, initiating rollback",
                        context.getTransactionId(), context.getTimeout());
                context.setStatus(TransactionStatus.TIMEOUT);
                rollback(context).exceptionally(throwable -> {
                    Log.error("Failed to rollback timed out transaction %s: %s",
                            context.getTransactionId(), throwable.getMessage());
                    return null;
                });
            }
        }, context.getTimeout(), TimeUnit.SECONDS);
    }

    /**
     * Gets an active transaction by ID.
     *
     * @param transactionId the transaction ID
     * @return the transaction context, or null if not found
     */
    public TransactionContext getActiveTransaction(String transactionId) {
        return activeTransactions.get(transactionId);
    }

    /**
     * Gets all active transactions.
     *
     * @return unmodifiable map of active transactions
     */
    public ConcurrentMap<String, TransactionContext> getActiveTransactions() {
        return activeTransactions;
    }

    /**
     * Shuts down the transaction coordinator.
     */
    public void shutdown() {
        Log.info("Shutting down distributed transaction coordinator");

        // Shutdown timeout executor
        timeoutExecutor.shutdown();
        try {
            if (!timeoutExecutor.awaitTermination(10, TimeUnit.SECONDS)) {
                timeoutExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            timeoutExecutor.shutdownNow();
        }

        // Shutdown coordinator executor
        coordinatorExecutor.shutdown();
        try {
            if (!coordinatorExecutor.awaitTermination(30, TimeUnit.SECONDS)) {
                coordinatorExecutor.shutdownNow();
            }
        } catch (InterruptedException exception) {
            coordinatorExecutor.shutdownNow();
        }

        // Rollback all active transactions
        activeTransactions.values().forEach(context -> {
            Log.warn("Rolling back active transaction during shutdown: %s", context);
            try {
                rollback(context).get(10, TimeUnit.SECONDS);
            } catch (Exception exception) {
                Log.error("Failed to rollback transaction %s during shutdown: %s",
                        context.getTransactionId(), exception.getMessage());
            }
        });

        activeTransactions.clear();
    }

}
