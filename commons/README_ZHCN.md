### 实用工具 (Commons) 模块

这是一个包含各种实用工具的模块，用途广泛，因此内容比较杂，但当有新内容时，我会及时更新此文档。

### 用法

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

这是一个 `injector`（注入器），它的主要用途是与
[VarHandleAutoInjection](src/main/java/net/legacy/library/commons/injector/annotation/VarHandleAutoInjection.java).

是的，就像它的名字一样，我们不必编写一堆丑陋的代码来赋值 `VarHandle`，让这一切都消失吧，阿门。

```java
public class Example {
    public static void main(String[] args) {
        Test test = new Test();

        // 我们可以使用 TField_HANDLE
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
         * 这个类是一个单例，由 Fairy IoC 管理。
         * 当然，也允许使用工厂直接创建它，或者直接创建它。
         * 这并不是那么严格。
         */
        InjectorFactory.createVarHandleReflectionInjector().inject(Test.class);
    }
}
```

### [Task](src/main/java/net/legacy/library/commons/task)

[TaskInterface](src/main/java/net/legacy/library/commons/task/TaskInterface.java)
通过提供与 Fairy 框架的 [MCScheduler](https://docs.fairyproject.io/core/minecraft/scheduler) 具有一致命名和参数顺序的便捷方法，简化任务调度。

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface<ScheduledTask<?>> taskInterface = new TaskInterface<>() {
            @Override
            public ScheduledTask<?> start() {
                // 这是一个简单的任务示例，每秒打印一次 "Hello, world!"。
                return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 20);
            }
        };

        // 启动任务
        taskInterface.start();
    }
}
```

同时，该模块也支持虚拟线程的各种调度操作，这在网络通信，IO 等方面尤为有用，拥有更高的性能。

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface<ScheduledFuture<?>> taskInterface = new TaskInterface<>() {
            @Override
            public ScheduledFuture<?> start() {
                // 这是一个简单的任务示例，使用虚拟线程，每秒打印一次 "Hello, world!"。
                return scheduleAtFixedRateWithVirtualThread(() -> System.out.println("Hello, world!"), 0, 1, TimeUnit.SECONDS);
            }
        };
    }
}
```

它还提供了 [TaskAutoStartAnnotation](src/main/java/net/legacy/library/commons/task/annotation/TaskAutoStartAnnotation.java)
来处理一些需要在特定时间自动启动的任务。当有很多任务需要启动时，注解处理器将帮助我们避免手动管理这些实例的创建和调用，从而简化代码。

```java

@TaskAutoStartAnnotation(isFromFairyIoC = false)
public class Example implements TaskInterface<ScheduledTask<?>> {
    @Override
    public ScheduledTask<?> start() {
        // 这是一个简单的任务示例，每秒打印一次 "Hello, world!"。
        return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 20);
    }
}
```

至于如何在您自己的插件上使注解处理器工作，请参阅 [annotation](../annotation/README.md) 模块。更多方法请详细阅读 JavaDoc。

### [GsonUtil](src/main/java/net/legacy/library/commons/util/GsonUtil.java)

`GsonUtil` 提供了一种线程安全的方式来管理和自定义共享的 `Gson` 实例。它允许在您的应用程序中保持一致的 `Gson`
配置，防止分散和潜在冲突的设置。

为了防止依赖冲突，所以您应该先将 `fairy-lib-plugin` 作为依赖导入，使用重定位后的 `Gson`，包应为 `io.fairyproject.libs.gson`
。而无需手动导入依赖并重定位。

```java
public class Example {
    public static void main(String[] args) {
        // 格式化 JSON 输出
        GsonUtil.customizeGsonBuilder(builder -> builder.setPrettyPrinting());

        // 添加自定义类型适配器
        GsonUtil.customizeGsonBuilder(builder -> builder.registerTypeAdapter(MyClass.class, new MyClassTypeAdapter()));

        // 获取共享的 Gson 实例以供使用
        Gson gson = GsonUtil.getGson();
    }
}
```

