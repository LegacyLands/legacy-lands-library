package net.legacy.library.aop.proxy;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import lombok.RequiredArgsConstructor;
import net.bytebuddy.ByteBuddy;
import net.bytebuddy.ClassFileVersion;
import net.bytebuddy.description.modifier.FieldManifestation;
import net.bytebuddy.description.modifier.Visibility;
import net.bytebuddy.dynamic.DynamicType;
import net.bytebuddy.dynamic.loading.ClassLoadingStrategy;
import net.bytebuddy.implementation.FieldAccessor;
import net.bytebuddy.implementation.InvocationHandlerAdapter;
import net.bytebuddy.implementation.MethodCall;
import net.bytebuddy.matcher.ElementMatchers;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.service.ClassLoaderIsolationService;

import java.lang.invoke.MethodHandles;
import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Creates proxy instances enriched with the configured AOP interceptors.
 *
 * <p>The factory prefers JDK dynamic proxies whenever the target implements at least one interface.
 * For concrete classes it generates a ByteBuddy subclass that carries an invocation handler field and
 * mirrors the target state before and after each invocation to keep field mutations in sync.
 * 
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@InjectableComponent
@RequiredArgsConstructor
public class AspectProxyFactory {

    private static final ByteBuddy BYTE_BUDDY = new ByteBuddy(ClassFileVersion.ofThisVm());
    private static final String HANDLER_FIELD = "__fairyAopHandler";

    private final ClassLoaderIsolationService isolationService;
    private final Map<Class<?>, List<MethodInterceptor>> interceptorCache = new ConcurrentHashMap<>();

    private static void copyState(Object source, Object target) throws ReflectiveOperationException {
        Class<?> current = source.getClass();
        while (current != null && current != Object.class) {
            java.lang.reflect.Field[] fields = current.getDeclaredFields();
            for (java.lang.reflect.Field field : fields) {
                if (Modifier.isStatic(field.getModifiers()) || Modifier.isFinal(field.getModifiers())) {
                    continue;
                }
                field.setAccessible(true);
                Object value = field.get(source);
                field.set(target, value);
            }
            current = current.getSuperclass();
        }
    }

    /**
     * Creates a proxy instance for the given target object using the provided interfaces.
     */
    @SuppressWarnings("unchecked")
    public <T> T createProxy(T target, Class<?>[] interfaces, List<MethodInterceptor> interceptors) {
        InvocationHandler handler = new AspectInvocationHandler(target, interceptors, isolationService);
        return (T) Proxy.newProxyInstance(target.getClass().getClassLoader(), interfaces, handler);
    }

    /**
     * Creates a proxy instance for the given target, falling back to class-based proxies when needed.
     */
    public <T> T createProxy(T target, List<MethodInterceptor> interceptors) {
        List<MethodInterceptor> sortedInterceptors = new ArrayList<>(interceptors);
        sortedInterceptors.sort(Comparator.comparingInt(MethodInterceptor::getOrder));

        Class<?>[] interfaces = target.getClass().getInterfaces();
        if (interfaces.length > 0) {
            return createProxy(target, interfaces, sortedInterceptors);
        }

        return createClassProxy(target, sortedInterceptors);
    }

    private <T> T createClassProxy(T target, List<MethodInterceptor> interceptors) {
        Class<?> targetClass = target.getClass();

        if (Modifier.isFinal(targetClass.getModifiers())) {
            throw new IllegalArgumentException("Cannot create proxy for final class: " + targetClass.getName());
        }

        ClassProxyInvocationHandler handler = new ClassProxyInvocationHandler(target, interceptors, isolationService);

        try {
            ClassLoadingStrategy<ClassLoader> strategy = resolveClassLoadingStrategy(targetClass);

            try (DynamicType.Unloaded<?> dynamicType = BYTE_BUDDY
                    .subclass(targetClass)
                    .defineField(HANDLER_FIELD, InvocationHandler.class, Visibility.PRIVATE, FieldManifestation.FINAL)
                    .defineConstructor(Visibility.PUBLIC)
                    .withParameters(InvocationHandler.class)
                    .intercept(MethodCall.invoke(targetClass.getDeclaredConstructor())
                            .andThen(FieldAccessor.ofField(HANDLER_FIELD).setsArgumentAt(0)))
                    .method(ElementMatchers.not(ElementMatchers.isFinalizer()))
                    .intercept(InvocationHandlerAdapter.toField(HANDLER_FIELD))
                    .make()) {
                @SuppressWarnings("unchecked")
                Class<? extends T> proxyType = (Class<? extends T>) dynamicType
                        .load(targetClass.getClassLoader(), strategy)
                        .getLoaded();

                T proxyInstance = proxyType.getDeclaredConstructor(InvocationHandler.class).newInstance(handler);
                handler.setProxyInstance(proxyInstance);
                copyState(target, proxyInstance);
                return proxyInstance;
            }
        } catch (NoSuchMethodException noSuchMethodException) {
            throw new IllegalArgumentException("Target class must provide a no-arg constructor: " + targetClass.getName(),
                    noSuchMethodException);
        } catch (ReflectiveOperationException reflectiveOperationException) {
            throw new IllegalStateException("Failed to create class-based proxy for " + targetClass.getName(),
                    reflectiveOperationException);
        }
    }

    private ClassLoadingStrategy<ClassLoader> resolveClassLoadingStrategy(Class<?> targetClass) {
        try {
            MethodHandles.Lookup privateLookup = MethodHandles.privateLookupIn(targetClass, MethodHandles.lookup());
            return ClassLoadingStrategy.UsingLookup.of(privateLookup);
        } catch (IllegalAccessException illegalAccessException) {
            return ClassLoadingStrategy.Default.INJECTION;
        }
    }

    /**
     * Registers an interceptor for a specific class.
     */
    public void registerInterceptor(Class<?> targetClass, MethodInterceptor interceptor) {
        interceptorCache.computeIfAbsent(targetClass, key -> new ArrayList<>()).add(interceptor);
        Log.info("Registered interceptor %s for class %s", interceptor.getClass().getSimpleName(), targetClass.getName());
    }

    /**
     * Gets all registered interceptors for a class.
     */
    public List<MethodInterceptor> getInterceptors(Class<?> targetClass) {
        return interceptorCache.getOrDefault(targetClass, new ArrayList<>());
    }

    /**
     * Invocation handler used by ByteBuddy-generated subclasses to keep the target and proxy instances in sync.
     */
    private static class ClassProxyInvocationHandler extends AspectInvocationHandler {

        private volatile Object proxyInstance;

        private ClassProxyInvocationHandler(Object target,
                                            List<MethodInterceptor> interceptors,
                                            ClassLoaderIsolationService isolationService) {
            super(target, interceptors, isolationService);
        }

        /**
         * Records the proxy created by ByteBuddy so the handler can mirror state before and after the invocation.
         *
         * @param proxyInstance the proxy instance created for the target
         */
        void setProxyInstance(Object proxyInstance) {
            this.proxyInstance = proxyInstance;
        }

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            Object effectiveProxy = proxyInstance != null ? proxyInstance : proxy;
            Object target = getTarget();

            copyState(effectiveProxy, target);
            try {
                return super.invoke(proxy, method, args);
            } finally {
                copyState(target, effectiveProxy);
            }
        }

    }

}
