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

### [TaskChain](src/main/java/net/legacy/library/commons/task/TaskChain.java)

`TaskChain` 提供了一个强大的流式 API，用于链式构建和执行任务。它支持多种执行模式，包括虚拟线程、定时任务和异步操作，同时提供完善的结果管理和错误处理机制。

#### 基本用法

```java
public class TaskChainExample {
    public static void main(String[] args) {
        // 创建任务链并执行多个任务
        TaskChain taskChain = TaskChain.builder()
                // 定义第一个任务的执行模式并立即执行
                .withMode((taskInterface, task) -> {
                    Log.info("执行任务: " + task);
                    return "结果: " + task;
                })
                // withMode 方法 task 字段为：任务1（可以为 null）
                .execute("任务1")

                // 继续添加第二个任务
                .then()
                .withMode((taskInterface, task) -> {
                    Log.info("执行任务: " + task);
                    return "结果: " + task;
                })
                .run("任务2") // run 与 execute 等价，只是更直观的命名

                // 添加命名任务以便后续访问
                .then()
                .withMode((taskInterface, task) -> // 此处的 task 即为下方 execute 的第二个传参
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                Thread.sleep(1000);
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "计算完成: " + Math.random();
                        }))
                // 该任务名称为：计算任务，其上方 withMode 方法 task 字段即为：异步计算（可以为 null）
                .execute("计算任务", "异步计算")

                // 构建最终的任务链
                .build();

        // 等待所有任务完成
        taskChain.join().get(5, TimeUnit.SECONDS);

        // 通过名称获取结果
        String result = taskChain.getResult("计算任务");
        System.out.println("计算结果: " + result);

        // 通过索引获取结果
        String indexResult = taskChain.getResult(2);
        System.out.println("索引2的结果: " + indexResult);

        System.out.println("任务链包含 " + taskChain.size() + " 个任务");
    }
}
```

#### 虚拟线程

```java
public class VirtualThreadExample {
    public static void main(String[] args) {
        AtomicInteger counter = new AtomicInteger(0);

        TaskChain taskChain = TaskChain.builder()
                // 使用虚拟线程执行 I/O 密集型任务
                .withMode((taskInterface, task) ->
                        taskInterface.submitWithVirtualThreadAsync(() -> {
                            counter.incrementAndGet();
                            try {
                                Thread.sleep(500); // 模拟网络请求或 I/O 操作
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "虚拟线程任务完成: " + task;
                        }))
                .run("虚拟线程任务", "VT任务")
                .then()
                // 添加另一个虚拟线程任务
                .withMode((taskInterface, task) ->
                        taskInterface.submitWithVirtualThreadAsync(() -> {
                            counter.incrementAndGet();
                            Thread.sleep(100);
                            return "虚拟线程任务完成: " + task;
                        }))
                .execute("VT任务2")
                .build();

        taskChain.join().get(5, TimeUnit.SECONDS);

        String result1 = taskChain.getResult("虚拟线程任务");
        String result2 = taskChain.getResult(1);
        System.out.println("虚拟线程结果1: " + result1);
        System.out.println("虚拟线程结果2: " + result2);
    }
}
```

#### 定时任务链

```java
public class ScheduledTaskExample {
    public static void main(String[] args) {
        long startTime = System.currentTimeMillis();

        TaskChain taskChain = TaskChain.builder()
                // 立即执行的任务
                .withMode((taskInterface, task) -> {
                    Log.info("立即执行: " + System.currentTimeMillis());
                    return "立即执行结果";
                })
                .execute("立即任务")

                // 延迟1秒执行的任务
                .then()
                .withMode((taskInterface, task) ->
                        taskInterface.schedule(() -> {
                            Log.info("延迟任务执行: " + task);
                        }, 20L)) // 延迟1秒 (20 ticks)
                .execute("延迟任务", "延迟任务")

                // 虚拟线程延迟任务
                .then()
                .withMode((taskInterface, task) ->
                        taskInterface.scheduleWithVirtualThread(() -> {
                            Log.info("虚拟线程延迟任务执行: " + task);
                        }, 100, TimeUnit.MILLISECONDS))
                .run("虚拟线程延迟", "虚拟线程延迟任务")

                .build();

        // 等待所有任务完成
        taskChain.join().get(10, TimeUnit.SECONDS);

        // 可以获取原始的 Mode 返回值进行高级控制
        ScheduledTask<?> scheduledTask = taskChain.getModeResult("延迟任务");
        Object vtScheduledFuture = taskChain.getModeResult("虚拟线程延迟");

        long executionTime = System.currentTimeMillis() - startTime;
        System.out.println("总执行时间: " + executionTime + "ms");
    }
}
```