我们在 `player` 模块中有一个 `TypeAdapterRegister` 注解，它可以用来简化 `Type Adapter` 的注册。

### [RandomGenerator](src/main/java/net/legacy/library/commons/util/random/RandomGenerator.java)

`RandomGenerator` 源自作者 `qwq-dev (2000000)` 已废弃的项目 `Advanced Wish`，但该工具在按权重进行随机选择的场景下依旧十分实用。

当你需要从一组对象中根据各自的权重（概率）随机抽取一个对象时，`RandomGenerator`
会是一个便捷的选择。它支持动态添加对象及其权重，并提供了多种随机算法以满足不同场景下的需求。

- `ThreadLocalRandom` (默认): 标准伪随机数生成器，性能高效，适用于大多数常规场景
- `SecureRandom`: 提供更高安全性的随机数生成器，适用于对随机性要求严格的场景
- **蒙特卡罗方法 (`getResultWithMonteCarlo`)**: 通过模拟大量随机事件来逼近概率分布，理论上更公平，但在大数据集下效率可能较低
- **洗牌方法 (`getResultWithShuffle`)**: 通过打乱对象列表的顺序来实现随机化，随机性较好，但可能不严格符合设定的权重
- **梅森旋转 (`getResultWithMersenneTwister`)**: 高质量的伪随机数生成器，以其极长的周期和良好的统计特性而闻名
- **XORShift (`getResultWithXORShift`)**: 一类快速且简单的伪随机数生成器。通常表现良好，但可能无法通过所有严格的统计测试
- **高斯方法 (`getResultWithGaussian`)**: 生成符合高斯分布（正态分布）的随机数进行选择，可以使得选择结果更倾向于权重集中的区域，而非简单的线性概率

```java
public class Main {
    public static void main(String[] args) {
        // 创建一个 RandomGenerator 实例，并初始化对象及其权重
        // "Apple" 权重 30, "Banana" 权重 50, "Orange" 权重 20
        RandomGenerator<String> randomGenerator = new RandomGenerator<>(
                "Apple", 30,
                "Banana", 50,
                "Orange", 20
        );

        // 使用普通伪随机数生成器进行随机选择
        String result = randomGenerator.getResult();
        System.out.println("普通伪随机数生成器选择的结果: " + result);

        // 使用更安全的随机数生成器进行随机选择
        String secureResult = randomGenerator.getResultWithSecureRandom();
        System.out.println("更安全的随机数生成器选择的结果: " + secureResult);

        // 使用蒙特卡罗方法进行随机选择
        String monteCarloResult = randomGenerator.getResultWithMonteCarlo();
        System.out.println("蒙特卡罗方法选择的结果: " + monteCarloResult);

        // 使用洗牌方法进行随机选择
        String shuffleResult = randomGenerator.getResultWithShuffle();
        System.out.println("洗牌方法选择的结果: " + shuffleResult);

        // 使用高斯方法进行随机选择
        String gaussianResult = randomGenerator.getResultWithGaussian();
        System.out.println("高斯方法选择的结果: " + gaussianResult);
    }
}
```

另外，权重的总和不必为 100，`RandomGenerator` 会自动根据权重比例计算概率。选择合适的随机算法取决于你的具体应用场景对性能、安全性和随机分布特性的要求。

### [ValidationUtil](src/main/java/net/legacy/library/commons/util/ValidationUtil.java)

`ValidationUtil` 是一个全面的静态工具类，旨在简化各种常见的输入校验任务。它提供了一套简洁易用的 API，支持多种校验方式，并覆盖了多种数据类型。

* **多种校验模式**: 提供返回布尔值的方法（如 `isNull`, `isEmpty`）和在校验失败时抛出异常的方法（如 `requireNonNull`,
  `requireNotEmpty`）
* **异常灵活性**: 支持抛出标准的 Java 异常（`IllegalArgumentException`, `NullPointerException`,
  `IndexOutOfBoundsException`）或由调用者提供的自定义异常（通过 `Supplier`）
