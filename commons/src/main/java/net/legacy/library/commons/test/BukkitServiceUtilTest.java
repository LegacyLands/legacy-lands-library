package net.legacy.library.commons.test;

import net.legacy.library.commons.util.BukkitServiceUtil;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.ServicePriority;
import org.bukkit.plugin.ServicesManager;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.BiConsumer;
import java.util.function.BiFunction;
import java.util.function.Function;

/**
 * Test class for {@link BukkitServiceUtil}, validating lookups by provider plugin name and the guards around every
 * call, against Bukkit's real services manager.
 *
 * <p>Providers are registered under the commons plugin itself and unregistered after each test.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "bukkit-service-util-test",
        description = "Tests BukkitServiceUtil lookups by plugin name, fallbacks and broadcasts",
        tags = {"commons", "services", "bukkit", "signals"},
        priority = 6,
        timeout = 2000,
        expectedResult = "SUCCESS"
)
public class BukkitServiceUtilTest {

    private static final String MODULE_NAME = "commons";
    private static final String PLUGIN_NAME = "commons";

    /**
     * Tests that a provider answers by plugin name, and that an unknown plugin gives the fallback.
     */
    public static boolean testServiceQueryByPluginName() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        ServicesManager servicesManager = Bukkit.getServicesManager();
        BiFunction<String, Integer, String> provider = (text, times) -> text.repeat(times);
        try {
            servicesManager.register(BiFunction.class, provider, plugin, ServicePriority.Normal);

            String answer = BukkitServiceUtil.<BiFunction<String, Integer, String>, String>query(
                    BiFunction.class, PLUGIN_NAME, function -> function.apply("ab", 2), "none");
            String absent = BukkitServiceUtil.<BiFunction<String, Integer, String>, String>query(
                    BiFunction.class, "NoSuchPlugin", function -> function.apply("ab", 2), "none");

            return check("query", "abab".equals(answer) && "none".equals(absent)
                    && BukkitServiceUtil.getProvider(BiFunction.class, PLUGIN_NAME).isPresent()
                    && BukkitServiceUtil.getProvider(BiFunction.class, "NoSuchPlugin").isEmpty());
        } catch (Exception exception) {
            return failure("testServiceQueryByPluginName", exception);
        } finally {
            servicesManager.unregister(BiFunction.class, provider);
        }
    }

    /**
     * Tests that a provider that throws, one built against missing classes, one of another shape and one answering
     * {@code null} all give the fallback.
     */
    public static boolean testServiceFailuresGiveFallback() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        ServicesManager servicesManager = Bukkit.getServicesManager();
        Function<Object, Object> throwing = input -> {
            throw new IllegalStateException("provider failed");
        };
        BiFunction<Object, Object, Object> linkage = (first, second) -> {
            throw new NoClassDefFoundError("net/example/Missing");
        };
        BiConsumer<Integer, Integer> otherShape = (first, second) -> Math.addExact(first, second);
        try {
            servicesManager.register(Function.class, throwing, plugin, ServicePriority.Normal);
            servicesManager.register(BiFunction.class, linkage, plugin, ServicePriority.Normal);
            servicesManager.register(BiConsumer.class, otherShape, plugin, ServicePriority.Normal);

            String thrown = BukkitServiceUtil.<Function<String, String>, String>query(
                    Function.class, PLUGIN_NAME, function -> function.apply("x"), "fallback");
            String linked = BukkitServiceUtil.<BiFunction<String, String, String>, String>query(
                    BiFunction.class, PLUGIN_NAME, function -> function.apply("x", "y"), "fallback");
            boolean called = BukkitServiceUtil.<BiConsumer<String, String>>call(
                    BiConsumer.class, PLUGIN_NAME, consumer -> consumer.accept("not", "numbers"));
            String nullAnswer = BukkitServiceUtil.<BiConsumer<String, String>, String>query(
                    BiConsumer.class, PLUGIN_NAME, consumer -> null, "fallback");

            return check("fallbacks", "fallback".equals(thrown) && "fallback".equals(linked) && !called
                    && "fallback".equals(nullAnswer));
        } catch (Exception exception) {
            return failure("testServiceFailuresGiveFallback", exception);
        } finally {
            servicesManager.unregister(Function.class, throwing);
            servicesManager.unregister(BiFunction.class, linkage);
            servicesManager.unregister(BiConsumer.class, otherShape);
        }
    }

    /**
     * Tests that a broadcast reaches every matching provider, and that one failing does not stop the others.
     */
    public static boolean testServiceBroadcastIsolatesFailures() {
        Plugin plugin = Bukkit.getPluginManager().getPlugin(PLUGIN_NAME);
        ServicesManager servicesManager = Bukkit.getServicesManager();
        List<String> received = new CopyOnWriteArrayList<>();
        BiConsumer<String, String> failing = (player, key) -> {
            throw new IllegalStateException("receiver failed");
        };
        BiConsumer<String, String> working = (player, key) -> received.add(player + ":" + key);
        try {
            servicesManager.register(BiConsumer.class, failing, plugin, ServicePriority.High);
            servicesManager.register(BiConsumer.class, working, plugin, ServicePriority.Normal);

            int delivered = BukkitServiceUtil.<BiConsumer<String, String>>broadcast(
                    BiConsumer.class, PLUGIN_NAME::equals, consumer -> consumer.accept("Alice", "market.trade"));
            int nobody = BukkitServiceUtil.<BiConsumer<String, String>>broadcast(
                    BiConsumer.class, name -> name.startsWith("NoSuch"), consumer -> consumer.accept("Bob", "x"));

            return check("broadcast", delivered == 1 && nobody == 0
                    && received.equals(List.of("Alice:market.trade")));
        } catch (Exception exception) {
            return failure("testServiceBroadcastIsolatesFailures", exception);
        } finally {
            servicesManager.unregister(BiConsumer.class, failing);
            servicesManager.unregister(BiConsumer.class, working);
        }
    }

    private static boolean check(String what, boolean condition) {
        if (!condition) {
            TestLogger.logFailure(MODULE_NAME, "Check failed: %s", what);
        }
        return condition;
    }

    private static boolean failure(String testName, Exception exception) {
        TestLogger.logFailure(MODULE_NAME, "%s failed: %s", testName, exception.getMessage());
        return false;
    }

}
