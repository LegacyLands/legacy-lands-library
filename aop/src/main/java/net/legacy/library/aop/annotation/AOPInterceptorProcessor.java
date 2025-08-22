package net.legacy.library.aop.annotation;

import io.fairyproject.container.Containers;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.registry.InterceptorRegistry;
import net.legacy.library.aop.service.AOPService;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The {@link AOPInterceptorProcessor} is responsible for processing
 * all classes annotated with {@link AOPInterceptor}. Upon processing, it attempts
 * to retrieve an instance of the annotated class from a dependency container (via {@link Containers#get(Class)}),
 * or read the {@link AOPInterceptor#isFromFairyIoC()} property from the annotation and
 * create the instance directly using reflection, then registers it with the AOP service
 * (if it implements {@link MethodInterceptor}).
 *
 * <p>When {@link AOPInterceptor#isFromFairyIoC()} is false, it must contain a no-argument constructor.
 * If we do not want it to be managed by {@code Fairy IoC} but still need to inject dependencies,
 * we need to use the {@link io.fairyproject.container.Autowired} annotation.
 *
 * <p>Interceptors are sorted by their order value before registration, with lower values
 * having higher priority (executed earlier).
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 12:00
 */
@AnnotationProcessor(AOPInterceptor.class)
public class AOPInterceptorProcessor implements CustomAnnotationProcessor {

    private static final Map<Class<?>, MethodInterceptor> NON_IOC_INTERCEPTORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> PROCESSED_CLASSES = new ConcurrentHashMap<>();

    /**
     * Processes a class annotated with {@link AOPInterceptor}.
     *
     * <p>Creates or retrieves an instance of the interceptor and registers it immediately.
     *
     * @param clazz the annotated class to be processed
     */
    @Override
    public void process(Class<?> clazz) throws Exception {
        // Skip if already processed
        if (PROCESSED_CLASSES.putIfAbsent(clazz, Boolean.TRUE) != null) {
            Log.debug("[AOPInterceptorProcessor] Class %s already processed, skipping", clazz.getName());
            return;
        }

        AOPInterceptor aopInterceptor = clazz.getAnnotation(AOPInterceptor.class);

        if (aopInterceptor == null) {
            return;
        }

        if (!MethodInterceptor.class.isAssignableFrom(clazz)) {
            Log.warn("[AOPInterceptorProcessor] Class %s is annotated with @AOPInterceptor but does not implement MethodInterceptor",
                    clazz.getName());
            return;
        }

        MethodInterceptor interceptor;

        if (aopInterceptor.isFromFairyIoC()) {
            interceptor = (MethodInterceptor) Containers.get(clazz);
        } else {
            // For non-IoC interceptors, reuse the same instance
            interceptor = NON_IOC_INTERCEPTORS.computeIfAbsent(clazz, k -> {
                try {
                    return (MethodInterceptor) k.getDeclaredConstructor().newInstance();
                } catch (Exception exception) {
                    Log.error("Failed to create interceptor instance for %s", k.getName(), exception);
                    return null;
                }
            });
        }

        if (interceptor == null) {
            Log.warn("[AOPInterceptorProcessor] Failed to create or retrieve interceptor instance for %s",
                    clazz.getName());
            return;
        }

        // Check if already registered in InterceptorRegistry
        if (InterceptorRegistry.get(interceptor.getClass()) != null) {
            Log.warn("[AOPInterceptorProcessor] Interceptor %s already registered, skipping", clazz.getName());
            return;
        }

        // Register in the InterceptorRegistry for direct access
        InterceptorRegistry.register(interceptor);

        // Register with AOP service if it's a global interceptor
        if (aopInterceptor.global()) {
            AOPService aopService = Containers.get(AOPService.class);
            if (aopService == null) {
                Log.warn("[AOPInterceptorProcessor] AOPService not available yet, skipping global registration for %s",
                        clazz.getName());
                return;
            }
            aopService.registerGlobalInterceptor(interceptor);
            Log.info("[AOPInterceptorProcessor] Registered global interceptor: %s (order=%d)",
                    clazz.getName(), aopInterceptor.order());
        } else {
            Log.info("[AOPInterceptorProcessor] Registered interceptor: %s (order=%d)",
                    clazz.getName(), aopInterceptor.order());
        }
    }

    /**
     * Handles exceptions that occur during the annotation processing phase.
     *
     * @param clazz     the annotated class where the exception occurred
     * @param exception the exception thrown
     */
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("An exception occurred while processing AOP interceptor", exception);
    }

}