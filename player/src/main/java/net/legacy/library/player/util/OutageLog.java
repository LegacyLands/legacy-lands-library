package net.legacy.library.player.util;

import io.fairyproject.log.Log;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Logs a failure that repeats while a dependency such as Redis is unreachable: the first one with its cause, the
 * rest only counted, and a line once it works again.
 *
 * <p>A loop that fails every round, or every save, would otherwise write a line each time for as long as the outage
 * lasts. Thread-safe.
 *
 * @author qwq-dev
 * @since 2026-10-07 07:10
 */
public class OutageLog {

    private final String what;
    private final AtomicLong failures = new AtomicLong();

    /**
     * Creates an outage log.
     *
     * @param what what fails, as the start of a sentence, such as {@code "Reading the entity stream"}
     */
    public OutageLog(String what) {
        this.what = what;
    }

    /**
     * Reports a failure: logged with its cause if it is the first since the last success, counted otherwise.
     *
     * @param exception the cause
     */
    public void failed(Exception exception) {
        if (failures.getAndIncrement() == 0) {
            Log.warn("%s failed; further failures are counted until it works again", exception, what);
        }
    }

    /**
     * Reports a success: logs how many failures came before it, if any.
     */
    public void succeeded() {
        long failed = failures.getAndSet(0);
        if (failed > 0) {
            Log.info("%s works again after %s failures", what, failed);
        }
    }

    /**
     * Checks whether the last report was a failure.
     *
     * @return {@code true} while failing
     */
    public boolean isFailing() {
        return failures.get() > 0;
    }

}
