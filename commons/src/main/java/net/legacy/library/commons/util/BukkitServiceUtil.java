package net.legacy.library.commons.util;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;
import org.bukkit.Bukkit;
import org.bukkit.plugin.RegisteredServiceProvider;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;

/**
 * Utility class for calling services other plugins register with Bukkit's {@link org.bukkit.plugin.ServicesManager},
 * found by the name of the plugin that provides them, without a compile-time dependency on that plugin.
 *
 * <p>A plugin registers a provider under a JDK type, such as {@code BiConsumer<UUID, String>} for a signal or
 * {@code Function<UUID, Integer>} for a query, and another plugin looks it up by that type and the provider plugin's
 * name. Neither jar names a class of the other, so neither has to be rebuilt when the other changes, and either can
 * be installed alone.
 *
 * <p>Absence is a normal answer: no provider, a provider that throws, and a provider whose plugin was built against
 * classes that are not there ({@link LinkageError}) all give the caller's fallback instead of an exception. Failures
 * are logged at debug level only. Because providers are told apart by type and plugin name, a plugin should register
 * at most one provider per JDK type; a second one of the same type may be found in place of the first.
 *
 * <p>Generic types are erased at runtime, so a provider of the right raw type but another shape fails with a
 * {@link ClassCastException} when called; the guarded methods treat that like any other failure. All methods are
 * thread-safe as far as Bukkit's services manager is.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class BukkitServiceUtil {

    /**
     * Gets the provider a plugin registered for a type.
     *
     * @param type       the registered type, such as {@code BiConsumer.class}
     * @param pluginName the exact name of the plugin that provides it
     * @param <T>        the type the caller expects, such as {@code BiConsumer<UUID, String>}; the cast is unchecked
     * @return the provider with the highest priority, or empty when that plugin registered none
     */
    public static <T> Optional<T> getProvider(Class<? super T> type, String pluginName) {
        return BukkitServiceUtil.<T>getProviders(type, pluginName::equals).stream().findFirst();
    }

    /**
     * Gets the providers of every plugin whose name matches, highest priority first.
     *
     * @param type             the registered type
     * @param pluginNameFilter which provider plugins to include, by name
     * @param <T>              the type the caller expects; the cast is unchecked
     * @return the providers, or an empty list when there are none or the services manager cannot be read
     */
    @SuppressWarnings("unchecked")
    public static <T> List<T> getProviders(Class<? super T> type, Predicate<String> pluginNameFilter) {
        try {
            return Bukkit.getServicesManager().getRegistrations(type).stream()
                    .filter(registration -> pluginNameFilter.test(registration.getPlugin().getName()))
                    .map(RegisteredServiceProvider::getProvider)
                    .map(provider -> (T) provider)
                    .toList();
        } catch (RuntimeException | LinkageError exception) {
            Log.debug("[BukkitServiceUtil] Could not read the providers of %s: %s", type.getName(), exception.toString());
            return List.of();
        }
    }

    /**
     * Asks a plugin's provider a question, giving a fallback when there is no answer.
     *
     * @param type       the registered type
     * @param pluginName the exact name of the plugin that provides it
     * @param question   what to ask the provider
     * @param fallback   the answer when the plugin provides nothing, the provider fails, or it answers {@code null}
     * @param <T>        the type the caller expects; the cast is unchecked
     * @param <R>        the type of the answer
     * @return the provider's answer, or {@code fallback}
     */
    public static <T, R> R query(Class<? super T> type, String pluginName, Function<? super T, ? extends R> question,
                                 R fallback) {
        Optional<T> provider = getProvider(type, pluginName);
        if (provider.isEmpty()) {
            return fallback;
        }

        try {
            R answer = question.apply(provider.get());
            return answer == null ? fallback : answer;
        } catch (RuntimeException | LinkageError exception) {
            Log.debug("[BukkitServiceUtil] %s did not answer through %s: %s",
                    pluginName, type.getName(), exception.toString());
            return fallback;
        }
    }

    /**
     * Calls a plugin's provider, doing nothing when there is none.
     *
     * @param type       the registered type
     * @param pluginName the exact name of the plugin that provides it
     * @param call       what to do with the provider
     * @param <T>        the type the caller expects; the cast is unchecked
     * @return {@code true} if the provider was found and returned normally
     */
    public static <T> boolean call(Class<? super T> type, String pluginName, Consumer<? super T> call) {
        return BukkitServiceUtil.<T, Boolean>query(type, pluginName, provider -> {
            call.accept(provider);
            return Boolean.TRUE;
        }, Boolean.FALSE);
    }

    /**
     * Calls the providers of every plugin whose name matches, such as every sibling plugin of a family, each on its
     * own: one that fails does not keep the others from being called.
     *
     * @param type             the registered type
     * @param pluginNameFilter which provider plugins to call, by name; exclude the calling plugin to avoid calling
     *                         itself
     * @param call             what to do with each provider
     * @param <T>              the type the caller expects; the cast is unchecked
     * @return the number of providers that returned normally
     */
    public static <T> int broadcast(Class<? super T> type, Predicate<String> pluginNameFilter, Consumer<? super T> call) {
        List<T> providers = getProviders(type, pluginNameFilter);
        return (int) providers.stream().filter(provider -> {
            try {
                call.accept(provider);
                return true;
            } catch (RuntimeException | LinkageError exception) {
                Log.debug("[BukkitServiceUtil] A provider of %s failed: %s", type.getName(), exception.toString());
                return false;
            }
        }).count();
    }

}
