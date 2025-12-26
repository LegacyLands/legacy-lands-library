package net.legacy.library.aop.aspect;

import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import net.legacy.library.aop.annotation.AOPInterceptor;
import net.legacy.library.aop.annotation.DynamicConfig;
import net.legacy.library.aop.config.ConfigurationService;
import net.legacy.library.aop.config.DefaultConfigurationService;
import net.legacy.library.aop.interceptor.MethodInterceptor;
import net.legacy.library.aop.interceptor.MethodInvocation;
import net.legacy.library.aop.model.AspectContext;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Arrays;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Aspect for dynamic configuration injection and management.
 *
 * <p>This aspect intercepts methods on classes or methods annotated with {@link DynamicConfig}
 * and injects configuration values from {@link DefaultConfigurationService}. It supports
 * field-level injection, hot-reload via refresh intervals, and change callbacks.
 *
 * <p>This aspect runs at order 10 (highest priority) to ensure configuration values
 * are injected before other aspects that may depend on them.
 *
 * @author qwq-dev
 * @version 1.0
 * @see DynamicConfig
 * @see DefaultConfigurationService
 * @since 2025-12-25 15:00
 */
@InjectableComponent
@AOPInterceptor(global = true, order = 10)
public class DynamicConfigAspect implements MethodInterceptor {

    private final DefaultConfigurationService configurationService;
    private final Map<String, Method> callbackCache = new ConcurrentHashMap<>();
    private final Map<String, Object> lastValueCache = new ConcurrentHashMap<>();
    private final Map<Class<?>, Map<Field, DynamicConfig>> fieldAnnotationCache = new ConcurrentHashMap<>();

    /**
     * Constructs a new DynamicConfigAspect.
     *
     * @param configurationService the configuration service for value retrieval
     */
    public DynamicConfigAspect(DefaultConfigurationService configurationService) {
        this.configurationService = configurationService;
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
        Object target = context.getTarget();
        Class<?> targetClass = target.getClass();

        // Handle method-level @DynamicConfig
        Method method = context.getMethod();
        DynamicConfig methodConfig = method.getAnnotation(DynamicConfig.class);
        if (methodConfig != null) {
            registerMethodConfiguration(method, methodConfig);
        }

        // Inject field-level configurations
        injectFieldConfigurations(target, targetClass);

        return invocation.proceed();
    }

    /**
     * {@inheritDoc}
     *
     * @param method {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public boolean supports(Method method) {
        Class<?> declaringClass = method.getDeclaringClass();

        // Support if method has @DynamicConfig
        if (method.isAnnotationPresent(DynamicConfig.class)) {
            return true;
        }

        // Support if class has @DynamicConfig
        if (declaringClass.isAnnotationPresent(DynamicConfig.class)) {
            return true;
        }

        // Support if any field has @DynamicConfig
        return Arrays.stream(declaringClass.getDeclaredFields())
                .anyMatch(field -> field.isAnnotationPresent(DynamicConfig.class));
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public int getOrder() {
        return 10;
    }

    /**
     * Gets the configuration service used by this aspect.
     *
     * @return the configuration service
     */
    public ConfigurationService getConfigurationService() {
        return configurationService;
    }

    /**
     * Clears all caches maintained by this aspect.
     */
    public void clearCaches() {
        fieldAnnotationCache.clear();
        callbackCache.clear();
        lastValueCache.clear();
    }

    private void injectFieldConfigurations(Object target, Class<?> targetClass) {
        Map<Field, DynamicConfig> fieldConfigs = getFieldAnnotations(targetClass);

        for (Map.Entry<Field, DynamicConfig> entry : fieldConfigs.entrySet()) {
            Field field = entry.getKey();
            DynamicConfig config = entry.getValue();
            try {
                injectFieldValue(target, field, config);
            } catch (IllegalStateException exception) {
                // Propagate required configuration exceptions
                throw exception;
            } catch (Exception exception) {
                Log.warn("Failed to inject configuration for field %s.%s: %s",
                        targetClass.getSimpleName(), field.getName(), exception.getMessage());
            }
        }
    }

    private Map<Field, DynamicConfig> getFieldAnnotations(Class<?> targetClass) {
        return fieldAnnotationCache.computeIfAbsent(targetClass, clazz -> {
            Map<Field, DynamicConfig> result = new ConcurrentHashMap<>();

            for (Field field : clazz.getDeclaredFields()) {
                DynamicConfig config = field.getAnnotation(DynamicConfig.class);
                if (config != null) {
                    field.setAccessible(true);
                    result.put(field, config);

                    // Register metadata with configuration service
                    registerFieldConfiguration(field, config);
                }
            }

            return result;
        });
    }

