package net.legacy.library.aop.service;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.registry.AspectRegistry;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReadWriteLock;
import java.util.concurrent.locks.ReentrantReadWriteLock;

/**
 * Manages ClassLoader-level isolation for AOP operations.
 *
 * <p>This service ensures that aspects from different plugins do not interfere
 * with each other by maintaining separate aspect registries per ClassLoader.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
public class ClassLoaderIsolationService {
    private final Map<ClassLoader, AspectRegistry> registries = new ConcurrentHashMap<>();
    private final ReadWriteLock lock = new ReentrantReadWriteLock();

    /**
     * Gets or creates an aspect registry for the given ClassLoader.
     *
     * @param classLoader the ClassLoader
     * @return the aspect registry
     */
    public AspectRegistry getRegistry(ClassLoader classLoader) {
        Lock readLock = lock.readLock();
        readLock.lock();
        try {
            AspectRegistry registry = registries.get(classLoader);
            if (registry != null) {
                return registry;
            }
        } finally {
            readLock.unlock();
        }

        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            return registries.computeIfAbsent(classLoader, AspectRegistry::new);
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Checks if aspects should be applied based on ClassLoader isolation.
     *
     * @param aspectContext the aspect context
     * @param aspectLoader  the ClassLoader of the aspect
     * @return true if the aspect should be applied
     */
    public boolean shouldApplyAspect(AspectContext aspectContext, ClassLoader aspectLoader) {
        ClassLoader targetLoader = aspectContext.getClassLoader();

        // Same ClassLoader - always apply
        if (targetLoader == aspectLoader) {
            return true;
        }

        // Check parent-child relationship
        ClassLoader current = targetLoader;
        while (current != null) {
            if (current == aspectLoader) {
                return true;
            }
            current = current.getParent();
        }

        // Check if aspect is from system ClassLoader (applies globally)
        return aspectLoader == ClassLoader.getSystemClassLoader();
    }

    /**
     * Cleans up resources for a ClassLoader.
     *
     * @param classLoader the ClassLoader to clean up
     */
    public void cleanup(ClassLoader classLoader) {
        Lock writeLock = lock.writeLock();
        writeLock.lock();
        try {
            AspectRegistry registry = registries.remove(classLoader);
            if (registry != null) {
                registry.clear();
                Log.info("Cleaned up AspectRegistry for ClassLoader: %s", classLoader);
            }
        } finally {
            writeLock.unlock();
        }
    }

}