#### 结果管理和错误处理

```java
public class ResultManagementExample {
    public static void main(String[] args) {
        TaskChain taskChain = TaskChain.builder()
                // 成功的任务
                .withMode((taskInterface, task) ->
                        CompletableFuture.supplyAsync(() -> "成功结果"))
                .execute("成功任务", "同步任务")

                // 可能失败的任务
                .then()
                .withMode((taskInterface, task) ->
                        CompletableFuture.supplyAsync(() -> {
                            if (Math.random() > 0.5) {
                                throw new RuntimeException("随机失败");
                            }
                            return "风险任务成功";
                        }))
                .execute("风险任务", "异步任务")

                .build();

        try {
            // 带超时的结果获取
            String result1 = taskChain.getResult("成功任务", 5, TimeUnit.SECONDS);
            System.out.println("成功任务结果: " + result1);

            // 检查任务是否存在
            if (taskChain.hasTask("风险任务")) {
                String result2 = taskChain.getResult("风险任务");
                System.out.println("风险任务结果: " + result2);
            }

        } catch (RuntimeException exception) {
            System.err.println("任务执行失败: " + exception.getMessage());
        }

        // 获取所有原始 Mode 结果
        List<Object> modeResults = taskChain.getAllModeResults();
        System.out.println("Mode 结果数量: " + modeResults.size());

        // 获取名称到索引的映射
        Map<String, Integer> nameMapping = taskChain.getNameToIndexMap();
        System.out.println("命名任务: " + nameMapping.keySet());

        // 测试超时处理
        TaskChain timeoutChain = TaskChain.builder()
                .withMode((taskInterface, task) ->
                        CompletableFuture.supplyAsync(() -> {
                            try {
                                Thread.sleep(2000); // 2秒延迟
                            } catch (InterruptedException e) {
                                Thread.currentThread().interrupt();
                            }
                            return "超时任务完成";
                        }))
                .execute("超时任务")
                .build();

        try {
            timeoutChain.getResult(0, 500, TimeUnit.MILLISECONDS);
        } catch (RuntimeException e) {
            System.err.println("超时处理: " + e.getMessage());
        }
    }
}
```

#### 自定义 TaskInterface

```java
public class CustomTaskInterfaceExample {
    public static void main(String[] args) {
        // 创建自定义的 TaskInterface
        TaskInterface<?> customTaskInterface = new TaskInterface<>() {
            @Override
            public void execute(Runnable task) {
                Log.info("使用自定义TaskInterface执行任务");
                task.run();
            }
        };

        TaskChain taskChain = TaskChain.builder(customTaskInterface)
                .withMode((taskInterface, task) -> {
                    taskInterface.execute(() -> Log.info("自定义执行: " + task));
                    return "自定义结果";
                })
                .execute("自定义任务")
                .build();

        String result = taskChain.getResult(0);
        System.out.println("自定义结果: " + result);
    }
}
```

### 虚拟线程 Pinning 警告

> **Java 21 虚拟线程重要提示**

在本模块中使用虚拟线程时，请注意 "pinning" 问题：如果虚拟线程在 `synchronized` 块/方法内阻塞，
它将无法从载体线程卸载，导致性能下降。

**使用指南：**

1. **避免使用 `synchronized`**：在可能被虚拟线程调用的代码中，请使用 `java.util.concurrent.locks.ReentrantLock` 替代。
2. **关闭方法**：`VirtualThreadExecutors.destroy()` 和 `VirtualThreadSchedulerManager.destroy()` 使用阻塞操作
   （`awaitTermination`），应**仅从平台线程调用**（如主线程或关闭钩子）。
