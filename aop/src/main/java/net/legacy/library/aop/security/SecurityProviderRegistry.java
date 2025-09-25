package net.legacy.library.aop.security;

import io.fairyproject.container.InjectableComponent;
import lombok.Getter;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry for managing security providers.
 *
 * <p>This class manages the registration and lookup of security providers.
 * It allows multiple security providers to be used simultaneously and provides
 * a centralized point for security operations.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Getter
@InjectableComponent
public class SecurityProviderRegistry {

    private final Map<String, SecurityProvider> providers = new ConcurrentHashMap<>();
    private final DefaultSecurityProvider defaultSecurityProvider;

    public SecurityProviderRegistry(DefaultSecurityProvider defaultSecurityProvider) {
        this.defaultSecurityProvider = defaultSecurityProvider;
        registerProvider(defaultSecurityProvider);
    }

    /**
     * Registers a security provider.
     *
     * @param provider the security provider to register
     */
    public void registerProvider(SecurityProvider provider) {
        providers.put(provider.getName(), provider);
    }

    /**
     * Unregisters a security provider.
     *
     * @param providerName the name of the provider to unregister
     */
    public void unregisterProvider(String providerName) {
        SecurityProvider provider = providers.remove(providerName);
        if (provider != null) {
            provider.shutdown();
        }
    }

    /**
     * Gets a security provider by name.
     *
     * @param providerName the name of the provider
     * @return the security provider, or null if not found
     */
    public SecurityProvider getProvider(String providerName) {
        return providers.get(providerName);
    }

    /**
     * Gets all registered security providers.
     *
     * @return map of provider names to providers
     */
    public Map<String, SecurityProvider> getAllProviders() {
        return new ConcurrentHashMap<>(providers);
    }

    /**
     * Checks if a security provider is registered.
     *
     * @param providerName the name of the provider
     * @return true if the provider is registered
     */
    public boolean isProviderRegistered(String providerName) {
        return providers.containsKey(providerName);
    }

    /**
     * Gets the default security provider.
     *
     * @return the default provider, or null if no providers are registered
     */
    public SecurityProvider getDefaultProvider() {
        return providers.get("default");
    }

    /**
     * Shuts down all security providers.
     */
    public void shutdownAll() {
        providers.values().forEach(SecurityProvider::shutdown);
        providers.clear();
    }

}