* **类型覆盖广泛**: 支持对象、字符串、集合、Map、数组、数值（int/long）和索引的校验
* **易于使用**: 所有方法均为静态方法，方便直接调用

#### 对象校验 (Object Validation)

用于检查对象是否为 `null` 以及两个对象是否相等。

* `isNull(Object value)`: 检查对象是否为 `null`。
* `notNull(Object value)`: 检查对象是否不为 `null`。
* `requireNonNull(Object value, String message)`: 确保对象不为 `null`，否则抛出 `NullPointerException`。
* `requireNonNull(T value, Supplier<? extends X> exceptionSupplier)`: 确保对象不为 `null`，否则抛出自定义异常。
* `equals(Object object1, Object object2)`: 检查两个对象是否相等。
* `notEquals(Object object1, Object object2)`: 检查两个对象是否不相等。
* `requireEquals(Object object1, Object object2, String message)`: 确保两个对象相等，否则抛出 `IllegalArgumentException`。
* `requireEquals(Object object1, Object object2, Supplier<? extends X> exceptionSupplier)`: 确保两个对象相等，否则抛出自定义异常。

```java
public class ObjectValidationExample {
    public static void main(String[] args) {
        Object obj = null;
        Object obj2 = "Hello";

        if (ValidationUtil.isNull(obj)) {
            System.out.println("obj is null"); // 输出: obj is null
        }

        ValidationUtil.requireNonNull(obj2, "obj2 cannot be null");

        try {
            ValidationUtil.requireNonNull(obj, () -> new IllegalStateException("Object must not be null here."));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // 输出: Object must not be null here.
        }

        String s1 = "test";
        String s2 = new String("test"); // 内容相同，但对象不同
        String s3 = "different";

        // 使用 Objects.equals 比较内容
        if (ValidationUtil.equals(s1, s2)) {
            System.out.println("s1 and s2 are equal (content-wise)"); // 输出: s1 and s2 are equal (content-wise)
        }

        ValidationUtil.requireEquals(s1, s2, "s1 and s2 must be equal");

        try {
            ValidationUtil.requireEquals(s1, s3, () -> new RuntimeException("Strings must match"));
        } catch (RuntimeException exception) {
            System.err.println(exception.getMessage()); // 输出: Strings must match
        }
    }
}
```

#### 字符串校验 (String Validation)

提供对字符串是否为空、空白、长度以及是否匹配正则表达式的校验。

* `isEmpty(String inputString)`: 检查字符串是否为 `null` 或空字符串 `""`
* `isBlank(String inputString)`: 检查字符串是否为 `null`、空字符串 `""` 或仅包含空白字符
* `notEmpty(String inputString)`: 检查字符串是否不为 `null` 且不为空字符串 `""`
* `notBlank(String inputString)`: 检查字符串是否不为 `null`、不为空字符串 `""` 且包含非空白字符
* `requireNotEmpty(String inputString, String message)`: 确保字符串非空，否则抛出 `IllegalArgumentException`
* `requireNotBlank(String inputString, String message)`: 确保字符串非空白，否则抛出 `IllegalArgumentException`
* `requireNotEmpty(String inputString, Supplier<? extends X> exceptionSupplier)`: 确保字符串非空，否则抛出自定义异常
* `requireNotBlank(String inputString, Supplier<? extends X> exceptionSupplier)`: 确保字符串非空白，否则抛出自定义异常
* `lengthBetween(String inputString, int minLength, int maxLength)`: 检查字符串长度是否在 `[minLength, maxLength]` 范围内
* `requireLengthBetween(String inputString, int minLength, int maxLength, String message)`: 确保字符串长度在范围内，否则抛出
  `IllegalArgumentException`
* `requireLengthBetween(String inputString, int minLength, int maxLength, Supplier<? extends X> exceptionSupplier)`:
  确保字符串长度在范围内，否则抛出自定义异常
