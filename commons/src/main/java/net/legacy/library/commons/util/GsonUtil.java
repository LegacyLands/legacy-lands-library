package net.legacy.library.commons.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;

/**
 * Utility class for handling {@link Gson} operations.
 *
 * <p>This class provides a thread-safe way to customize and access a shared Gson instance.
 * Customizations can be applied via the {@link #customizeGson(Consumer)} method, which safely modifies
 * the shared Gson instance in a thread-safe manner.
 *
 * <p>It is safe to use in multithreaded environments, ensuring only one customized Gson instance at a time.
 *
 * @author qwq-dev
 * @since 2025-01-03 15:16
 */
@UtilityClass
public class GsonUtil {
    /**
     * A shared {@link Gson} instance. This instance will be updated thread-safely.
     */
    public static volatile Gson GSON = new GsonBuilder().create();

    /**
     * Customizes the shared Gson instance.
     *
     * <p>The customization is performed in a thread-safe manner by synchronizing on the GSON instance.
     *
     * @param consumer the customization action to apply to the Gson instance
     */
    public static synchronized void customizeGson(Consumer<Gson> consumer) {
        consumer.accept(GSON);
    }
}
