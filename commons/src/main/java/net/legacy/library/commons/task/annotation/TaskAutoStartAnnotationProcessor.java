package net.legacy.library.commons.task.annotation;

import io.fairyproject.container.Containers;
import io.fairyproject.log.Log;
import net.legacy.library.annotation.service.AnnotationProcessor;
import net.legacy.library.annotation.service.CustomAnnotationProcessor;
import net.legacy.library.commons.task.TaskInterface;

/**
 * The {@link TaskAutoStartAnnotationProcessor} is responsible for processing
 * all classes annotated with {@link TaskAutoStartAnnotation}. Upon processing, it attempts
 * to retrieve an instance of the annotated class from a dependency container (via {@link Containers#get(Class)}),
 * or read the {@link TaskAutoStartAnnotation#isFromFairyIoC()} property from the annotation and
 * create the instance directly using reflection, then calls the {@link TaskInterface#start()} method on that instance
 * (if it implements {@link TaskInterface}).
 *
 * <p>When {@link TaskAutoStartAnnotation#isFromFairyIoC()} is false, it must contain a no-argument constructor.
 * If we do not want it to be managed by {@code Fairy IoC} but still need to inject dependencies,
 * we need to use the {@link io.fairyproject.container.Autowired} annotation.
 *
 * @author qwq-dev
 * @since 2024-12-24 11:47
 */
@AnnotationProcessor(TaskAutoStartAnnotation.class)
public class TaskAutoStartAnnotationProcessor implements CustomAnnotationProcessor {
    /**
     * Processes a class annotated with {@link TaskAutoStartAnnotation}.
     *
     * <p>Casts the retrieved object from the container to {@link TaskInterface},
     * then calls its {@link TaskInterface#start()} method to initiate the task.
     *
     * @param clazz the annotated class to be processed
     */
    @Override
    public void process(Class<?> clazz) throws Exception {
        TaskAutoStartAnnotation taskAutoStartAnnotation =
                clazz.getAnnotation(TaskAutoStartAnnotation.class);

        if (taskAutoStartAnnotation == null) {
            return;
        }

        TaskInterface taskInterface;

        if (taskAutoStartAnnotation.isFromFairyIoC()) {
            taskInterface = ((TaskInterface) Containers.get(clazz));
        } else {
            taskInterface = (TaskInterface) clazz.getDeclaredConstructor().newInstance();
        }

        taskInterface.start();
        Log.info("[AnnotationProcessor] %s task started.", clazz.getName());
    }

    /**
     * Handles exceptions that occur during the annotation processing phase.
     *
     * @param clazz     the annotated class where the exception occurred
     * @param exception the exception thrown
     */
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("An exception occurred while processing the task", exception);
    }
}
