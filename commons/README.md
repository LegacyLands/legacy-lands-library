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

### [RandomGenerator](src/main/java/net/legacy/library/commons/util/random/RandomGenerator.java)

`RandomGenerator` originated from the author `qwq-dev (2000000)`'s deprecated project `Advanced Wish`, but this tool is
still very useful in scenarios involving random selection based on weights.

When you need to randomly select an object from a group based on their respective weights (probabilities),
`RandomGenerator`
is a convenient choice. It supports dynamically adding objects and their weights, and provides multiple random
algorithms to meet the needs of different scenarios.

- `ThreadLocalRandom` (default): Standard pseudo-random number generator, high performance, suitable for most general
  scenarios.
- `SecureRandom`: Provides a higher security random number generator, suitable for scenarios with strict randomness
  requirements.
- **Monte Carlo Method (`getResultWithMonteCarlo`)**: Approximates the probability distribution by simulating a large
  number of random events. Theoretically fairer, but may be less efficient with large datasets.
- **Shuffle Method (`getResultWithShuffle`)**: Randomizes by shuffling the order of the object list. Good randomness,
  but may not strictly adhere to the set weights.
- **Gaussian Method (`getResultWithGaussian`)**: Generates random numbers following a Gaussian (normal) distribution for
  selection. This can make the selection result tend towards areas with concentrated weights, rather than simple linear
  probability.

```java
public class Main {
    public static void main(String[] args) {
        // Create a RandomGenerator instance and initialize objects with their weights
        // "Apple" weight 30, "Banana" weight 50, "Orange" weight 20
        RandomGenerator<String> randomGenerator = new RandomGenerator<>(
                "Apple", 30,
                "Banana", 50,
                "Orange", 20
        );

        // Perform random selection using the default pseudo-random number generator
        String result = randomGenerator.getResult();
        System.out.println("Result using default pseudo-random generator: " + result);

        // Perform random selection using a more secure random number generator
        String secureResult = randomGenerator.getResultWithSecureRandom();
        System.out.println("Result using secure random generator: " + secureResult);

        // Perform random selection using the Monte Carlo method
        String monteCarloResult = randomGenerator.getResultWithMonteCarlo();
        System.out.println("Result using Monte Carlo method: " + monteCarloResult);

        // Perform random selection using the Shuffle method
        String shuffleResult = randomGenerator.getResultWithShuffle();
        System.out.println("Result using Shuffle method: " + shuffleResult);

        // Perform random selection using the Gaussian method
        String gaussianResult = randomGenerator.getResultWithGaussian();
        System.out.println("Result using Gaussian method: " + gaussianResult);
    }
}
```

Additionally, the sum of weights does not need to be 100; `RandomGenerator` automatically calculates probabilities based
on the weight proportions. Choosing the appropriate random algorithm depends on the specific requirements of your
application scenario regarding performance, security, and random distribution characteristics.
