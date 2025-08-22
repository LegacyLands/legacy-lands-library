package net.legacy.library.aop.annotation;

import io.fairyproject.container.Containers;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.PointcutMethodInterceptor;
import net.legacy.library.aop.registry.InterceptorRegistry;
import net.legacy.library.aop.service.AOPService;

import java.lang.reflect.Constructor;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Processor for classes annotated with {@link AOPPointcut}.
 *
 * <p>This processor handles the registration of interceptors that use pointcut
 * expressions for flexible method matching.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-20 19:40
 */
@AnnotationProcessor(AOPPointcut.class)
public class AOPPointcutProcessor implements CustomAnnotationProcessor {

    private static final Map<Class<?>, MethodInterceptor> POINTCUT_INTERCEPTORS = new ConcurrentHashMap<>();
    private static final Map<Class<?>, Boolean> PROCESSED_CLASSES = new ConcurrentHashMap<>();

    /**
     * {@inheritDoc}
     *
     * <p>This implementation processes classes annotated with {@link AOPPointcut},
     * validates they extend {@link PointcutMethodInterceptor}, and registers them
     * with the interceptor registry and AOP service.
     */
    @Override
    public void process(Class<?> clazz) {
        // Skip if already processed
        if (PROCESSED_CLASSES.putIfAbsent(clazz, Boolean.TRUE) != null) {
            Log.debug("[AOPPointcutProcessor] Class %s already processed, skipping", clazz.getName());
            return;
        }

        AOPPointcut pointcutAnnotation = clazz.getAnnotation(AOPPointcut.class);
        if (pointcutAnnotation == null) {
            return;
        }

        // Check if the class extends PointcutMethodInterceptor
        if (!PointcutMethodInterceptor.class.isAssignableFrom(clazz)) {
            Log.warn("[AOPPointcutProcessor] Class %s has @AOPPointcut but doesn't extend PointcutMethodInterceptor",
                    clazz.getName());
            return;
        }

        // Check if it also has @AOPInterceptor
        AOPInterceptor interceptorAnnotation = clazz.getAnnotation(AOPInterceptor.class);
        if (interceptorAnnotation == null) {
            Log.warn("[AOPPointcutProcessor] Class %s has @AOPPointcut but missing @AOPInterceptor annotation",
                    clazz.getName());
            return;
        }

        // Create or retrieve the interceptor instance
        MethodInterceptor interceptor = createOrRetrieveInterceptor(clazz, interceptorAnnotation);

        if (interceptor == null) {
            Log.warn("[AOPPointcutProcessor] Failed to create interceptor instance for %s", clazz.getName());
            return;
        }

        // Register in the InterceptorRegistry
        if (InterceptorRegistry.get(interceptor.getClass()) == null) {
            InterceptorRegistry.register(interceptor);
        }

        // Register with AOP service if it's a global interceptor
        if (interceptorAnnotation.global()) {
            AOPService aopService = Containers.get(AOPService.class);
            if (aopService != null) {
                aopService.registerGlobalInterceptor(interceptor);
                Log.info("[AOPPointcutProcessor] Registered global pointcut interceptor: %s with expression: %s",
                        clazz.getName(), pointcutAnnotation.value());
            }
        } else {
            Log.info("[AOPPointcutProcessor] Registered pointcut interceptor: %s with expression: %s",
                    clazz.getName(), pointcutAnnotation.value());
        }
    }

    private MethodInterceptor createOrRetrieveInterceptor(Class<?> clazz, AOPInterceptor interceptorAnnotation) {
        if (interceptorAnnotation.isFromFairyIoC()) {
            return (MethodInterceptor) Containers.get(clazz);
        } else {
            return POINTCUT_INTERCEPTORS.computeIfAbsent(clazz, k -> {
                try {
                    // Try to find a constructor that takes the interceptor class
                    Constructor<?> constructor = k.getDeclaredConstructor(Class.class);
                    return (MethodInterceptor) constructor.newInstance(k);
                } catch (NoSuchMethodException noSuchMethodException) {
                    try {
                        // Fall back to no-arg constructor
                        return (MethodInterceptor) k.getDeclaredConstructor().newInstance();
                    } catch (Exception exception) {
                        Log.error("Failed to create pointcut interceptor instance for %s", k.getName(), exception);
                        return null;
                    }
                } catch (Exception exception) {
                    Log.error("Failed to create pointcut interceptor instance for %s", k.getName(), exception);
                    return null;
                }
            });
        }
    }

    /**
     * {@inheritDoc}
     */
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("An exception occurred while processing pointcut interceptor", exception);
    }

}