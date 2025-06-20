package net.legacy.library.aop.custom;

import net.legacy.library.aop.model.AspectContext;

import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Registry for managing custom executors, lock strategies, and timeout handlers.
 *
 * <p>This registry provides centralized management of all custom components
 * used by the AsyncSafe aspect for flexible execution strategies.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 18:43
 */
public class CustomExecutorRegistry {
    private static final CustomExecutorRegistry INSTANCE = new CustomExecutorRegistry();
    
    private final ConcurrentMap<String, CustomExecutor> executors = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CustomLockStrategy> lockStrategies = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, CustomTimeoutHandler> timeoutHandlers = new ConcurrentHashMap<>();
    
    private CustomExecutorRegistry() {
        // Register default implementations
        registerDefaultImplementations();
    }
    
    /**
     * Gets the singleton instance of the registry
     *
     * @return the registry instance
     */
    public static CustomExecutorRegistry getInstance() {
        return INSTANCE;
    }
    
    /**
     * Registers a custom executor
     *
     * @param executor the executor to register
     * @throws IllegalArgumentException if an executor with the same name already exists
     */
    public void registerExecutor(CustomExecutor executor) {
        String name = executor.getName();
        if (executors.containsKey(name)) {
            throw new IllegalArgumentException("Custom executor already registered: " + name);
        }
        executors.put(name, executor);
    }
    
    /**
     * Registers a custom lock strategy
     *
     * @param lockStrategy the lock strategy to register
     * @throws IllegalArgumentException if a strategy with the same name already exists
     */
    public void registerLockStrategy(CustomLockStrategy lockStrategy) {
        String name = lockStrategy.getName();
        if (lockStrategies.containsKey(name)) {
            throw new IllegalArgumentException("Custom lock strategy already registered: " + name);
        }
        lockStrategies.put(name, lockStrategy);
    }
    
    /**
     * Registers a custom timeout handler
     *
     * @param timeoutHandler the timeout handler to register
     * @throws IllegalArgumentException if a handler with the same name already exists
     */
    public void registerTimeoutHandler(CustomTimeoutHandler timeoutHandler) {
        String name = timeoutHandler.getName();
        if (timeoutHandlers.containsKey(name)) {
            throw new IllegalArgumentException("Custom timeout handler already registered: " + name);
        }
        timeoutHandlers.put(name, timeoutHandler);
    }
    
    /**
     * Gets a custom executor by name
     *
     * @param name the executor name
     * @return the executor, or null if not found
     */
    public CustomExecutor getExecutor(String name) {
        return executors.get(name);
    }
    
    /**
     * Gets a custom lock strategy by name
     *
     * @param name the strategy name
     * @return the lock strategy, or null if not found
     */
    public CustomLockStrategy getLockStrategy(String name) {
        return lockStrategies.get(name);
    }
    
    /**
     * Gets a custom timeout handler by name
     *
     * @param name the handler name
     * @return the timeout handler, or null if not found
     */
    public CustomTimeoutHandler getTimeoutHandler(String name) {
        return timeoutHandlers.get(name);
    }
    
    /**
     * Unregisters a custom executor
     *
     * @param name the executor name
     * @return the removed executor, or null if not found
     */
    public CustomExecutor unregisterExecutor(String name) {
        CustomExecutor removed = executors.remove(name);
        if (removed != null) {
            removed.shutdown();
        }
        return removed;
    }
    
    /**
     * Unregisters a custom lock strategy
     *
     * @param name the strategy name
     * @return the removed lock strategy, or null if not found
     */
    public CustomLockStrategy unregisterLockStrategy(String name) {
        CustomLockStrategy removed = lockStrategies.remove(name);
        if (removed != null) {
            removed.shutdown();
        }
        return removed;
    }
    
    /**
     * Unregisters a custom timeout handler
     *
     * @param name the handler name
     * @return the removed timeout handler, or null if not found
     */
    public CustomTimeoutHandler unregisterTimeoutHandler(String name) {
        CustomTimeoutHandler removed = timeoutHandlers.remove(name);
        if (removed != null) {
            removed.shutdown();
        }
        return removed;
    }
    
    /**
     * Shuts down all registered components
     */
    public void shutdown() {
        executors.values().forEach(CustomExecutor::shutdown);
        lockStrategies.values().forEach(CustomLockStrategy::shutdown);
        timeoutHandlers.values().forEach(CustomTimeoutHandler::shutdown);
        
        executors.clear();
        lockStrategies.clear();
        timeoutHandlers.clear();
    }
    
    /**
     * Converts string array properties to Properties object
     *
     * @param customProperties array of "key=value" strings
     * @return Properties object
     */
    public static Properties parseCustomProperties(String[] customProperties) {
        Properties properties = new Properties();
        for (String property : customProperties) {
            String[] parts = property.split("=", 2);
            if (parts.length == 2) {
                properties.setProperty(parts[0].trim(), parts[1].trim());
            }
        }
        return properties;
    }
    
    private void registerDefaultImplementations() {
        // Register default timeout handler
        registerTimeoutHandler(new CustomTimeoutHandler() {
            @Override
            public String getName() {
                return "default";
            }
            
            @Override
            public Object handleTimeout(AspectContext context, CompletableFuture<?> future,
                                        long timeout, Properties properties) throws Throwable {
                future.cancel(true);
                throw new TimeoutException(
                    "Method execution timed out after " + timeout + "ms: " + 
                    context.getMethod().getName()
                );
            }
        });
        
        // Register default lock strategy
        registerLockStrategy(new CustomLockStrategy() {
            private final ConcurrentHashMap<String, ReentrantLock> locks =
                new ConcurrentHashMap<>();
            
            @Override
            public String getName() {
                return "default";
            }
            
            @Override
            public <T> T executeWithLock(AspectContext context, 
                                       Callable<T> operation, 
                                       Properties properties) throws Exception {
                String lockKey = generateLockKey(context);
                ReentrantLock lock = locks.computeIfAbsent(lockKey, 
                    k -> new ReentrantLock());
                
                lock.lock();
                try {
                    return operation.call();
                } finally {
                    lock.unlock();
                }
            }
            
            @Override
            public boolean isReentrant(AspectContext context) {
                String lockKey = generateLockKey(context);
                ReentrantLock lock = locks.get(lockKey);
                return lock != null && lock.isHeldByCurrentThread();
            }
            
            private String generateLockKey(AspectContext context) {
                return context.getTarget().getClass().getName() + "#" + 
                       context.getMethod().getName() + "#" + 
                       context.getMethod().getParameterCount();
            }
        });
    }
}