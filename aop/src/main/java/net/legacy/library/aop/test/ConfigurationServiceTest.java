package net.legacy.library.aop.test;

import net.legacy.library.aop.config.ConfigurationService.ConfigurationMetadata;
import net.legacy.library.aop.config.ConfigurationSource;
import net.legacy.library.aop.config.DefaultConfigurationService;
import net.legacy.library.foundation.annotation.ModuleTest;

import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicReference;

@ModuleTest(
        testName = "aop-configuration-service-test",
        description = "Validates configuration metadata registration, caching behaviour, and scheduled refresh",
        tags = {"aop", "configuration"},
        priority = 2
)
public final class ConfigurationServiceTest {

    private ConfigurationServiceTest() {
    }

    public static boolean testScheduledRefreshUpdatesValue() {
        DefaultConfigurationService service = new DefaultConfigurationService();
        InMemorySource source = new InMemorySource("scheduled-source", 200);
        source.put("dynamic.test.key", "initial");
        service.addConfigurationSource(source);

        ConfigurationMetadata metadata = new ConfigurationMetadata(
                "dynamic.test.key",
                "Test dynamic key",
                "initial",
                "in-memory",
                false,
                "String",
                new String[0],
                "1.0",
                50L,
                true,
                true
        );
        service.registerMetadata(metadata);
        AtomicReference<Object> observed = new AtomicReference<>();
        service.addChangeListener("dynamic.test.key", (key, oldValue, newValue) -> observed.set(newValue));

        String first = service.get("dynamic.test.key", String.class);
        if (!Objects.equals(first, "initial")) {
            service.shutdown();
            return false;
        }

        source.put("dynamic.test.key", "updated");
        try {
            Thread.sleep(150L);
        } catch (InterruptedException interruptedException) {
            Thread.currentThread().interrupt();
            service.shutdown();
            return false;
        }

        String refreshed = service.get("dynamic.test.key", String.class);
        service.shutdown();
        return Objects.equals(refreshed, "updated") && Objects.equals(observed.get(), "updated");
    }

    public static boolean testMetadataDisablesCache() {
        DefaultConfigurationService service = new DefaultConfigurationService();
        InMemorySource source = new InMemorySource("cache-source", 250);
        source.put("dynamic.cache.key", "value-one");
        service.addConfigurationSource(source);

        ConfigurationMetadata metadata = new ConfigurationMetadata(
                "dynamic.cache.key",
                "Cache disabled key",
                "value-one",
                "in-memory",
                false,
                "String",
                new String[0],
                "1.0",
                0L,
                false,
                false
        );
        service.registerMetadata(metadata);

        String first = service.get("dynamic.cache.key", String.class);
        source.put("dynamic.cache.key", "value-two");
        String second = service.get("dynamic.cache.key", String.class);

        service.shutdown();
        return Objects.equals(first, "value-one") && Objects.equals(second, "value-two");
    }

    private static class InMemorySource implements ConfigurationSource {

        private final String name;
        private final int priority;
        private final Map<String, String> store = new ConcurrentHashMap<>();

        InMemorySource(String name, int priority) {
            this.name = name;
            this.priority = priority;
        }

        void put(String key, String value) {
            store.put(key, value);
        }

        @Override
        public String getName() {
            return name;
        }

        @Override
        public int getPriority() {
            return priority;
        }

        @Override
        public Map<String, String> load() {
            return new HashMap<>(store);
        }

        @Override
        public CompletableFuture<Map<String, String>> loadAsync() {
            return CompletableFuture.completedFuture(load());
        }

        @Override
        public boolean supportsMonitoring() {
            return false;
        }

        @Override
        public boolean supportsWriting() {
            return true;
        }

        @Override
        public boolean save(String key, String value) {
            store.put(key, value);
            return true;
        }

        @Override
        public boolean delete(String key) {
            return store.remove(key) != null;
        }

        @Override
        public SourceMetadata getMetadata() {
            return new SourceMetadata(name, "In-memory source", "memory", false, false, Collections.emptyMap());
        }

    }

}
