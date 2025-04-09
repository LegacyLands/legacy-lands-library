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