### Foundation 模块

各库模块的服务端内集成测试所依赖的测试基础设施：描述测试类的 `@ModuleTest` 注解，运行测试类并校验整轮结果的 `AbstractModuleTestRunner`，记录结果的 `TestResultSummary` 和 `TestMetrics`，计时用的 `TestTimer`，以及输出日志的 `TestLogger`。

### 使用方法

```kotlin
dependencies {
    compileOnly("net.legacy.library:foundation:1.0-SNAPSHOT")
}
```

### TestLogger 消息格式化

`TestLogger` 的每个方法都接收一条消息和可选的格式化参数。只有传入参数时，消息才会经过 `String.format`；不带参数时按原样输出。因此已经格式化过的消息、或带有异常信息之类文本的消息可以包含 `%`：

```java
// 原样输出：没有参数，"%" 不需要转义
TestLogger.logFailure("player", "Save failed: " + exception.getMessage());

// 会格式化：传入了参数
TestLogger.logInfo("player", "Saved %d of %d entities", saved, total);
```
