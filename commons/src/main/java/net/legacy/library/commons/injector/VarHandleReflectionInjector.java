package net.legacy.library.commons.injector;

import io.fairyproject.container.InjectableComponent;
import net.legacy.library.commons.factory.InjectorFactory;
import net.legacy.library.commons.injector.annotation.VarHandleAutoInjection;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.lang.reflect.Field;
import java.lang.reflect.Method;

/**
 * Injector class for injecting {@link VarHandle} instances into static fields annotated with {@link VarHandleAutoInjection}.
 * This class automatically injects {@link VarHandle} instances into the static fields of a class based on the annotations
 * present on those fields.
 *
 * <p>This class is a singleton class managed by {@code Fairy IoC}.. It is best to use dependency injection,
 * but using a factory class or creating it directly is also allowed.
 *
 * <p>The injection is done via reflection, which allows this class to work without explicitly hardcoding the
 * dependencies. However, this approach introduces some overhead and may not be suitable for high-performance use cases.
 *
 * <p>The injection process works in two ways:
 * <ul>
 *     <li><b>Default:</b> Uses {@link MethodHandles#lookup()} to locate the {@link VarHandle} for the specified static field.
 *     This is the most common use case when static methods aren't needed.</li>
 *     <li><b>Static Method:</b> If specified in the {@link VarHandleAutoInjection} annotation, a static method can be used
 *     to retrieve the {@link VarHandle} for the field. This allows for more customized handling of the {@link VarHandle}
 *     injection.</li>
 * </ul>
 *
 * <p>If using a static method for injection, the class containing the static method must be loadable by the current
 * thread's context {@link ClassLoader}. The method should be accessible and match the required signature for retrieving
 * the {@link VarHandle}.
 *
 * <p>Due to the use of reflection, the fields will be made accessible even if they are private, ensuring that injection
 * can occur regardless of visibility modifiers.
 *
 * <p>This class implements the {@link StaticInjectorInterface} interface, which defines the contract for injecting
 * dependencies (in this case, {@link VarHandle} instances) into static fields of a class.
 *
 * @author qwq-dev
 * @see InjectorFactory
 * @since 2024-12-23 16:15
 */
@InjectableComponent
public class VarHandleReflectionInjector implements StaticInjectorInterface {
    /**
     * {@inheritDoc}
     *
     * <p>Injects {@link VarHandle} instances into static fields of the given class that are annotated with
     * {@link VarHandleAutoInjection}. This method uses reflection to find the appropriate {@link VarHandle}
     * for each annotated field and assigns it to the field.
     *
     * <p>The method performs the injection by either using the default method of
     * {@link MethodHandles#privateLookupIn(Class, MethodHandles.Lookup)} to
     * locate the {@link VarHandle} for the specified static field or by using a static method if defined in the
     * annotation {@link VarHandleAutoInjection}.
     *
     * <p>The specified static method must contain two parameters: {@link String} and {@link Class}
     *
     * <p>If a static method is specified in the annotation, it will be called to retrieve the {@link VarHandle}
     * for the field. The class containing the static method must be loadable by the current thread's context
     * {@link ClassLoader}.
     *
     * @param clazz the class into which {@link VarHandle} instances will be injected into its static fields
     * @throws IllegalStateException if the injection fails due to reflection issues or invalid annotations
     */
    @Override
    public void inject(Class<?> clazz) {
        Field[] fields = clazz.getDeclaredFields();

        for (Field field : fields) {
            VarHandleAutoInjection annotation = field.getAnnotation(VarHandleAutoInjection.class);

            if (annotation == null) {
                continue;
            }

            String targetFieldName = annotation.fieldName();
            String staticMethodName = annotation.staticMethodName();
            String staticMethodPackage = annotation.staticMethodPackage();
            boolean useStaticMethod = !staticMethodName.isEmpty() && !staticMethodPackage.isEmpty();

            try {
                VarHandle varHandle;

                if (useStaticMethod) {
                    Class<?> staticClass = Class.forName(staticMethodPackage);
                    Method method = staticClass.getDeclaredMethod(staticMethodName, String.class, Class.class);
                    method.setAccessible(true);
                    varHandle = (VarHandle) method.invoke(null, targetFieldName, field.getType());
                } else {
                    varHandle = MethodHandles.privateLookupIn(clazz, MethodHandles.lookup())
                            .unreflectVarHandle(clazz.getDeclaredField(targetFieldName));
                }

                field.setAccessible(true);
                field.set(null, varHandle);
            } catch (Exception exception) {
                throw new IllegalStateException("Failed to inject VarHandle for field: " + field.getName(), exception);
            }
        }
    }
}
