package net.legacy.library.commons.injector;

/**
 * Interface for object injectors.
 *
 * @author qwq-dev
 * @since 2024-12-23 16:44
 */
public interface ObjectInjectorInterface {
    /**
     * Process the given object.
     *
     * @param object given object
     */
    void inject(Object object);
}
