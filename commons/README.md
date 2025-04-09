### commons

This is a module full of good stuff that is useful in every way, so it's a bit of a mixed bag, but I'll always update
this document when there's new content.

### usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))

    // commons module
    compileOnly(files("libs/commons-1.0-SNAPSHOT.jar"))
}
```

### [VarHandleReflectionInjector](src/main/java/net/legacy/library/commons/injector/VarHandleReflectionInjector.java)

This is an `injector`, and its main use is to be used
with [VarHandleAutoInjection](src/main/java/net/legacy/library/commons/injector/annotation/VarHandleAutoInjection.java).

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

### [Task](src/main/java/net/legacy/library/commons/task)

The [TaskInterface](src/main/java/net/legacy/library/commons/task/TaskInterface.java)
simplifies task scheduling by providing convenience methods with consistent naming and argument order with the Fairy
Framework [MCScheduler](https://docs.fairyproject.io/core/minecraft/scheduler).

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface<ScheduledTask<?>> taskInterface = new TaskInterface<>() {
            @Override
            public ScheduledTask<?> start() {
                // This is a simple example of a task that prints "Hello, world!" every second.
                return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 20);
            }
        };

        // start the task
        taskInterface.start();
    }
}
```

This module also supports various scheduling operations for virtual threads, which is particularly useful in network
communication, I/O, and other areas, leading to higher performance.

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface<ScheduledFuture<?>> taskInterface = new TaskInterface<>() {
            @Override
            public ScheduledFuture<?> start() {
                // This is a simple example using virtual threads that prints "Hello, world!" every second.
                return scheduleAtFixedRateWithVirtualThread(() -> System.out.println("Hello, world!"), 0, 1, TimeUnit.SECONDS);
            }
        };
    }
}
```

It also
provides [TaskAutoStartAnnotation](src/main/java/net/legacy/library/commons/task/annotation/TaskAutoStartAnnotation.java)
to handle some tasks that need to be automatically started at a specific time. When there are many tasks to start,
annotation automation will help us avoid manually managing the creation and calling of these instances, thereby
simplifying the code. For more methods and detailed information, please refer to the JavaDoc.

```java

@TaskAutoStartAnnotation(isFromFairyIoC = false)
public class Example implements TaskInterface<ScheduledTask<?>> {
    @Override
    public ScheduledTask<?> start() {
        // This is a simple example of a task that prints "Hello, world!" every second.
        return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 20);
    }
}
```

As for how to make annotation processors work on your own plugins, please see the [annotation](../annotation/README.md)
module.

### [GsonUtil](src/main/java/net/legacy/library/commons/util/GsonUtil.java)

`GsonUtil` provides a thread-safe way to manage and customize a shared `Gson` instance. It allows for consistent `Gson`
configuration across your application, preventing scattered and potentially conflicting settings.

To prevent dependency conflicts, you should first import `fairy-lib-plugin` as a dependency and use the relocated
`Gson`, the package should be `io.fairyproject.libs.gson`. No need to manually import dependencies and relocate.

```java
public class Example {
    public static void main(String[] args) {
        // Pretty-print JSON output
        GsonUtil.customizeGsonBuilder(builder -> builder.setPrettyPrinting());

        // Add a custom type adapter
        GsonUtil.customizeGsonBuilder(builder -> builder.registerTypeAdapter(MyClass.class, new MyClassTypeAdapter()));

        // Get the shared Gson instance for use
        Gson gson = GsonUtil.getGson();
    }
}
```

We have a `TypeAdapterRegister` annotation in the `player` module, which can be used to simplify the registration of
`Type Adapter`.
