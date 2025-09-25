package net.legacy.library.aop.config;

import lombok.AccessLevel;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Getter;

/**
 * Aggregates configuration flags for the AOP module, allowing feature toggles at runtime.
 * 
 * @author qwq-dev
 * @version 1.0
 * @since 2025-06-19 17:41
 */
@Getter
@Builder(toBuilder = true)
@AllArgsConstructor(access = AccessLevel.PRIVATE)
public class AOPModuleConfiguration {

    private final boolean tracingEnabled;
    private final boolean retryEnabled;
    private final boolean securityEnabled;
    private final boolean distributedTransactionEnabled;
    private final boolean asyncSafeEnabled;
    private final boolean monitoringEnabled;
    private final boolean loggingEnabled;
    private final boolean faultToleranceEnabled;
    private final boolean debugMode;
    private final boolean relaxedClassLoaderEnabled;

    public static AOPModuleConfiguration enableAll() {
        return AOPModuleConfiguration.builder()
                .tracingEnabled(true)
                .retryEnabled(true)
                .securityEnabled(true)
                .distributedTransactionEnabled(true)
                .asyncSafeEnabled(true)
                .monitoringEnabled(true)
                .loggingEnabled(true)
                .faultToleranceEnabled(true)
                .debugMode(false)
                .relaxedClassLoaderEnabled(false)
                .build();
    }

}