* `matches(String inputString, String regex)` / `matches(String inputString, Pattern pattern)`: 检查字符串是否匹配正则表达式。
* `requireMatches(String inputString, String regex, String message)` /
  `requireMatches(String inputString, Pattern pattern, String message)`: 确保字符串匹配正则，否则抛出
  `IllegalArgumentException`
* `requireMatches(String inputString, String regex, Supplier<? extends X> exceptionSupplier)` /
  `requireMatches(String inputString, Pattern pattern, Supplier<? extends X> exceptionSupplier)`: 确保字符串匹配正则，否则抛出自定义异常

```java
public class StringValidationExample {
    public static void main(String[] args) {
        String emptyStr = "";
        String blankStr = "   ";
        String validStr = " example ";
        String numberStr = "12345";
        Pattern numberPattern = Pattern.compile("\\d+");

        if (ValidationUtil.isEmpty(emptyStr)) {
            System.out.println("emptyStr is empty"); // 输出: emptyStr is empty
        }
        if (ValidationUtil.isBlank(blankStr)) {
            System.out.println("blankStr is blank"); // 输出: blankStr is blank
        }
        if (ValidationUtil.notBlank(validStr)) {
            System.out.println("validStr is not blank"); // 输出: validStr is not blank
        }

        ValidationUtil.requireNotBlank(validStr, "String must not be blank");
        ValidationUtil.requireLengthBetween(validStr.trim(), 5, 10, "Trimmed length (example) must be between 5 and 10"); // "example" 长度为 7
        ValidationUtil.requireMatches(numberStr, numberPattern, "String must contain only digits");

        try {
            ValidationUtil.requireNotEmpty(emptyStr, () -> new NoSuchElementException("String is required."));
        } catch (NoSuchElementException exception) {
            System.err.println(exception.getMessage()); // 输出: String is required.
        }
    }
}
```

#### 集合/Map/数组校验 (Collection/Map/Array Validation)

用于检查集合、Map 或数组是否为 `null` 或空。

* `isEmpty(Collection<?> collection)` / `isEmpty(Map<?, ?> map)` / `isEmpty(T[] array)`: 检查是否为 `null` 或空
* `notEmpty(Collection<?> collection)` / `notEmpty(Map<?, ?> map)` / `notEmpty(T[] array)`: 检查是否不为 `null` 且不为空
* `requireNotEmpty(Collection<?> collection, String message)` / `requireNotEmpty(Map<?, ?> map, String message)` /
  `requireNotEmpty(T[] array, String message)`: 确保非空，否则抛出 `IllegalArgumentException`
