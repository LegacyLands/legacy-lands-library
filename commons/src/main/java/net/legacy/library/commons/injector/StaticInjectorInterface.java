package net.legacy.library.commons.injector;

/**
 * Interface for static injectors.
 *
 * @author qwq-dev
 * @since 2024-12-23 17:07
 */
public interface StaticInjectorInterface {

    /**
     * Process the given class.
     *
     * @param clazz given class
     */
    void inject(Class<?> clazz);

}
