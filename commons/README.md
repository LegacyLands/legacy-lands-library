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
- **Mersenne Twister (`getResultWithMersenneTwister`)**: A high-quality pseudo-random number generator known for its
  very long period and good statistical properties.
- **XORShift (`getResultWithXORShift`)**: A class of fast and simple pseudo-random number generators. Generally good,
  but might not pass all stringent statistical tests.
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

### [ValidationUtil](src/main/java/net/legacy/library/commons/util/ValidationUtil.java)

`ValidationUtil` is a comprehensive static utility class designed to simplify various common input validation tasks in
Java applications. It provides a concise and easy-to-use API, supporting multiple validation approaches and covering a
wide range of data types.

**Core Features:**

* **Multiple Validation Modes**: Offers methods that return boolean values (e.g., `isNull`, `isEmpty`) and methods that
  throw exceptions upon validation failure (e.g., `requireNonNull`, `requireNotEmpty`).
* **Exception Flexibility**: Supports throwing standard Java exceptions (`IllegalArgumentException`,
  `NullPointerException`, `IndexOutOfBoundsException`) or custom exceptions provided by the caller (via `Supplier`).
* **Broad Type Coverage**: Supports validation for objects, strings, collections, maps, arrays, numeric types (
  int/long), and indices.
* **Ease of Use**: All methods are static, allowing for direct invocation.

**Detailed Method Descriptions and Examples:**

#### Object Validation

Used to check if an object is `null` and if two objects are equal.

* `isNull(Object value)`: Checks if the object is `null`.
* `notNull(Object value)`: Checks if the object is not `null`.
* `requireNonNull(Object value, String message)`: Ensures the object is not `null`, throws `NullPointerException`
  otherwise.
* `requireNonNull(T value, Supplier<? extends X> exceptionSupplier)`: Ensures the object is not `null`, throws a custom
  exception otherwise.
* `equals(Object object1, Object object2)`: Checks if two objects are equal.
* `notEquals(Object object1, Object object2)`: Checks if two objects are not equal.
* `requireEquals(Object object1, Object object2, String message)`: Ensures two objects are equal, throws
  `IllegalArgumentException` otherwise.
* `requireEquals(Object object1, Object object2, Supplier<? extends X> exceptionSupplier)`: Ensures two objects are
  equal, throws a custom exception otherwise.

```java
public class ObjectValidationExample {
    public static void main(String[] args) {
        Object obj = null;
        Object obj2 = "Hello";

        if (ValidationUtil.isNull(obj)) {
            System.out.println("obj is null"); // Output: obj is null
        }

        ValidationUtil.requireNonNull(obj2, "obj2 cannot be null");

        try {
            ValidationUtil.requireNonNull(obj, () -> new IllegalStateException("Object must not be null here."));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // Output: Object must not be null here.
        }

        String s1 = "test";
        String s2 = new String("test"); // Same content, different object
        String s3 = "different";

        // Compare content using Objects.equals
        if (ValidationUtil.equals(s1, s2)) {
            System.out.println("s1 and s2 are equal (content-wise)"); // Output: s1 and s2 are equal (content-wise)
        }

        ValidationUtil.requireEquals(s1, s2, "s1 and s2 must be equal");

        try {
            ValidationUtil.requireEquals(s1, s3, () -> new RuntimeException("Strings must match"));
        } catch (RuntimeException exception) {
            System.err.println(exception.getMessage()); // Output: Strings must match
        }
    }
}
```

#### String Validation

Provides checks for whether a string is empty, blank, within a specific length, or matches a regular expression.

* `isEmpty(String inputString)`: Checks if the string is `null` or empty `""`.
* `isBlank(String inputString)`: Checks if the string is `null`, empty `""`, or contains only whitespace characters.
* `notEmpty(String inputString)`: Checks if the string is not `null` and not empty `""`.
* `notBlank(String inputString)`: Checks if the string is not `null`, not empty `""`, and contains non-whitespace
  characters.
* `requireNotEmpty(String inputString, String message)`: Ensures the string is not empty, throws
  `IllegalArgumentException` otherwise.
* `requireNotBlank(String inputString, String message)`: Ensures the string is not blank, throws
  `IllegalArgumentException` otherwise.
* `requireNotEmpty(String inputString, Supplier<? extends X> exceptionSupplier)`: Ensures the string is not empty,
  throws a custom exception otherwise.
