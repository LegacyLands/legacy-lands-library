package net.legacy.library.aop.transaction;

import io.fairyproject.container.InjectableComponent;

import java.util.Collection;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Registry for managing transaction participants.
 *
 * <p>This registry provides centralized management of all transaction participants,
 * allowing for dynamic registration and lookup of participants during transaction
 * execution.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
public class ParticipantRegistry {

    private final ConcurrentMap<String, TransactionParticipant> participants = new ConcurrentHashMap<>();
    private final ConcurrentMap<TransactionParticipant.ParticipantType, Collection<TransactionParticipant>> participantsByType =
            new ConcurrentHashMap<>();

    /**
     * Registers a transaction participant.
     *
     * @param participant the participant to register
     * @throws IllegalArgumentException if a participant with the same ID already exists
     */
    public void registerParticipant(TransactionParticipant participant) {
        String participantId = participant.getParticipantId();
        if (participants.containsKey(participantId)) {
            throw new IllegalArgumentException("Participant already registered: " + participantId);
        }

        participants.put(participantId, participant);

        // Add to type-based index
        participantsByType.compute(participant.getType(), (type, participantCollection) -> {
            if (participantCollection == null) {
                participantCollection = ConcurrentHashMap.newKeySet();
            }
            participantCollection.add(participant);
            return participantCollection;
        });
    }

    /**
     * Unregisters a transaction participant.
     *
     * @param participantId the participant ID to unregister
     * @return the unregistered participant, or null if not found
     */
    public TransactionParticipant unregisterParticipant(String participantId) {
        TransactionParticipant participant = participants.remove(participantId);
        if (participant != null) {
            // Remove from type-based index
            participantsByType.computeIfPresent(participant.getType(), (type, participantCollection) -> {
                participantCollection.remove(participant);
                return participantCollection.isEmpty() ? null : participantCollection;
            });
        }
        return participant;
    }

    /**
     * Gets a participant by ID.
     *
     * @param participantId the participant ID
     * @return the participant, or null if not found
     */
    public TransactionParticipant getParticipant(String participantId) {
        return participants.get(participantId);
    }

    /**
     * Gets all participants of a specific type.
     *
     * @param type the participant type
     * @return collection of participants of the specified type
     */
    public Collection<TransactionParticipant> getParticipantsByType(TransactionParticipant.ParticipantType type) {
        return participantsByType.getOrDefault(type, ConcurrentHashMap.newKeySet());
    }

    /**
     * Gets all registered participants.
     *
     * @return collection of all participants
     */
    public Collection<TransactionParticipant> getAllParticipants() {
        return participants.values();
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
     * Checks if a participant is registered.
     *
     * @param participantId the participant ID
     * @return true if the participant is registered
     */
    public boolean isParticipantRegistered(String participantId) {
        return participants.containsKey(participantId);
    }

    /**
     * Clears all registered participants.
     */
    public void clear() {
        participants.clear();
        participantsByType.clear();
    }

}