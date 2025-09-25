package net.legacy.library.aop.annotation;

import java.lang.annotation.ElementType;
import java.lang.annotation.Retention;
import java.lang.annotation.RetentionPolicy;
import java.lang.annotation.Target;

/**
 * Annotation for enabling dynamic configuration on methods or classes.
 *
 * <p>This annotation provides dynamic configuration capabilities that allow
 * runtime modification of behavior without requiring application restart.
 * It supports configuration hot-reloading, validation, and versioning.
 *
 * @author qwq-dev
 * @version 2.0
 * @since 2025-06-20 10:00
 */
@Target({ElementType.TYPE, ElementType.METHOD, ElementType.FIELD})
@Retention(RetentionPolicy.RUNTIME)
public @interface DynamicConfig {

    /**
     * The configuration key or path.
     *
     * <p>Supports dot notation for nested properties (e.g., "service.timeout").
     * If not specified, the field name or method name will be used.
     *
     * @return the configuration key
     */
    String key() default "";

    /**
     * The default value for the configuration.
     *
     * <p>Used when the configuration is not found in any source.
     *
     * @return the default value
     */
    String defaultValue() default "";

    /**
     * The configuration source to use.
     *
     * <p>If not specified, all available sources will be checked in order.
     *
     * @return the configuration source
     */
    String source() default "";

    /**
     * Whether the configuration is required.
     *
     * <p>When true, the application will fail to start if the configuration is not found.
     *
     * @return true if the configuration is required
     */
    boolean required() default false;

    /**
     * The configuration refresh interval in milliseconds.
     *
     * <p>How often to check for configuration updates. 0 means no automatic refresh.
     *
     * @return the refresh interval
     */
    long refreshInterval() default 0;

    /**
     * Whether to cache the configuration value.
     *
     * <p>When true, the configuration value is cached for better performance.
     *
     * @return true to cache the configuration
     */
    boolean cache() default true;

    /**
     * The environment profiles for which this configuration applies.
     *
     * <p>If empty, applies to all environments.
     *
     * @return array of environment profiles
     */
    String[] profiles() default {};

    /**
     * The validation rules for the configuration.
     *
     * <p>Supports validation expressions like "min=1", "max=100", "regex=.*".
     *
     * @return array of validation rules
     */
    String[] validation() default {};

    /**
     * The configuration version.
     *
     * <p>Used for configuration versioning and rollback capabilities.
     *
     * @return the configuration version
     */
    String version() default "";

    /**
     * Whether to watch for configuration changes.
     *
     * <p>When true, changes to the configuration will trigger events.
     *
     * @return true to watch for changes
     */
    boolean watch() default false;

    /**
     * The callback method to invoke when configuration changes.
     *
     * <p>The method should accept the old value and new value as parameters.
     *
     * @return the callback method name
     */
    String onChangeCallback() default "";

    /**
     * The configuration description.
     *
     * <p>Used for documentation and metadata purposes.
     *
     * @return the configuration description
     */
    String description() default "";

}