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