3. **测试检测**：开发时添加 JVM 参数 `-Djdk.tracePinnedThreads=short` 以检测 pinning 事件。
4. **Java 24 已解决此问题**（[JEP 491](https://openjdk.org/jeps/491) 已交付），升级后可移除这些限制。

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

### [SpatialUtil](src/main/java/net/legacy/library/commons/util/SpatialUtil.java)

`SpatialUtil` 是一个用于处理 Bukkit `Location` 相关空间计算的实用工具类。它提供了一些用于检查位置关系和区域内方块存在性的方法。

* **`isWithinCuboid(Location loc1, Location loc2, Location target)`**:
    * **功能**: 检查目标位置 `target` 是否位于由两个对角点 `loc1` 和 `loc2` 定义的长方体区域内（包含边界）
    * **返回值**: 如果目标位置在长方体内，并且所有 `Location` 都在同一个世界且不为 `null`，则返回 `true`；否则返回 `false`
    * **复杂度**: O(1)

```java
public class CuboidCheckExample {
    public static void main(String[] args) {
        Location corner1 = new Location(world, 10, 60, 20);
        Location corner2 = new Location(world, 30, 70, 40);

        Location insideTarget = new Location(world, 15, 65, 25); // 目标在 X, Y, Z 边界内
        Location outsideTargetY = new Location(world, 15, 75, 25); // 目标在 Y 边界外
        Location outsideTargetX = new Location(world, 5, 65, 30);  // 目标在 X 边界外

        if (SpatialUtil.isWithinCuboid(corner1, corner2, insideTarget)) {
            System.out.println("insideTarget 在长方体内");
        } else {
            System.out.println("insideTarget 不在长方体内");
        }

        if (SpatialUtil.isWithinCuboid(corner1, corner2, outsideTargetY)) {
            System.out.println("outsideTargetY 在长方体内");
        } else {
            System.out.println("outsideTargetY 不在长方体内");
        }

        if (SpatialUtil.isWithinCuboid(corner1, corner2, outsideTargetX)) {
            System.out.println("outsideTargetX 在长方体内");
        } else {
            System.out.println("outsideTargetX 不在长方体内");
        }
    }
}
```

* **`hasBlocksNearby(Location center, int xRange, int yRange, int zRange)`**:
    * **功能**: 检查以 `center` 为中心的指定范围的长方体区域内，是否存在任何非空气方块（`AIR`, `CAVE_AIR`,
      `VOID_AIR`）。参数 `xRange`, `yRange`, `zRange` 定义了各轴上的大致总范围；实际检查范围是从中心方块坐标向正负方向各延伸
      `range / 2` 格。
      该方法使用 `ChunkSnapshot` 以提高效率，尤其是在检查范围跨越多个区块时
    * **警告**: 检查非常大的范围会消耗大量服务器资源（CPU、内存），因为它需要迭代检查范围内的所有方块，并可能加载/创建区块快照

```java
public class BlockCheckExample {
    public void checkArea(Location center) {
        // 检查以 center 为中心的指定范围内的方块
        // 轴范围为 10 表示从中心方块向正负方向各检查 5 格。
        // 例如，xRange=10 会检查 X 坐标从 center.X - 5 到 center.X + 5 (包含边界，如果xRange是偶数，则覆盖11格)
        int xRange = 10;
        int yRange = 10;
        int zRange = 10;

        boolean blocksFound = SpatialUtil.hasBlocksNearby(center, xRange, yRange, zRange);
        if (blocksFound) {
            System.out.println("中心点 (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ") 周围的指定范围内存在非空气方块。");
        } else {
            System.out.println("中心点 (" + center.getBlockX() + ", " + center.getBlockY() + ", " + center.getBlockZ() + ") 周围的指定范围内没有非空气方块。");
        }
    }
}
```

### [AnvilGUI](https://github.com/WesJD/AnvilGUI)

`AnvilGUI` (`net.legacy.library.libs.anvilgui`) 只是作为一个依赖项被打包进来，并且进行了重定位，这只是为了方便一些重复开发。