### commons

This is a module full of good stuff that is useful in every way, so it's a bit of a mixed bag, but I'll always update
this document when there's new content.

### usage

```kotlin
// Dependencies
dependencies {
    // commons module
    compileOnly("net.legacy.library:commons:1.0-SNAPSHOT")
}
```

### [VarHandleReflectionInjector](src/main/java/me/qwqdev/library/commons/injector/VarHandleReflectionInjector.java)

This is an `injector`, and its main use is to be used
with [VarHandleAutoInjection](src/main/java/me/qwqdev/library/commons/injector/annotation/VarHandleAutoInjection.java).

Yes, just like its name, we don't have to write a bunch of ugly code to assign `VarHandle`, let it all disappear, Amen.

```java
public class Example {
    public static void main(String[] args) {
        Test test = new Test();

        // we can use TField_HANDLE
        Test.TField_HANDLE.set(test, 2);

        // prints 2
        System.out.println(test.getTField());
    }
}
```

```java

@Getter
@Setter
public class Test {
    private volatile int tField = 100;

    @VarHandleAutoInjection(fieldName = "tField")
    public static VarHandle TField_HANDLE;

    static {
        /*
         * This class is a singleton and is managed by Fairy IoC.
         * Of course, it is also allowed to create it directly using the factory or directly creating it.
         * This is not so strict.
         */
        InjectorFactory.createVarHandleReflectionInjector().inject(Test.class);
    }
}
```

### [Task](src/main/java/me/qwqdev/library/commons/task)

The [TaskInterface](src/main/java/me/qwqdev/library/commons/task/TaskInterface.java)
simplifies task scheduling by providing convenience methods with consistent naming and argument order with the Fairy Framework [MCScheduler](https://docs.fairyproject.io/core/minecraft/scheduler).

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface taskInterface = new TaskInterface() {
            @Override
            public ScheduledTask<?> start() {
                // This is a simple example of a task that prints "Hello, world!" every second.
                return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 1000);
            }
        };

        // start the task
        taskInterface.start();
    }
}
```

It also provides [TaskAutoStartAnnotation](src/main/java/me/qwqdev/library/commons/task/annotation/TaskAutoStartAnnotation.java) to handle some tasks that need to be automatically started at a specific time. When there are many tasks to start, annotation automation will help us avoid manually managing the creation and calling of these instances, thereby simplifying the code.

```java
@TaskAutoStartAnnotation(isFromFairyIoC = false)
public class Example implements TaskInterface {
    @Override
    public ScheduledTask<?> start() {
        // This is a simple example of a task that prints "Hello, world!" every second.
        return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 1000);
    }
}
```

As for how to make annotation processors work on your own plugins, please see the [annotation](../annotation/README.md) module.