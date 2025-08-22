package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.mc.scheduler.MCSchedulers;
import lombok.RequiredArgsConstructor;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.AsyncSafe;
import net.legacy.library.aop.custom.CustomExecutor;
import net.legacy.library.aop.custom.CustomExecutorRegistry;
import net.legacy.library.aop.custom.CustomLockStrategy;
import net.legacy.library.aop.custom.CustomTimeoutHandler;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.commons.task.TaskInterface;
import org.bukkit.Bukkit;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.Callable;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Aspect for ensuring thread-safe execution of methods.
 *
 * <p>This aspect intercepts methods annotated with {@link AsyncSafe} and
 * ensures they are executed on the appropriate thread type.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@InjectableComponent
@RequiredArgsConstructor
@AOPInterceptor(global = true, order = 200)
public class AsyncSafeAspect implements MethodInterceptor {

    private final Map<String, ReentrantLock> methodLocks = new ConcurrentHashMap<>();

    /**
     * Registers a custom executor with the registry
     *
     * @param executor the custom executor to register
     */
    public static void registerCustomExecutor(CustomExecutor executor) {
        CustomExecutorRegistry.getInstance().registerExecutor(executor);
    }

    /**
     * Registers a custom lock strategy with the registry
     *
     * @param lockStrategy the custom lock strategy to register
     */
    public static void registerCustomLockStrategy(CustomLockStrategy lockStrategy) {
        CustomExecutorRegistry.getInstance().registerLockStrategy(lockStrategy);
    }

    /**
     * Registers a custom timeout handler with the registry
     *
     * @param timeoutHandler the custom timeout handler to register
     */
    public static void registerCustomTimeoutHandler(CustomTimeoutHandler timeoutHandler) {
        CustomExecutorRegistry.getInstance().registerTimeoutHandler(timeoutHandler);
    }

    /**
     * {@inheritDoc}
     *
     * @param context    {@inheritDoc}
     * @param invocation {@inheritDoc}
     * @return {@inheritDoc}
     * @throws Throwable {@inheritDoc}
     */
    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Method method = context.getMethod();
        AsyncSafe asyncSafe = method.getAnnotation(AsyncSafe.class);

        // If method already returns CompletableFuture, don't wrap it again
        if (CompletableFuture.class.isAssignableFrom(method.getReturnType())) {
            return invocation.proceed();
        }

        // Check for re-entrant calls
        if (!asyncSafe.allowReentrant()) {
            String lockKey = generateLockKey(context);
            ReentrantLock lock = methodLocks.computeIfAbsent(lockKey, k -> new ReentrantLock());

            if (lock.isHeldByCurrentThread()) {
                throw new IllegalStateException(
                        "Re-entrant call detected for method: " + method.getName()
                );
            }
        }

        boolean isMainThread = Bukkit.isPrimaryThread();
        AsyncSafe.ThreadType targetThread = asyncSafe.target();