* `requireNotBlank(String inputString, Supplier<? extends X> exceptionSupplier)`: Ensures the string is not blank,
  throws a custom exception otherwise.
* `lengthBetween(String inputString, int minLength, int maxLength)`: Checks if the string length is within the range
  `[minLength, maxLength]`.
* `requireLengthBetween(String inputString, int minLength, int maxLength, String message)`: Ensures the string length is
  within the range, throws `IllegalArgumentException` otherwise.
* `requireLengthBetween(String inputString, int minLength, int maxLength, Supplier<? extends X> exceptionSupplier)`:
  Ensures the string length is within the range, throws a custom exception otherwise.
* `matches(String inputString, String regex)` / `matches(String inputString, Pattern pattern)`: Checks if the string
  matches the regular expression.
* `requireMatches(String inputString, String regex, String message)` /
  `requireMatches(String inputString, Pattern pattern, String message)`: Ensures the string matches the regex, throws
  `IllegalArgumentException` otherwise.
* `requireMatches(String inputString, String regex, Supplier<? extends X> exceptionSupplier)` /
  `requireMatches(String inputString, Pattern pattern, Supplier<? extends X> exceptionSupplier)`: Ensures the string
  matches the regex, throws a custom exception otherwise.

```java
public class StringValidationExample {
    public static void main(String[] args) {
        String emptyStr = "";
        String blankStr = "   ";
        String validStr = " example ";
        String numberStr = "12345";
        Pattern numberPattern = Pattern.compile("\\d+");

        if (ValidationUtil.isEmpty(emptyStr)) {
            System.out.println("emptyStr is empty"); // Output: emptyStr is empty
        }
        if (ValidationUtil.isBlank(blankStr)) {
            System.out.println("blankStr is blank"); // Output: blankStr is blank
        }
        if (ValidationUtil.notBlank(validStr)) {
            System.out.println("validStr is not blank"); // Output: validStr is not blank
        }

        ValidationUtil.requireNotBlank(validStr, "String must not be blank");
        ValidationUtil.requireLengthBetween(validStr.trim(), 5, 10, "Trimmed length (example) must be between 5 and 10"); // "example" has length 7
        ValidationUtil.requireMatches(numberStr, numberPattern, "String must contain only digits");

        try {
            ValidationUtil.requireNotEmpty(emptyStr, () -> new NoSuchElementException("String is required."));
        } catch (NoSuchElementException exception) {
            System.err.println(exception.getMessage()); // Output: String is required.
        }
    }
}
```

#### Collection/Map/Array Validation

Used to check if a collection, map, or array is `null` or empty.

* `isEmpty(Collection<?> collection)` / `isEmpty(Map<?, ?> map)` / `isEmpty(T[] array)`: Checks if `null` or empty.
* `notEmpty(Collection<?> collection)` / `notEmpty(Map<?, ?> map)` / `notEmpty(T[] array)`: Checks if not `null` and not
  empty.
* `requireNotEmpty(Collection<?> collection, String message)` / `requireNotEmpty(Map<?, ?> map, String message)` /
  `requireNotEmpty(T[] array, String message)`: Ensures not empty, throws `IllegalArgumentException` otherwise.
*

`requireNotEmpty(Collection<?> collection, Supplier<? extends X> exceptionSupplier)` / `requireNotEmpty(Map<?, ?> map, Supplier<? extends X> exceptionSupplier)` /
`requireNotEmpty(T[] array, Supplier<? extends X> exceptionSupplier)`: Ensures not empty, throws a custom exception
otherwise.