    private void registerFieldConfiguration(Field field, DynamicConfig config) {
        String key = resolveConfigKey(config, field.getName());

        ConfigurationService.ConfigurationMetadata metadata = new ConfigurationService.ConfigurationMetadata(
                key,
                config.description().isEmpty() ? "Configuration for " + field.getName() : config.description(),
                config.defaultValue(),
                config.source(),
                config.required(),
                field.getType().getSimpleName(),
                config.validation(),
                config.version(),
                config.refreshInterval(),
                config.cache(),
                config.watch()
        );

        configurationService.registerMetadata(metadata);

        // Register change listener if callback is specified
        if (config.watch() && !config.onChangeCallback().isEmpty()) {
            configurationService.addChangeListener(key, (changedKey, oldValue, newValue) -> {
                if (changedKey.equals(key)) {
                    invokeChangeCallback(field.getDeclaringClass(), config.onChangeCallback(), oldValue, newValue);
                }
            });
        }
    }

    private void registerMethodConfiguration(Method method, DynamicConfig config) {
        String key = resolveConfigKey(config, method.getName());

        ConfigurationService.ConfigurationMetadata metadata = new ConfigurationService.ConfigurationMetadata(
                key,
                config.description().isEmpty() ? "Configuration for " + method.getName() : config.description(),
                config.defaultValue(),
                config.source(),
                config.required(),
                "String",
                config.validation(),
                config.version(),
                config.refreshInterval(),
                config.cache(),
                config.watch()
        );

        configurationService.registerMetadata(metadata);
    }

    private void injectFieldValue(Object target, Field field, DynamicConfig config) throws IllegalAccessException {
        String key = resolveConfigKey(config, field.getName());
        Class<?> fieldType = field.getType();

        Object value = configurationService.get(key, fieldType);
        if (value == null && !config.defaultValue().isEmpty()) {
            value = convertDefaultValue(config.defaultValue(), fieldType);
        }

        if (value == null && config.required()) {
            throw new IllegalStateException("Required configuration not found: " + key);
        }

        if (value != null) {
            Object currentValue = field.get(target);

            // Only inject if value has changed or first time
            if (!value.equals(currentValue)) {
                String cacheKey = System.identityHashCode(target) + ":" + key;
                Object lastValue = lastValueCache.put(cacheKey, value);

                field.set(target, value);

                // Invoke change callback if value changed and callback is specified
                if (lastValue != null && !value.equals(lastValue) && !config.onChangeCallback().isEmpty()) {
                    invokeChangeCallback(target, config.onChangeCallback(), lastValue, value);
                }
            }
        }
    }

    private String resolveConfigKey(DynamicConfig config, String defaultName) {
        return config.key().isEmpty() ? defaultName : config.key();
    }

    private Object convertDefaultValue(String defaultValue, Class<?> targetType) {
        if (targetType == String.class) {
            return defaultValue;
        } else if (targetType == Integer.class || targetType == int.class) {
            return Integer.parseInt(defaultValue);
        } else if (targetType == Long.class || targetType == long.class) {
            return Long.parseLong(defaultValue);
        } else if (targetType == Double.class || targetType == double.class) {
            return Double.parseDouble(defaultValue);
        } else if (targetType == Boolean.class || targetType == boolean.class) {
            return Boolean.parseBoolean(defaultValue);
        } else if (targetType == Float.class || targetType == float.class) {
            return Float.parseFloat(defaultValue);
        }

        return defaultValue;
    }

    private void invokeChangeCallback(Object target, String callbackName, Object oldValue, Object newValue) {
        try {
            Class<?> targetClass = target instanceof Class<?> ? (Class<?>) target : target.getClass();
            String cacheKey = targetClass.getName() + "#" + callbackName;

            Method callback = callbackCache.computeIfAbsent(cacheKey, k -> {
                try {
                    return targetClass.getDeclaredMethod(callbackName, Object.class, Object.class);
                } catch (NoSuchMethodException exception) {
                    Log.warn("Change callback method not found: %s in class %s", callbackName, targetClass.getName());
                    return null;
                }
            });

            if (callback != null && !(target instanceof Class<?>)) {
                callback.setAccessible(true);
                callback.invoke(target, oldValue, newValue);
            }
        } catch (Exception exception) {
            Log.warn("Failed to invoke change callback %s: %s", callbackName, exception.getMessage());
        }
    }

}