        return switch (targetThread) {
            case SYNC -> executeSync(context, invocation, asyncSafe, isMainThread);
            case ASYNC -> executeAsync(context, invocation, asyncSafe, isMainThread);
            case VIRTUAL -> executeVirtual(context, invocation, asyncSafe);
            case CUSTOM -> executeCustom(context, invocation, asyncSafe);
        };
    }

    /**
     * {@inheritDoc}
     *
     * @param {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean supports(Method method) {
        return method.isAnnotationPresent(AsyncSafe.class);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 50;
    }

    private Object executeSync(AspectContext context, MethodInvocation invocation, AsyncSafe asyncSafe, boolean isMainThread) throws Throwable {
        if (isMainThread) {
            return executeWithLock(context, invocation, asyncSafe);
        }

        // Use TaskInterface to schedule on main thread
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithLock(context, invocation, asyncSafe);
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        }, command -> {
            // Schedule on main thread using global scheduler
            MCSchedulers.getGlobalScheduler().schedule(command);
        });

        return waitForResult(future, asyncSafe.timeout());
    }

    private Object executeAsync(AspectContext context, MethodInvocation invocation, AsyncSafe asyncSafe, boolean isMainThread) throws Throwable {
        if (!isMainThread) {
            return executeWithLock(context, invocation, asyncSafe);
        }

        // Use TaskInterface to schedule on async thread
        CompletableFuture<Object> future = CompletableFuture.supplyAsync(() -> {
            try {
                return executeWithLock(context, invocation, asyncSafe);
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        }, command -> TaskInterface.defaultInstance().getMCScheduler().schedule(command));

        return waitForResult(future, asyncSafe.timeout());
    }

    private Object executeVirtual(AspectContext context, MethodInvocation invocation, AsyncSafe asyncSafe) throws Throwable {
        // Use TaskInterface's virtual thread support
        CompletableFuture<Object> future = TaskInterface.defaultInstance().submitWithVirtualThreadAsync(() -> {
            try {
                return executeWithLock(context, invocation, asyncSafe);
            } catch (Throwable throwable) {
                throw new CompletionException(throwable);
            }
        });

        return waitForResult(future, asyncSafe.timeout());
    }

    private Object executeWithLock(AspectContext context, MethodInvocation invocation, AsyncSafe asyncSafe) throws Throwable {
        if (!asyncSafe.allowReentrant()) {
            String lockKey = generateLockKey(context);
            ReentrantLock lock = methodLocks.get(lockKey);

            if (lock != null) {
                lock.lock();
                try {
                    return invocation.proceed();
                } finally {
                    lock.unlock();
                }
            }
        }

        return invocation.proceed();
    }

    private Object waitForResult(CompletableFuture<Object> future, long timeout) throws Throwable {
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            future.cancel(true);
            throw new TimeoutException("Method execution timed out after " + timeout + "ms");
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            throw cause == null ? executionException : cause;
        }
    }

    private String generateLockKey(AspectContext context) {
        Method method = context.getMethod();
        Object target = context.getTarget();
        return target.getClass().getName() + "#" + method.getName() + "#" + method.getParameterCount();
    }

    public void shutdown() {
        // Clear method locks
        methodLocks.clear();
    }

    private Object executeCustom(AspectContext context, MethodInvocation invocation, AsyncSafe asyncSafe) throws Throwable {
        String executorName = asyncSafe.customExecutor();
        String lockStrategyName = asyncSafe.customLockStrategy();
        String timeoutHandlerName = asyncSafe.customTimeoutHandler();
        Properties properties = CustomExecutorRegistry.parseCustomProperties(asyncSafe.customProperties());

        CustomExecutorRegistry registry = CustomExecutorRegistry.getInstance();

        // Get custom executor
        CustomExecutor executor = null;
        if (!executorName.isEmpty()) {
            executor = registry.getExecutor(executorName);
            if (executor == null) {
                throw new IllegalStateException("Custom executor not found: " + executorName);
            }
        }

        // Get custom lock strategy
        CustomLockStrategy lockStrategy = null;
        if (!lockStrategyName.isEmpty()) {
            lockStrategy = registry.getLockStrategy(lockStrategyName);
            if (lockStrategy == null) {
                throw new IllegalStateException("Custom lock strategy not found: " + lockStrategyName);
            }
        } else {
            lockStrategy = registry.getLockStrategy("default");
        }

        // Get custom timeout handler
        CustomTimeoutHandler timeoutHandler = null;
        if (!timeoutHandlerName.isEmpty()) {
            timeoutHandler = registry.getTimeoutHandler(timeoutHandlerName);
            if (timeoutHandler == null) {
                throw new IllegalStateException("Custom timeout handler not found: " + timeoutHandlerName);
            }
        } else {
            timeoutHandler = registry.getTimeoutHandler("default");
        }

        // Check for re-entrant calls with custom lock strategy
        if (!asyncSafe.allowReentrant() && lockStrategy.isReentrant(context)) {
            throw new IllegalStateException(
                    "Re-entrant call detected for method: " + context.getMethod().getName()
            );
        }

        final CustomTimeoutHandler finalTimeoutHandler = timeoutHandler;
        final CustomLockStrategy finalLockStrategy = lockStrategy;
        final CustomExecutor finalExecutor = executor;

        // Execute with custom components
        if (finalExecutor != null) {
            // Use custom executor
            long startTime = System.currentTimeMillis();
            finalTimeoutHandler.beforeExecution(context, asyncSafe.timeout(), properties);

            try {
                Object result = finalExecutor.execute(context, new MethodInvocation() {
                    @Override
                    public Object proceed() throws Throwable {
                        return executeWithCustomLock(context, invocation, finalLockStrategy, properties);
                    }
                }, properties);

                // Check if result is a CompletableFuture and handle timeout
                if (result instanceof CompletableFuture) {
                    result = waitForResultWithCustomHandler((CompletableFuture<Object>) result, asyncSafe.timeout(),
                            context, finalTimeoutHandler, properties);
                }

                finalTimeoutHandler.afterExecution(context, result,
                        System.currentTimeMillis() - startTime, properties);
                return result;
            } catch (Throwable e) {
                if (e instanceof TimeoutException) {
                    return finalTimeoutHandler.handleTimeout(context, null, asyncSafe.timeout(), properties);
                }
                throw e;
            }
        } else {
            // Use default execution with custom lock and timeout handling
            return executeWithCustomLock(context, invocation, finalLockStrategy, properties);
        }
    }

    private Object executeWithCustomLock(AspectContext context, MethodInvocation invocation,
                                         CustomLockStrategy lockStrategy, Properties properties) throws Throwable {
        if (lockStrategy != null) {
            return lockStrategy.executeWithLock(context, new Callable<Object>() {
                @Override
                public Object call() throws Exception {
                    try {
                        return invocation.proceed();
                    } catch (Throwable e) {
                        if (e instanceof Exception) {
                            throw (Exception) e;
                        }
                        throw new RuntimeException(e);
                    }
                }
            }, properties);
        } else {
            return invocation.proceed();
        }
    }

    private Object waitForResultWithCustomHandler(CompletableFuture<Object> future, long timeout,
                                                  AspectContext context, CustomTimeoutHandler timeoutHandler,
                                                  Properties properties) throws Throwable {
        try {
            return future.get(timeout, TimeUnit.MILLISECONDS);
        } catch (TimeoutException timeoutException) {
            return timeoutHandler.handleTimeout(context, future, timeout, properties);
        } catch (ExecutionException executionException) {
            Throwable cause = executionException.getCause();
            throw cause == null ? executionException : cause;
        }
    }

}