```java
public class CollectionMapArrayValidationExample {
    public static void main(String[] args) {
        List<String> emptyList = new ArrayList<>();
        List<String> list = List.of("a", "b");
        Map<String, Integer> emptyMap = Map.of();
        Map<String, Integer> map = Map.of("one", 1);
        String[] emptyArray = {};
        String[] array = {"x", "y"};

        if (ValidationUtil.isEmpty(emptyList)) {
            System.out.println("emptyList is empty"); // Output: emptyList is empty
        }
        if (ValidationUtil.notEmpty(list)) {
            System.out.println("list is not empty"); // Output: list is not empty
        }
        if (ValidationUtil.isEmpty(emptyMap)) {
            System.out.println("emptyMap is empty"); // Output: emptyMap is empty
        }
        if (ValidationUtil.notEmpty(map)) {
            System.out.println("map is not empty"); // Output: map is not empty
        }
        if (ValidationUtil.isEmpty(emptyArray)) {
            System.out.println("emptyArray is empty"); // Output: emptyArray is empty
        }
        if (ValidationUtil.notEmpty(array)) {
            System.out.println("array is not empty"); // Output: array is not empty
        }

        ValidationUtil.requireNotEmpty(list, "List cannot be empty.");
        ValidationUtil.requireNotEmpty(map, "Map cannot be empty.");
        ValidationUtil.requireNotEmpty(array, "Array cannot be empty.");

        try {
            ValidationUtil.requireNotEmpty(emptyList, () -> new IllegalStateException("Need at least one element in the list"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // Output: Need at least one element in the list
        }

        try {
            ValidationUtil.requireNotEmpty(emptyMap, () -> new IllegalStateException("Need at least one entry in the map"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // Output: Need at least one entry in the map
        }

        try {
            ValidationUtil.requireNotEmpty(emptyArray, () -> new IllegalStateException("Need at least one element in the array"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // Output: Need at least one element in the array
        }
    }
}
```

#### Numeric Validation (int/long)

Compares numeric values or checks if they fall within a specified range.

* `isGreaterThan(int/long value, int/long min)`: Checks `value > min`.
* `isGreaterThanOrEqual(int/long value, int/long min)`: Checks `value >= min`.
* `isLessThan(int/long value, int/long max)`: Checks `value < max`.
* `isLessThanOrEqual(int/long value, int/long max)`: Checks `value <= max`.
* `isBetween(int/long value, int/long min, int/long max)`: Checks `min <= value <= max`.
* `requireGreaterThan(int/long value, int/long min, String message)`: Ensures `value > min`, throws
  `IllegalArgumentException` otherwise.
* `requireBetween(int/long value, int/long min, int/long max, String message)`: Ensures `min <= value <= max`, throws
  `IllegalArgumentException` otherwise.
* `requireGreaterThan(int/long value, int/long min, Supplier<? extends X> exceptionSupplier)`: Ensures `value > min`,
  throws a custom exception otherwise.
* `requireBetween(int/long value, int/long min, int/long max, Supplier<? extends X> exceptionSupplier)`: Ensures
  `min <= value <= max`, throws a custom exception otherwise.

```java
public class NumericValidationExample {
    public static void main(String[] args) {
        int age = 25;
        long count = 100L;

        if (ValidationUtil.isGreaterThan(age, 18)) {
            System.out.println("Age is greater than 18"); // Output: Age is greater than 18
        }
        if (ValidationUtil.isBetween(count, 50L, 200L)) {
            System.out.println("Count is between 50 and 200"); // Output: Count is between 50 and 200
        }

        ValidationUtil.requireGreaterThan(age, 0, "Age must be positive.");
        ValidationUtil.requireBetween(count, 1L, 1000L, "Count must be between 1 and 1000.");

        try {
            ValidationUtil.requireBetween(age, 30, 40, () -> new IllegalArgumentException("Age must be in the 30s"));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage()); // Output: Age must be in the 30s
        }

        long largeValue = 5_000_000_000L;
        ValidationUtil.requireGreaterThan(largeValue, 4_000_000_000L, "Value must be over 4 billion");
    }
}
```

#### Index Validation

Used for safely accessing elements of arrays, lists, or strings, or checking if an index is a valid position.

* `isIndexValid(int index, int size)`: Checks if the index is within the bounds `[0, size)`.
* `checkElementIndex(int index, int size)` / `checkElementIndex(int index, int size, String message)` /
  `checkElementIndex(int index, int size, Supplier<? extends X> exceptionSupplier)`: Ensures the index is within
  `[0, size)` for element access. Throws `IndexOutOfBoundsException` or a custom exception on failure.
* `checkPositionIndex(int index, int size)` / `checkPositionIndex(int index, int size, String message)` /
  `checkPositionIndex(int index, int size, Supplier<? extends X> exceptionSupplier)`: Ensures the index is within
  `[0, size]` for iteration or insertion. Throws `IndexOutOfBoundsException` or a custom exception on failure.