* `requireNotEmpty(Collection<?> collection, Supplier<? extends X> exceptionSupplier) / 
   requireNotEmpty(Map<?, ?> map, Supplier<? extends X> exceptionSupplier) / requireNotEmpty(T[] array, Supplier<? extends X> exceptionSupplier)`:
  确保非空，否则抛出自定义异常

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
            System.out.println("emptyList is empty"); // 输出: emptyList is empty
        }
        if (ValidationUtil.notEmpty(list)) {
            System.out.println("list is not empty"); // 输出: list is not empty
        }
        if (ValidationUtil.isEmpty(emptyMap)) {
            System.out.println("emptyMap is empty"); // 输出: emptyMap is empty
        }
        if (ValidationUtil.notEmpty(map)) {
            System.out.println("map is not empty"); // 输出: map is not empty
        }
        if (ValidationUtil.isEmpty(emptyArray)) {
            System.out.println("emptyArray is empty"); // 输出: emptyArray is empty
        }
        if (ValidationUtil.notEmpty(array)) {
            System.out.println("array is not empty"); // 输出: array is not empty
        }

        ValidationUtil.requireNotEmpty(list, "List cannot be empty.");
        ValidationUtil.requireNotEmpty(map, "Map cannot be empty.");
        ValidationUtil.requireNotEmpty(array, "Array cannot be empty.");

        try {
            ValidationUtil.requireNotEmpty(emptyList, () -> new IllegalStateException("Need at least one element in the list"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // 输出: Need at least one element in the list
        }

        try {
            ValidationUtil.requireNotEmpty(emptyMap, () -> new IllegalStateException("Need at least one entry in the map"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // 输出: Need at least one entry in the map
        }

        try {
            ValidationUtil.requireNotEmpty(emptyArray, () -> new IllegalStateException("Need at least one element in the array"));
        } catch (IllegalStateException exception) {
            System.err.println(exception.getMessage()); // 输出: Need at least one element in the array
        }
    }
}
```

#### 数值校验 (Numeric Validation - int/long)

比较数值大小或检查数值是否在指定范围内。

* `isGreaterThan(int/long value, int/long min)`: 检查 `value > min`
* `isGreaterThanOrEqual(int/long value, int/long min)`: 检查 `value >= min`
* `isLessThan(int/long value, int/long max)`: 检查 `value < max`
* `isLessThanOrEqual(int/long value, int/long max)`: 检查 `value <= max`
* `isBetween(int/long value, int/long min, int/long max)`: 检查 `min <= value <= max`
* `requireGreaterThan(int/long value, int/long min, String message)`: 确保 `value > min`，否则抛出
  `IllegalArgumentException`
* `requireBetween(int/long value, int/long min, int/long max, String message)`: 确保 `min <= value <= max`，否则抛出
  `IllegalArgumentException`
* `requireGreaterThan(int/long value, int/long min, Supplier<? extends X> exceptionSupplier)`: 确保 `value > min`
  ，否则抛出自定义异常
* `requireBetween(int/long value, int/long min, int/long max, Supplier<? extends X> exceptionSupplier)`: 确保
  `min <= value <= max`，否则抛出自定义异常

```java
public class NumericValidationExample {
    public static void main(String[] args) {
        int age = 25;
        long count = 100L;

        if (ValidationUtil.isGreaterThan(age, 18)) {
            System.out.println("Age is greater than 18"); // 输出: Age is greater than 18
        }
        if (ValidationUtil.isBetween(count, 50L, 200L)) {
            System.out.println("Count is between 50 and 200"); // 输出: Count is between 50 and 200
        }

        ValidationUtil.requireGreaterThan(age, 0, "Age must be positive.");
        ValidationUtil.requireBetween(count, 1L, 1000L, "Count must be between 1 and 1000.");

        try {
            ValidationUtil.requireBetween(age, 30, 40, () -> new IllegalArgumentException("Age must be in the 30s"));
        } catch (IllegalArgumentException exception) {
            System.err.println(exception.getMessage()); // 输出: Age must be in the 30s
        }

        long largeValue = 5_000_000_000L;
        ValidationUtil.requireGreaterThan(largeValue, 4_000_000_000L, "Value must be over 4 billion");
    }
}
```

#### 索引校验 (Index Validation)

用于安全地访问数组、列表或字符串的元素，或检查索引是否是有效的位置。

* `isIndexValid(int index, int size)`: 检查索引是否在 `[0, size)` 范围内
* `checkElementIndex(int index, int size)` / `checkElementIndex(int index, int size, String message)` /
  `checkElementIndex(int index, int size, Supplier<? extends X> exceptionSupplier)`: 确保索引在 `[0, size)`
  范围内，用于访问元素。校验失败时抛出 `IndexOutOfBoundsException` 或自定义异常
* `checkPositionIndex(int index, int size)` / `checkPositionIndex(int index, int size, String message)` /
  `checkPositionIndex(int index, int size, Supplier<? extends X> exceptionSupplier)`: 确保索引在 `[0, size]`
  范围内，用于迭代或插入。校验失败时抛出 `IndexOutOfBoundsException` 或自定义异常

```java
public class IndexValidationExample {
    public static void main(String[] args) {
        List<String> data = new ArrayList<>(List.of("one", "two"));
        int validAccessIndex = 1;
        int invalidAccessIndex = 2;
        int validPositionIndex = 2;
        int invalidPositionIndex = 3;

        if (ValidationUtil.isIndexValid(validAccessIndex, data.size())) {
            System.out.println("Element at index " + validAccessIndex + ": " + data.get(validAccessIndex)); // 输出: Element at index 1: two
        }

        // 校验访问索引
        ValidationUtil.checkElementIndex(validAccessIndex, data.size(), "Invalid index to access element.");
        try {
            ValidationUtil.checkElementIndex(invalidAccessIndex, data.size());
        } catch (IndexOutOfBoundsException exception) {
            // 输出: Access attempt failed: Index 2 out of bounds for length 2
            System.err.println("Access attempt failed: " + exception.getMessage());
        }

        // 校验位置索引
        ValidationUtil.checkPositionIndex(validPositionIndex, data.size(), "Invalid index for position.");
        // data.add(validPositionIndex, "three"); // 这行现在是合法的
        // System.out.println("After adding at valid position: " + data);

        try {
            ValidationUtil.checkPositionIndex(invalidPositionIndex, data.size(), () -> new RuntimeException("Cannot use index " + invalidPositionIndex + " as position"));
        } catch (RuntimeException exception) {
            // 输出: Position check failed: Cannot use index 3 as position
            System.err.println("Position check failed: " + exception.getMessage());
        }
    }
}
```

#### 条件校验 (Boolean Condition Validation)

确保某个布尔条件为 `true` 或 `false`。

* `requireTrue(boolean condition, String message)` /
  `requireTrue(boolean condition, Supplier<? extends X> exceptionSupplier)`: 确保条件为 `true`，否则抛出
  `IllegalArgumentException` 或自定义异常
* `requireFalse(boolean condition, String message)` /
  `requireFalse(boolean condition, Supplier<? extends X> exceptionSupplier)`: 确保条件为 `false`，否则抛出
  `IllegalArgumentException` 或自定义异常

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
            // 输出: This should fail if enabled.
            System.err.println(exception.getMessage());
        }

        try {
            ValidationUtil.requireFalse(isEnabled, "This should fail if feature is enabled.");
        } catch (IllegalArgumentException exception) {
            // 输出: This should fail if feature is enabled.
            System.err.println(exception.getMessage());
        }
    }
}
```

#### 通用校验 (Generic Validation)

允许使用自定义的 `Predicate` 来执行复杂的校验逻辑。

* `validate(T value, Predicate<T> predicate, String message)`: 使用 `predicate` 校验 `value`，如果 `predicate` 返回
  `false`，则抛出 `IllegalArgumentException`
* `validate(T value, Predicate<T> predicate, Supplier<? extends X> exceptionSupplier)`: 使用 `predicate` 校验 `value`，如果
  `predicate` 返回 `false`，则抛出自定义异常

```java
public class GenericValidationExample {
    // 示例用户类
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

        // 定义一个校验规则：用户名不能是 "guest" (忽略大小写) 且等级必须大于 5
        java.util.function.Predicate<User> complexUserPredicate = u ->
                !"guest".equalsIgnoreCase(u.name) && u.level > 5;

        // 使用标准异常进行校验
        ValidationUtil.validate(validUser, complexUserPredicate, "Invalid user data: Must not be guest and level > 5.");
        System.out.println("Valid user passed validation: " + validUser);

        // 使用自定义异常进行校验 - 低等级用户
        try {
            ValidationUtil.validate(invalidUserLowLevel, complexUserPredicate, () ->
                    new SecurityException("Access denied for user '" + invalidUserLowLevel.name + "': Level must be > 5."));
        } catch (SecurityException exception) {
            // 输出: Access denied for user 'PowerUser': Level must be > 5.
            System.err.println(exception.getMessage());
        }

        // 使用自定义异常进行校验 - Guest 用户
        try {
            ValidationUtil.validate(invalidUserGuest, complexUserPredicate, () ->
                    new SecurityException("Access denied: Guest users are not allowed."));
        } catch (SecurityException exception) {
            // 输出: Access denied: Guest users are not allowed.
            System.err.println(exception.getMessage());
        }
    }
}
```

通过组合使用这些方法，`ValidationUtil` 可以帮助您构建健壮且易于理解的校验层，提高代码质量和可维护性。
