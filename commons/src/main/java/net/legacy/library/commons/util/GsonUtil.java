package net.legacy.library.commons.util;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import lombok.experimental.UtilityClass;

import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Utility class for handling {@link Gson} operations.
 *
 * <p>This class provides a thread-safe way to customize and access a shared Gson instance.
 * Customizations can be applied via the {@link #customizeGsonBuilder(Consumer)} method, which safely modifies
 * the shared Gson instance in a thread-safe manner.
 *
 * <p>It is safe to use in multithreaded environments, ensuring only one customized Gson instance at a time.
 * You can extend the Gson configuration by providing additional customizations.
 *
 * @since 2025-01-03 15:16
 */
@UtilityClass
public class GsonUtil {

    /**
     * A shared {@link GsonBuilder} instance to accumulate configurations.
     */
    private static final GsonBuilder GSON_BUILDER = new GsonBuilder();

    /**
     * A shared {@link Gson} instance. This instance will be updated thread-safely.
     */
    private static volatile Gson GSON = GSON_BUILDER.create();

    /**
     * Customizes the shared GsonBuilder and updates the Gson instance.
     *
     * <p>The customization is performed in a thread-safe manner by synchronizing on the class.
     *
     * @param consumer the customization action to apply to the GsonBuilder
     */
    public static synchronized void customizeGsonBuilder(Consumer<GsonBuilder> consumer) {
        consumer.accept(GSON_BUILDER);
        GSON = GSON_BUILDER.create();
    }

    /**
     * Sets a new Gson instance using the provided GsonBuilder supplier.
     *
     * @param supplier the supplier that provides a configured GsonBuilder
     */
    public static synchronized void setNewGson(Supplier<GsonBuilder> supplier) {
        GsonBuilder builder = supplier.get();
        GSON = builder.create();
    }

    /**
     * Retrieves the shared Gson instance.
     *
     * @return the shared Gson instance
     */
    public static Gson getGson() {
        return GSON;
    }

}
