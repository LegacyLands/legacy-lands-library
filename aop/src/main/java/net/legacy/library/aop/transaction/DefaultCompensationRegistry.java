package net.legacy.library.aop.transaction;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Default in-memory implementation of CompensationRegistry.
 *
 * <p>This registry provides compensation strategy management for distributed transactions,
 * storing compensation strategies in memory and providing basic recovery capabilities.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
public class DefaultCompensationRegistry implements CompensationRegistry {

    private final Map<TransactionParticipant.ParticipantType, CompensationStrategy> typeStrategies = new ConcurrentHashMap<>();
    private final Map<String, CompensationStrategy> participantStrategies = new ConcurrentHashMap<>();

    @Override
    public void registerCompensationStrategy(TransactionParticipant.ParticipantType participantType,
                                             CompensationStrategy compensationStrategy) {
        if (participantType == null || compensationStrategy == null) {
            throw new IllegalArgumentException("Participant type and compensation strategy cannot be null");
        }

        typeStrategies.put(participantType, compensationStrategy);
        Log.info("Registered compensation strategy for participant type: %s", participantType);
    }

    @Override
    public void registerCompensationStrategy(String participantId,
                                             CompensationStrategy compensationStrategy) {
        if (participantId == null || compensationStrategy == null) {
            throw new IllegalArgumentException("Participant ID and compensation strategy cannot be null");
        }

        if (participantId.trim().isEmpty()) {
            throw new IllegalArgumentException("Participant ID cannot be empty");
        }

        participantStrategies.put(participantId, compensationStrategy);
        Log.info("Registered compensation strategy for participant: %s", participantId);
    }

    @Override
    public void executeCompensation(TransactionParticipant participant, TransactionContext context) {
        if (participant == null || context == null) {
            throw new IllegalArgumentException("Participant and context cannot be null");
        }

        CompensationStrategy strategy = getCompensationStrategy(participant);
        if (strategy == null) {
            Log.warn("No compensation strategy found for participant: %s", participant.getParticipantId());
            return;
        }

        try {
            Log.info("Executing compensation for participant: %s", participant.getParticipantId());
            strategy.compensate(participant, context);
            Log.info("Compensation completed successfully for participant: %s", participant.getParticipantId());
        } catch (Exception exception) {
            String errorMsg = String.format("Compensation failed for participant: %s", participant.getParticipantId());
            Log.error(errorMsg, exception);
            throw new CompensationException(errorMsg, exception);
        }
    }

    @Override
    public CompensationStrategy getCompensationStrategy(TransactionParticipant participant) {
        if (participant == null) {
            return null;
        }

        // First try participant-specific strategy
        String participantId = participant.getParticipantId();
        CompensationStrategy strategy = participantStrategies.get(participantId);
        if (strategy != null) {
            return strategy;
        }

        // Fall back to type-based strategy
        return typeStrategies.get(participant.getType());
    }

    /**
     * Gets the number of registered type-based compensation strategies.
     *
     * @return the count of type-based strategies
     */
    public int getTypeStrategyCount() {
        return typeStrategies.size();
    }

    /**
     * Gets the number of registered participant-specific compensation strategies.
     *
     * @return the count of participant-specific strategies
     */
    public int getParticipantStrategyCount() {
        return participantStrategies.size();
    }

    /**
     * Clears all registered compensation strategies.
     *
     * <p>This method is primarily used for testing and cleanup purposes.
     */
    public void clear() {
        typeStrategies.clear();
        participantStrategies.clear();
    }

    /**
     * Checks if a compensation strategy is registered for a specific participant.
     *
     * @param participantId the participant ID to check
     * @return true if a strategy is registered, false otherwise
     */
    public boolean hasStrategyForParticipant(String participantId) {
        return participantId != null && participantStrategies.containsKey(participantId);
    }

    /**
     * Checks if a compensation strategy is registered for a specific participant type.
     *
     * @param participantType the participant type to check
     * @return true if a strategy is registered, false otherwise
     */
    public boolean hasStrategyForType(TransactionParticipant.ParticipantType participantType) {
        return participantType != null && typeStrategies.containsKey(participantType);
    }

}
