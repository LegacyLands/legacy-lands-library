package net.legacy.library.player.task;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * What one server still has to write to L2: the entries saved into its L1 cache since they were last written there.
 *
 * <p>Every save gets a ticket, increasing with every save; writing an entry to L2 records the ticket of the latest save
 * it covers, so a save can tell when its state has reached L2.
 *
 * <p>Thread-safe.
 *
 * @author qwq-dev
 * @since 2026-10-07 04:10
 */
public class PersistenceBacklog {

    private final AtomicLong tickets = new AtomicLong();

    /**
     * The ticket of the latest save of each entry not yet written to L2.
     */
    private final Map<UUID, Long> saved = new ConcurrentHashMap<>();

    /**
     * The ticket of the latest save each entry's last write to L2 covered, for as long as a save may wait for it.
     */
    private final Cache<UUID, Long> written = Caffeine.newBuilder()
            .expireAfterWrite(10, TimeUnit.MINUTES)
            .build();

    /**
     * Notes an entry saved into L1, to be written to L2.
     *
     * @param uuid the entry's UUID
     * @return the save's ticket
     */
    public long saved(UUID uuid) {
        long ticket = tickets.incrementAndGet();
        saved.merge(uuid, ticket, Math::max);
        return ticket;
    }

    /**
     * Gets the entries saved into L1 and not yet written to L2.
     *
     * @return a copy of the entries' UUIDs
     */
    public Set<UUID> savedEntries() {
        return new HashSet<>(saved.keySet());
    }

    /**
     * Gets the ticket of an entry's latest save not yet written to L2, to read before its state is serialized for L2.
     *
     * @param uuid the entry's UUID
     * @return the ticket, or 0 if every save of it was written
     */
    public long pendingTicket(UUID uuid) {
        return saved.getOrDefault(uuid, 0L);
    }

    /**
     * Records that an entry's state, serialized after {@link #pendingTicket(UUID)} returned the given ticket, is in L2.
     *
     * @param uuid   the entry's UUID
     * @param ticket the ticket read before the state was serialized
     */
    public void written(UUID uuid, long ticket) {
        if (ticket == 0) {
            return;
        }
        written.asMap().merge(uuid, ticket, Math::max);
        saved.remove(uuid, ticket);
    }

    /**
     * Checks whether the state of a save is in L2.
     *
     * @param uuid   the entry's UUID
     * @param ticket the save's ticket
     * @return {@code true} if a write to L2 covered the save
     */
    public boolean isWritten(UUID uuid, long ticket) {
        Long pending = saved.get(uuid);
        if (pending == null) {
            return true;
        }
        Long covered = written.getIfPresent(uuid);
        return covered != null && covered >= ticket;
    }

}