```java
public class IndexValidationExample {
    public static void main(String[] args) {
        List<String> data = new ArrayList<>(List.of("one", "two"));
        int validAccessIndex = 1;
        int invalidAccessIndex = 2;
        int validPositionIndex = 2;
        int invalidPositionIndex = 3;

        if (ValidationUtil.isIndexValid(validAccessIndex, data.size())) {
            System.out.println("Element at index " + validAccessIndex + ": " + data.get(validAccessIndex)); // Output: Element at index 1: two
        }

        // Validate access index
        ValidationUtil.checkElementIndex(validAccessIndex, data.size(), "Invalid index to access element.");
        try {
            ValidationUtil.checkElementIndex(invalidAccessIndex, data.size());
        } catch (IndexOutOfBoundsException exception) {
            // Output: Access attempt failed: Index 2 out of bounds for length 2
            System.err.println("Access attempt failed: " + exception.getMessage());
        }

        // Validate position index
        ValidationUtil.checkPositionIndex(validPositionIndex, data.size(), "Invalid index for position.");
        // data.add(validPositionIndex, "three"); // This line is now legal
        // System.out.println("After adding at valid position: " + data);

        try {
            ValidationUtil.checkPositionIndex(invalidPositionIndex, data.size(), () -> new RuntimeException("Cannot use index " + invalidPositionIndex + " as position"));
        } catch (RuntimeException exception) {
            // Output: Position check failed: Cannot use index 3 as position
            System.err.println("Position check failed: " + exception.getMessage());
        }
    }
}
```

#### Boolean Condition Validation

Ensures that a boolean condition is `true` or `false`.

* `requireTrue(boolean condition, String message)` /
  `requireTrue(boolean condition, Supplier<? extends X> exceptionSupplier)`: Ensures the condition is `true`, throws
  `IllegalArgumentException` or a custom exception otherwise.
* `requireFalse(boolean condition, String message)` /
  `requireFalse(boolean condition, Supplier<? extends X> exceptionSupplier)`: Ensures the condition is `false`, throws
  `IllegalArgumentException` or a custom exception otherwise.

```java
public class ConditionValidationExample {
    public static void main(String[] args) {
        boolean isEnabled = true;
        boolean hasError = false;

        ValidationUtil.requireTrue(isEnabled, "Feature must be enabled.");
        ValidationUtil.requireFalse(hasError, () -> new IllegalStateException("Cannot proceed with errors."));

        try {
            ValidationUtil.requireTrue(!isEnabled, "This should fail if enabled.");
        } catch (IllegalArgumentException exception) {
            // Output: This should fail if enabled.
            System.err.println(exception.getMessage());
        }

        try {
            ValidationUtil.requireFalse(isEnabled, "This should fail if feature is enabled.");
        } catch (IllegalArgumentException exception) {
            // Output: This should fail if feature is enabled.
            System.err.println(exception.getMessage());
        }
    }
}
```

#### Generic Validation

Allows using custom `Predicate` functions to perform complex validation logic.

* `validate(T value, Predicate<T> predicate, String message)`: Validates `value` using the `predicate`. Throws
  `IllegalArgumentException` if the predicate returns `false`.
* `validate(T value, Predicate<T> predicate, Supplier<? extends X> exceptionSupplier)`: Validates `value` using the
  `predicate`. Throws a custom exception if the predicate returns `false`.

```java
public class GenericValidationExample {
    // Example User class
    static class User {
        String name;
        int level;

        User(String name, int level) {
            this.name = name;
            this.level = level;
        }

        @Override
        public String toString() {
            return "User{name='" + name + "', level=" + level + '}';
        }
    }

    public static void main(String[] args) {
        User validUser = new User("Admin", 10);
        User invalidUserLowLevel = new User("PowerUser", 3);
        User invalidUserGuest = new User("guest", 8);

        // Define a validation rule: username cannot be "guest" (case-insensitive) and level must be > 5
        Predicate<User> complexUserPredicate = u ->
                !"guest".equalsIgnoreCase(u.name) && u.level > 5;

        // Validate using standard exception
        ValidationUtil.validate(validUser, complexUserPredicate, "Invalid user data: Must not be guest and level > 5.");
        System.out.println("Valid user passed validation: " + validUser);

        // Validate using custom exception - low level user
        try {
            ValidationUtil.validate(invalidUserLowLevel, complexUserPredicate, () ->
                    new SecurityException("Access denied for user '" + invalidUserLowLevel.name + "': Level must be > 5."));
        } catch (SecurityException exception) {
            // Output: Access denied for user 'PowerUser': Level must be > 5.
            System.err.println(exception.getMessage());
        }

        // Validate using custom exception - Guest user
        try {
            ValidationUtil.validate(invalidUserGuest, complexUserPredicate, () ->
                    new SecurityException("Access denied: Guest users are not allowed."));
        } catch (SecurityException exception) {
            // Output: Access denied: Guest users are not allowed.
            System.err.println(exception.getMessage());
        }
    }
}
```

