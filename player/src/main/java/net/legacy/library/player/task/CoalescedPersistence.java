package net.legacy.library.player.task;

import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Queue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Function;

/**
 * Runs the persistence a save asks for, coalescing the requests of many saves.
 *
 * <p>A persistence writes everything in the cache, not only the entry just saved, and takes a lock every server of the
 * service shares. Started once per save, a burst of saves runs one persistence per save, all contending for that
 * lock. Coalesced, at most one runs at a time on this server: a save during a run asks for one more, which starts
 * when the run ends and covers every save that asked meanwhile. A save therefore never waits longer for its data to
 * be persisted than the run already going plus its own. Each request gets a future of the run covering it, so a save
 * can act once its state is in L2.
 *
 * <p>Thread-safe.
 *
 * @author qwq-dev
 * @since 2026-10-07 01:30
 */
@RequiredArgsConstructor
public class CoalescedPersistence {

    /**
     * Starts a persistence run: completes the future it is given once the cache has reached L2, and returns the
     * future of the whole run.
     */
    private final Function<CompletableFuture<Void>, CompletableFuture<?>> persistence;
    private final AtomicBoolean running = new AtomicBoolean();
    private final AtomicBoolean requested = new AtomicBoolean();
    private final Queue<CompletableFuture<Void>> waiting = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean closed = new AtomicBoolean();

    /**
     * Completed by the final persistence of a shutdown; what the requests made after {@link #close()} wait for.
     */
    private final CompletableFuture<Void> finalRun = new CompletableFuture<>();

    /**
     * How long the next run waits after a failed one, in milliseconds; 0 after a run that succeeded.
     */
    private final AtomicLong backoff = new AtomicLong();

    private static final long FIRST_BACKOFF_MILLIS = 200;
    private static final long MAX_BACKOFF_MILLIS = 5000;

    /**
     * Asks for a persistence: starts one now if none is running, otherwise makes sure one more follows the current run.
     *
     * @return a future completed once a run that started after this request has put the cache in L2, or has ended,
     *         failed or not: what was cached before the request has then reached L2 unless the run failed
     */
    public CompletableFuture<Void> request() {
        // The final persistence of a shutdown covers what is saved from then on
        if (closed.get()) {
            return finalRun;
        }

        CompletableFuture<Void> covered = new CompletableFuture<>();
        waiting.add(covered);
        requested.set(true);
        if (running.compareAndSet(false, true)) {
            runRequested();
        }
        return covered;
    }

    /**
     * Starts no more runs, for a shutdown: a request from then on waits for the final persistence, which completes
     * it with {@link #completeFinalRun()}. A run already going or asked for still runs.
     */
    public void close() {
        closed.set(true);
    }

    /**
     * Completes the requests made after {@link #close()}, once the final persistence has put the cache in L2.
     */
    public void completeFinalRun() {
        finalRun.complete(null);
    }

    /**
     * Waits until no persistence runs and none is asked for, at most the given time.
     *
     * @param timeout how long to wait at most
     * @return {@code true} if idle, {@code false} if the time ran out first
     * @throws InterruptedException if interrupted while waiting
     */
    public boolean awaitIdle(Duration timeout) throws InterruptedException {
        long deadline = System.nanoTime() + timeout.toNanos();
        while (running.get() || requested.get()) {
            if (System.nanoTime() >= deadline) {
                return false;
            }
            Thread.sleep(20);
        }
        return true;
    }

    private void runRequested() {
        if (!requested.getAndSet(false)) {
            running.set(false);

            // A request may have landed between the check and the reset; it must not be left waiting
            if (requested.get() && running.compareAndSet(false, true)) {
                runRequested();
            }
            return;
        }

        // Every request waiting now was made before this run starts, so the run covers it
        List<CompletableFuture<Void>> covered = new ArrayList<>();
        for (CompletableFuture<Void> next = waiting.poll(); next != null; next = waiting.poll()) {
            covered.add(next);
        }

        CompletableFuture<Void> synced = new CompletableFuture<>();
        synced.whenComplete((ignored, throwable) -> covered.forEach(future -> future.complete(null)));

        CompletableFuture<?> run;
        try {
            run = persistence.apply(synced);
        } catch (Exception exception) {
            Log.error("Failed to start a coalesced persistence", exception);
            synced.complete(null);
            running.set(false);

            // A request that landed meanwhile still gets its run
            if (requested.get() && running.compareAndSet(false, true)) {
                runRequested();
            }
            return;
        }

        run.whenComplete((ignored, throwable) -> {
            synced.complete(null);

            if (throwable == null) {
                backoff.set(0);
                runRequested();
                return;
            }

            /*
             * A failed run, such as with Redis unreachable, is retried until one succeeds, as the saves it covered still
             * wait to be written; each retry waits longer, up to 5 seconds. A shutdown stops the retries
             */
            long delay = backoff.updateAndGet(previous -> previous == 0 ? FIRST_BACKOFF_MILLIS : Math.min(MAX_BACKOFF_MILLIS, previous * 2));
            CompletableFuture.delayedExecutor(delay, TimeUnit.MILLISECONDS).execute(() -> {
                if (!closed.get()) {
                    requested.set(true);
                }
                runRequested();
            });
        });
    }

}
