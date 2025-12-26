package net.legacy.library.aop.transaction;

/**
 * Registry for managing compensation strategies for failed transaction participants.
 *
 * <p>Compensation strategies are used to undo the effects of a transaction participant
 * when the transaction cannot be completed normally. This is a key part of the
 * Saga pattern and provides an alternative to traditional two-phase commit.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
public interface CompensationRegistry {

    /**
     * Registers a compensation strategy for a participant type.
     *
     * @param participantType      the participant type
     * @param compensationStrategy the compensation strategy
     */
    void registerCompensationStrategy(TransactionParticipant.ParticipantType participantType,
                                      CompensationStrategy compensationStrategy);

    /**
     * Registers a compensation strategy for a specific participant.
     *
     * @param participantId        the participant ID
     * @param compensationStrategy the compensation strategy
     */
    void registerCompensationStrategy(String participantId,
                                      CompensationStrategy compensationStrategy);

    /**
     * Executes compensation for a failed participant.
     *
     * @param participant the failed participant
     * @param context     the transaction context
     * @throws CompensationException if compensation fails
     */
    void executeCompensation(TransactionParticipant participant, TransactionContext context);

    /**
     * Gets the compensation strategy for a participant.
     *
     * @param participant the participant
     * @return the compensation strategy, or null if not found
     */
    CompensationStrategy getCompensationStrategy(TransactionParticipant participant);

    /**
     * Interface for compensation strategies.
     */
    @FunctionalInterface
    interface CompensationStrategy {

        /**
         * Executes the compensation logic.
         *
         * @param participant the participant to compensate
         * @param context     the transaction context
         * @throws CompensationException if compensation fails
         */
        void compensate(TransactionParticipant participant, TransactionContext context);

    }

    /**
     * Exception thrown when compensation fails.
     */
    class CompensationException extends RuntimeException {

        public CompensationException(String message) {
            super(message);
        }

        public CompensationException(String message, Throwable cause) {
            super(message, cause);
        }

    }

}