By combining these methods, `ValidationUtil` helps build robust and understandable validation layers, improving code
quality and maintainability.

### [SpatialUtil](src/main/java/net/legacy/library/commons/util/SpatialUtil.java)

`SpatialUtil` is a utility class for handling spatial calculations related to Bukkit `Location`. It provides methods for
checking positional relationships and the existence of blocks within an area.

* **`isWithinCuboid(Location loc1, Location loc2, Location target)`**:
    * **Function**: Checks if the target location `target` is within the cuboid (rectangular prism) defined by two diagonal
      corner points `loc1` and `loc2` (inclusive).
    * **Return Value**: Returns `true` if the target location is within the cuboid and all `Location` objects are in
      the same world and not `null`; otherwise returns `false`.
    * **Complexity**: O(1)

```java
public class CuboidCheckExample {
    public static void main(String[] args) {
        Location corner1 = new Location(world, 10, 60, 20);
        Location corner2 = new Location(world, 30, 70, 40);
        
        Location insideTarget = new Location(world, 15, 65, 25); // Target within X, Y, Z bounds
        Location outsideTargetY = new Location(world, 15, 75, 25); // Target outside Y bounds
        Location outsideTargetX = new Location(world, 5, 65, 30);  // Target outside X bounds

        if (SpatialUtil.isWithinCuboid(corner1, corner2, insideTarget)) {
            System.out.println("insideTarget is within the cuboid");
        } else {
            System.out.println("insideTarget is not within the cuboid");
        }

        if (SpatialUtil.isWithinCuboid(corner1, corner2, outsideTargetY)) {
            System.out.println("outsideTargetY is within the cuboid");
        } else {
            System.out.println("outsideTargetY is not within the cuboid");
        }

        if (SpatialUtil.isWithinCuboid(corner1, corner2, outsideTargetX)) {
            System.out.println("outsideTargetX is within the cuboid");
        } else {
            System.out.println("outsideTargetX is not within the cuboid");
        }
    }
}
```

* **`hasBlocksNearby(Location center, int xRange, int yRange, int zRange)`**:
    * **Function**: Checks if there are any non-air blocks (`AIR`, `CAVE_AIR`, `VOID_AIR`) within the cuboid region
      centered at `center`. The parameters `xRange`, `yRange`, and `zRange` define the approximate total size along each axis;
      the actual check range extends `range / 2` blocks in both positive and negative directions from the center block's coordinates.
      This method uses `ChunkSnapshot` for efficiency, especially when the check range spans multiple chunks.
    * **Warning**: Checking very large ranges consumes significant server resources (CPU, memory) as it needs to iterate
      through all blocks in the range and potentially load/create chunk snapshots. **It is strongly recommended to
      perform this check asynchronously** to avoid blocking the main thread.
    * **Complexity**: Worst case is approximately O(xRange * yRange * zRange), proportional to the volume of blocks
      checked.

```java
public class BlockCheckExample {
    public void checkArea(Location center) {
        // Check for blocks within a range centered at 'center'.
        // A range of 10 along an axis means checking 5 blocks in the positive and 5 blocks in the negative direction from the center block.
        // So, xRange=10 checks from center.X - 5 to center.X + 5 (inclusive, covering 11 blocks if xRange is even).
        int xRange = 10;
        int yRange = 10;
        int zRange = 10;

        boolean blocksFound = SpatialUtil.hasBlocksNearby(center, xRange, yRange, zRange);
        if (blocksFound) {
            System.out.println("Non-air blocks exist within the specified range around center (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ").");
        } else {
            System.out.println("No non-air blocks found within the specified range around center (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ").");
        }
    }
}
```

### [AnvilGUI](https://github.com/WesJD/AnvilGUI)

`AnvilGUI` (`net.legacy.library.libs.anvilgui`) is only packaged as a dependency and relocated, just to facilitate some repeated development.