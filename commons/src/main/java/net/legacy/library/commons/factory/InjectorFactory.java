package net.legacy.library.commons.factory;

import lombok.experimental.UtilityClass;
import net.legacy.library.commons.injector.StaticInjectorInterface;
import net.legacy.library.commons.injector.VarHandleReflectionInjector;

/**
 * Factory for creating injector instances.
 *
 * @author qwq-dev
 * @since 2024-12-23 18:38
 */
@UtilityClass
public final class InjectorFactory {
    /**
     * Create a {@link VarHandleReflectionInjector} instance.
     *
     * @return a {@link VarHandleReflectionInjector} instance
     * @see StaticInjectorInterface
     * @see VarHandleReflectionInjector
     */
    public static StaticInjectorInterface createVarHandleReflectionInjector() {
        return new VarHandleReflectionInjector();
    }
}
