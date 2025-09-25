package net.legacy.library.aop.test;

import net.legacy.library.aop.config.AOPModuleConfiguration;
import net.legacy.library.aop.model.AspectContext;
import net.legacy.library.aop.service.ClassLoaderIsolationService;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;

/**
 * Tests the ClassLoader isolation behaviour for interceptors.
 */
@ModuleTest(
        testName = "aop-classloader-isolation-test",
        description = "Validates strict and relaxed ClassLoader isolation policies",
        tags = {"aop", "classloader", "isolation"},
        priority = 2
)
public final class ClassLoaderIsolationTest {

    private ClassLoaderIsolationTest() {
    }

    public static boolean testStrictIsolationAllowsSameLoader() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            isolationService.setModuleConfiguration(AOPModuleConfiguration.enableAll());

            InvocationScenario scenario = createInvocationScenario(new IsolatedClassLoader(ClassLoader.getSystemClassLoader()));
            ClassLoader aspectLoader = scenario.target().getClass().getClassLoader();

            boolean allowed = isolationService.shouldApplyAspect(scenario.context(), aspectLoader);
            TestLogger.logInfo("aop", "Strict isolation same loader: allowed=%s", allowed);
            return allowed;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Strict isolation same loader test failed: %s", exception.getMessage());
            return false;
        }
    }

    public static boolean testStrictIsolationBlocksUnrelatedLoader() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            isolationService.setModuleConfiguration(AOPModuleConfiguration.enableAll());

            InvocationScenario scenario = createInvocationScenario(new IsolatedClassLoader(ClassLoader.getSystemClassLoader()));
            ClassLoader unrelatedLoader = new IsolatedClassLoader(ClassLoader.getSystemClassLoader());

            boolean allowed = isolationService.shouldApplyAspect(scenario.context(), unrelatedLoader);
            TestLogger.logInfo("aop", "Strict isolation unrelated loader: allowed=%s", allowed);
            return !allowed;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Strict isolation unrelated loader test failed: %s", exception.getMessage());
            return false;
        }
    }

    public static boolean testRelaxedIsolationAllowsUnrelatedLoader() {
        try {
            ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
            AOPModuleConfiguration relaxedConfig = AOPModuleConfiguration.enableAll()
                    .toBuilder()
                    .relaxedClassLoaderEnabled(true)
                    .build();
            isolationService.setModuleConfiguration(relaxedConfig);

            InvocationScenario scenario = createInvocationScenario(new IsolatedClassLoader(ClassLoader.getSystemClassLoader()));
            ClassLoader unrelatedLoader = new IsolatedClassLoader(ClassLoader.getSystemClassLoader());

            boolean allowed = isolationService.shouldApplyAspect(scenario.context(), unrelatedLoader);
            TestLogger.logInfo("aop", "Relaxed isolation unrelated loader: allowed=%s", allowed);
            return allowed;
        } catch (Exception exception) {
            TestLogger.logFailure("aop", "Relaxed isolation unrelated loader test failed: %s", exception.getMessage());
            return false;
        }
    }

    private static InvocationScenario createInvocationScenario(ClassLoader loader) throws Exception {
        InvocationHandler handler = new ScenarioInvocationHandler();
        Runnable proxyInstance = (Runnable) Proxy.newProxyInstance(loader, new Class[]{Runnable.class}, handler);
        Method method = Runnable.class.getMethod("run");
        AspectContext context = AspectContext.create(proxyInstance, method, null);
        return new InvocationScenario(proxyInstance, context);
    }

    private static final class InvocationScenario {

        private final Object target;
        private final AspectContext context;

        private InvocationScenario(Object target, AspectContext context) {
            this.target = target;
            this.context = context;
        }

        private Object target() {
            return target;
        }

        private AspectContext context() {
            return context;
        }

    }

    private static final class ScenarioInvocationHandler implements InvocationHandler {

        @Override
        public Object invoke(Object proxy, Method method, Object[] args) {
            return null;
        }

        @Override
        public int hashCode() {
            return System.identityHashCode(this);
        }

        @Override
        public boolean equals(Object other) {
            return this == other;
        }

        @Override
        public String toString() {
            return "ScenarioInvocationHandler";
        }

    }

    private static class IsolatedClassLoader extends ClassLoader {

        IsolatedClassLoader(ClassLoader parent) {
            super(parent);
        }

    }

}
