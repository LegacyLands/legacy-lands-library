package net.legacy.library.script.manager;

import com.caoccao.javet.interop.V8Host;
import com.caoccao.javet.interop.V8Runtime;
import io.fairyproject.log.Log;
import lombok.Getter;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages V8 engine lifecycle and ensures proper initialization.
 *
 * @author qwq-dev
 * @since 2025-07-20 01:24
 */
public class V8EngineManager {
    private static final AtomicBoolean initialized = new AtomicBoolean(false);

    @Getter
    private static volatile V8Host v8Host;

    /**
     * Initializes the V8 engine manager.
     *
     * @return true if successfully initialized
     */
    public static synchronized boolean initialize() {
        if (initialized.get()) {
            return true;
        }

        try {
            v8Host = V8Host.getV8Instance();
            initialized.set(true);
            return true;
        } catch (Exception exception) {
            Log.error("Failed to initialize V8EngineManager", exception);
            return false;
        }
    }

    /**
     * Creates a new V8 runtime.
     *
     * @return a new V8Runtime instance
     * @throws IllegalStateException if not initialized
     */
    public static V8Runtime createRuntime() {
        if (!initialized.get()) {
            throw new IllegalStateException("V8EngineManager not initialized");
        }

        try {
            return v8Host.createV8Runtime();
        } catch (Exception exception) {
            throw new IllegalStateException("Failed to create V8 runtime", exception);
        }
    }

    /**
     * Tests if V8 engine is available and working.
     *
     * @return true if V8 is available
     */
    public static boolean isAvailable() {
        try {
            if (!initialize()) {
                return false;
            }

            // Try to create and immediately close a runtime as a test
            try (V8Runtime testRuntime = createRuntime()) {
                testRuntime.getExecutor("1+1").executeInteger();
                return true;
            }
        } catch (Exception exception) {
            Log.warn("V8 engine is not available: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Checks if the manager is initialized.
     *
     * @return true if initialized
     */
    public static boolean isInitialized() {
        return initialized.get();
    }
}