package net.legacy.library.aop;

import io.fairyproject.FairyLaunch;
import io.fairyproject.container.Autowired;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.container.PreDestroy;
import io.fairyproject.plugin.Plugin;
import lombok.Getter;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;
import net.legacy.library.aop.service.AOPService;
import net.legacy.library.aop.test.AOPTestRunner;
import net.legacy.library.foundation.test.TestExecutionUtil;

import java.util.List;

/**
 * The AOP module launcher for the Legacy Lands Library.
 *
 * <p>This launcher serves as the main entry point for the AOP (Aspect-Oriented Programming) framework,
 * providing comprehensive initialization and lifecycle management with ClassLoader-scoped isolation.
 * The framework offers cross-cutting concerns such as performance monitoring, thread safety,
 * logging, and exception handling specifically designed for Minecraft plugin environments.
 *
 * @author qwq-dev
 * @version 1.0
 * @see AOPService
 * @see AnnotationProcessingServiceInterface
 * @see AOPTestRunner
 * @since 2025-06-19 17:41
 */
@Getter
@FairyLaunch
@InjectableComponent
public class AOPLauncher extends Plugin {
    /**
     * Debug mode flag. When set to true, enables comprehensive testing
     * of the annotation processing framework during plugin startup.
     */
    private static final boolean DEBUG = false;

    @Autowired
    public AOPService aopService;

    @Autowired
    @SuppressWarnings("unused")
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of("net.legacy.library.aop");

        annotationProcessingService.processAnnotations(
                basePackages, false,
                this.getClassLoader(), AOPLauncher.class.getClassLoader()
        );

        // Run tests in debug mode (only once)
        if (DEBUG) {
            runDebugTests();
        }
    }

    /**
     * Performs cleanup and shutdown operations when the plugin is being disabled.
     */
    @PreDestroy
    public void onPluginDisable() {
        // Cleanup current ClassLoader resources
        ClassLoader currentLoader = getClass().getClassLoader();
        aopService.cleanupClassLoader(currentLoader);

        // Shutdown AOP service
        aopService.shutdown();
    }

    /**
     * Runs comprehensive debug tests for the AOP framework.
     */
    private void runDebugTests() {
        TestExecutionUtil.executeModuleTestRunner("aop", AOPTestRunner.create());
    }
}
