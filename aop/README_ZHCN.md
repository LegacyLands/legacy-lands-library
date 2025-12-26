### AOP 模块

企业级面向切面编程引擎，专为 Legacy Lands 插件生态打造。该模块提供全托管的跨领域能力——事务协调、安全审计、
输入校验、性能监控、异步安全与容错保护，同时保持插件间的 ClassLoader 隔离。代理生成结合 JDK 动态代理与
ByteBuddy 类代理，使接口服务与具体组件都能透明增强，无需修改业务代码。

### 依赖配置

```kotlin
// build.gradle.kts
dependencies {
    compileOnly("net.legacy.library:annotation:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:commons:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:foundation:1.0-SNAPSHOT")

    // AOP 模块
    compileOnly("net.legacy.library:aop:1.0-SNAPSHOT")
}
```

### 快速开始

```java
@FairyLaunch
public class AOPLauncher extends Plugin {

    @Autowired
    private AOPService aopService;

    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        annotationProcessingService.processAnnotations(
                List.of("net.legacy.library.aop"),
                false,
                getClassLoader(),
                AOPLauncher.class.getClassLoader()
        );

        aopService.setModuleConfiguration(AOPModuleConfiguration.enableAll());
        aopService.initialize();
    }
}
```

### 架构概述

AOP 模块采用混合代理管线，自动选择最优代理策略：

- **JDK 动态代理**：用于接口类型，提供标准代理行为
- **ByteBuddy 类代理**：用于具体类，通过同步复制语义保持字段状态
- **ClassLoader 隔离**：每个插件维护独立的切面元数据，防止跨插件干扰

### 代理创建流程

1. `AOPService` 接收需要代理的目标对象或类
2. `AspectProxyFactory` 决定代理类型（JDK 或 ByteBuddy）
3. 根据方法注解收集适用的拦截器
4. 按优先级排序拦截器并包装成执行链
5. 返回代理实例，准备进行透明方法拦截

### 内置切面

| 切面 | 注解 | 顺序 | 描述 |
|-----|------|-----|------|
| DynamicConfigAspect | `@DynamicConfig` | 10 | 运行时配置注入，支持热加载 |
| TracingAspect | `@Traced` | 30 | 分布式追踪与 Span 管理 |
| DistributedTransactionAspect | `@DistributedTransaction` | 50 | 两阶段提交协调 |
| RetryAspect | `@Retry` | 60 | 自动重试，可配置退避策略 |
| RateLimiterAspect | `@RateLimiter` | 65 | 多策略限流 |
| CircuitBreakerAspect | `@CircuitBreaker` | 70 | 状态机容错 |
| ValidationAspect | `@ValidInput` | 90 | 输入校验与自定义验证器 |
| SecurityAspect | `@Secured` | 100 | 认证、授权与审计 |
| MonitoringAspect | `@Monitored` | 100 | 指标收集与慢调用检测 |
| AsyncSafeAspect | `@AsyncSafe` | 200 | 线程亲和与重入保护 |
| LoggingAspect | `@Logged` | 300 | 模板化结构日志 |
| ExceptionWrapperAspect | `@ExceptionWrapper` | 400 | 异常规范化与映射 |

### 手动服务初始化

脱离 Fairy IoC 独立使用时，可手动创建 AOP 服务：

```java
public class ManualAOPSetup {
    public void setupAOP() {
        // 1. 创建核心服务
        ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
        AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

        // 2. 创建所需切面
        RetryAspect retryAspect = new RetryAspect();
        CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();
        RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

        // 3. 使用选定切面创建 AOP 服务
        AOPService aopService = new AOPService(
                proxyFactory,
                isolationService,
                null,  // DistributedTransactionAspect
                null,  // SecurityAspect
                circuitBreakerAspect,
                retryAspect,
                null,  // ValidationAspect
                null,  // TracingAspect
                null,  // MonitoringAspect
                null,  // AsyncSafeAspect
                null,  // LoggingAspect
                rateLimiterAspect,
                null   // DynamicConfigAspect
        );

        // 4. 初始化服务
        aopService.initialize();

        // 5. 创建代理实例
        AOPFactory aopFactory = new AOPFactory(aopService);
        MyService service = aopFactory.create(MyServiceImpl.class);
    }
}
```

## AOPFactory API

`AOPFactory` 提供创建 AOP 增强对象的便捷方法：

| 方法 | 描述 |
|------|------|
| `create(Class<T>)` | 创建具有 AOP 能力的新实例 |
| `create(Class<T>, List<MethodInterceptor>)` | 使用自定义拦截器创建实例 |
| `enhance(T)` | 为现有对象添加 AOP 能力 |
| `enhance(T, List<MethodInterceptor>)` | 使用自定义拦截器增强现有对象 |
| `createSingleton(Class<T>)` | 从容器创建或获取单例实例 |

### AOPService 关键方法

| 方法 | 描述 |
|------|------|
| `initialize()` | 初始化 AOP 服务并注册所有切面 |
| `registerTestInterceptors(Class<?>...)` | 为测试类注册拦截器 |
| `createProxy(T)` | 为对象创建代理 |
| `createProxy(T, List<MethodInterceptor>)` | 使用自定义拦截器创建代理 |
| `registerGlobalInterceptor(MethodInterceptor)` | 注册全局拦截器 |
| `getMonitoringMetrics()` | 获取监控指标映射 |
| `getRateLimiterMetrics()` | 获取限流器指标 |
| `getCircuitBreakerMetrics()` | 获取断路器指标 |
| `cleanupClassLoader(ClassLoader)` | 清理 ClassLoader 相关资源 |
| `shutdown()` | 执行完整关闭和清理 |

## 重试模式

`@Retry` 注解为瞬时故障提供自动重试，支持可配置的退避策略。

### 重试配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `maxAttempts` | 最大重试次数 | 3 |
| `initialDelay` | 初始延迟（毫秒） | 1000 |
| `maxDelay` | 最大延迟（毫秒） | 30000 |
| `backoffStrategy` | 退避策略（FIXED, LINEAR, EXPONENTIAL, EXPONENTIAL_JITTER, RANDOM） | EXPONENTIAL |
| `backoffMultiplier` | 指数退避乘数 | 2.0 |
| `jitterFactor` | EXPONENTIAL_JITTER 的抖动因子 | 0.1 |
| `retryOn` | 需要重试的异常类型（空 = 所有） | {} |
| `ignoreExceptions` | 忽略的异常类型（不重试） | {} |
| `fallbackMethod` | 降级方法名 | "" |
| `includeExceptionInFallback` | 降级时包含异常参数 | false |
| `propagateContext` | 跨重试传播上下文 | true |
| `timeout` | 每次尝试超时（0 = 无超时） | 0 |
| `maxAttemptsSupplier` | 动态最大次数提供者方法 | "" |

### 退避策略

- **FIXED**：重试间隔固定
- **LINEAR**：线性递增延迟
- **EXPONENTIAL**：指数递增延迟，可配置乘数
- **EXPONENTIAL_JITTER**：指数延迟加随机抖动，防止惊群效应
- **RANDOM**：指定范围内的随机延迟

### 基础重试用法

```java
public interface RetryService {

    @Retry
    String flakyOperation(String input) throws Exception;

    @Retry(
            maxAttempts = 5,
            initialDelay = 500,
            backoffMultiplier = 1.5
    )
    String eventuallySuccessfulOperation(String input) throws Exception;

    @Retry(
            maxAttempts = 2,
            initialDelay = 100
    )
    void alwaysFailingOperation(String input) throws Exception;

    @Retry(
            initialDelay = 200,
            fallbackMethod = "fallbackOperation",
            includeExceptionInFallback = true
    )
    String failingWithFallback(String input);

    @Retry(
            maxAttempts = 2,
            initialDelay = 100,
            fallbackMethod = "asyncFallback",
            includeExceptionInFallback = true
    )
    CompletableFuture<String> asyncOperation(String input);

}

public class TestRetryService implements RetryService {

    private final AtomicInteger flakyCounter = new AtomicInteger(0);
    private final AtomicInteger eventuallyCounter = new AtomicInteger(0);

    @Override
    @Retry
    public String flakyOperation(String input) throws Exception {
        int attempt = flakyCounter.incrementAndGet();

        if (attempt <= 2) {
            throw new RuntimeException("Flaky operation failed on attempt #" + attempt);
        }

        return "Flaky operation succeeded: " + input + " (Attempt #" + attempt + ")";
    }

    @Override
    @Retry(
            maxAttempts = 5,
            initialDelay = 500,
            backoffMultiplier = 1.5
    )
    public String eventuallySuccessfulOperation(String input) throws Exception {
        int attempt = eventuallyCounter.incrementAndGet();

        if (attempt <= 4) {
            throw new RuntimeException("Eventually successful failed on attempt #" + attempt);
        }

        return "Eventually succeeded: " + input + " (Attempt #" + attempt + ")";
    }

    @Override
    @Retry(
            maxAttempts = 2,
            initialDelay = 100
    )
    public void alwaysFailingOperation(String input) throws Exception {
        throw new RuntimeException("This operation always fails: " + input);
    }

    @Override
    @Retry(
            initialDelay = 200,
            fallbackMethod = "fallbackOperation",
            includeExceptionInFallback = true
    )
    public String failingWithFallback(String input) {
        throw new IllegalStateException("Primary operation failed for " + input);
    }

    public String fallbackOperation(String input, Throwable throwable) {
        return "Fallback invoked for " + input + " due to " + throwable.getClass().getSimpleName();
    }

    @Override
    @Retry(
            maxAttempts = 2,
            initialDelay = 100,
            fallbackMethod = "asyncFallback",
            includeExceptionInFallback = true
    )
    public CompletableFuture<String> asyncOperation(String input) {
        CompletableFuture<String> failed = new CompletableFuture<>();
        failed.completeExceptionally(new IllegalStateException("Async failure for " + input));
        return failed;
    }

    public CompletableFuture<String> asyncFallback(String input, Throwable throwable) {
        return CompletableFuture.completedFuture(
                "Async fallback for " + input + " after " + throwable.getClass().getSimpleName()
        );
    }

}
```

### 重试测试示例

```java
public class RetryExample {
    public void testRetry() {
        ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
        AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
        RetryAspect retryAspect = new RetryAspect();

        AOPService aopService = new AOPService(
                proxyFactory,
                isolationService,
                null, null, null,
                retryAspect,
                null, null
        );

        aopService.initialize();
        aopService.registerTestInterceptors(TestRetryService.class);

        AOPFactory aopFactory = new AOPFactory(aopService);
        RetryService service = aopFactory.create(TestRetryService.class);

        // 前两次失败，第三次成功
        String result = service.flakyOperation("retry-test");
        // result: "Flaky operation succeeded: retry-test (Attempt #3)"

        // 失败后使用 fallback
        String fallbackResult = service.failingWithFallback("fallback");
        // fallbackResult: "Fallback invoked for fallback due to IllegalStateException"
    }
}
```

## 断路器模式

`@CircuitBreaker` 注解实现断路器模式，提供容错保护。

### 断路器配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `failureRateThreshold` | 失败率阈值（0.0-1.0） | 0.5 |
| `failureCountThreshold` | 触发跳闸的失败次数 | 5 |
| `minimumNumberOfCalls` | 评估前的最小调用次数 | 10 |
| `slidingWindowSize` | 滑动窗口大小（毫秒） | 60000 |
| `waitDurationInOpenState` | 开启状态等待时间（毫秒） | 30000 |
| `openDurationSupplier` | 动态开启时间提供者 | "" |
| `permittedNumberOfCallsInHalfOpenState` | 半开状态允许的调用数 | 3 |
| `timeoutDuration` | 调用超时时间（0 = 无超时） | 0 |
| `fallbackMethod` | 降级方法名 | "" |
| `recordFailurePredicate` | 记录为失败的异常类型 | {} |
| `ignoreExceptions` | 忽略的异常类型 | {} |
| `automaticTransitionFromOpenToHalfOpen` | 自动转换到半开状态 | true |
| `enableMetrics` | 启用指标收集 | true |
| `name` | 用于指标的断路器名称 | "" |

### 断路器状态

- **CLOSED**：正常运行，所有调用放行
- **OPEN**：断路器跳闸，调用被阻断并触发 fallback
- **HALF_OPEN**：测试状态，允许有限调用探测恢复

### 断路器配置

```java
public interface CircuitBreakerService {

    @CircuitBreaker(
            failureCountThreshold = 3,
            waitDurationInOpenState = 5000,
            minimumNumberOfCalls = 5
    )
    String riskyOperation(String input) throws Exception;

    @CircuitBreaker(
            failureCountThreshold = 2,
            waitDurationInOpenState = 3000,
            minimumNumberOfCalls = 3
    )
    String anotherRiskyOperation(String input) throws Exception;

}

public class TestCircuitBreakerService implements CircuitBreakerService {

    private final AtomicInteger callCounter = new AtomicInteger(0);
    private volatile boolean circuitShouldFail = true;

    @Override
    @CircuitBreaker(
            failureCountThreshold = 3,
            waitDurationInOpenState = 5000,
            minimumNumberOfCalls = 5
    )
    public String riskyOperation(String input) throws Exception {
        int currentCall = callCounter.incrementAndGet();

        // 模拟断路器测试的失败场景
        // 前 6 次调用失败，确保断路器开启（需要 5 次最小调用且 3 次失败）
        // 断路器恢复后调用应成功
        if (circuitShouldFail && currentCall <= 6) {
            throw new RuntimeException("Simulated failure #" + currentCall);
        }

        return "Success: " + input + " (Call #" + currentCall + ")";
    }

    /**
     * 重置断路器失败模拟，用于恢复测试。
     */
    public void resetForRecovery() {
        this.circuitShouldFail = false;
        this.callCounter.set(0);
    }

    @Override
    @CircuitBreaker(
            failureCountThreshold = 2,
            waitDurationInOpenState = 3000,
            minimumNumberOfCalls = 3
    )
    public String anotherRiskyOperation(String input) throws Exception {
        if (input.contains("fail")) {
            throw new RuntimeException("Deliberate failure for: " + input);
        }
        return "Alternative success: " + input;
    }

}
```

### 断路器测试示例

```java
public class CircuitBreakerExample {
    public void testCircuitBreaker() {
        ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
        AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
        CircuitBreakerAspect circuitBreakerAspect = new CircuitBreakerAspect();

        AOPService aopService = new AOPService(
                proxyFactory,
                isolationService,
                null, null,
                circuitBreakerAspect,
                null, null, null
        );

        aopService.initialize();

        AOPFactory aopFactory = new AOPFactory(aopService);
        CircuitBreakerService service = aopFactory.create(TestCircuitBreakerService.class);

        int failureCount = 0;
        boolean openStateObserved = false;

        for (int i = 0; i < 10; i++) {
            try {
                String result = service.riskyOperation("test-" + i);
                System.out.println("Call #" + (i + 1) + " result: " + result);
            } catch (Exception exception) {
                failureCount++;
                if (exception.getMessage().contains("Circuit breaker is OPEN")) {
                    openStateObserved = true;
                }
                System.out.println("Call #" + (i + 1) + " failed: " + exception.getMessage());
            }
        }

        // 足够失败后断路器应开启
        // failureCount >= 3 && openStateObserved == true
    }
}
```

## 限流模式

`@RateLimiter` 注解提供多策略限流。

### 限流配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `limit` | 时间周期内允许的最大请求数（必需） | - |
| `period` | 时间周期（毫秒，必需） | - |
| `strategy` | 限流策略（FIXED_WINDOW, SLIDING_WINDOW, TOKEN_BUCKET, LEAKY_BUCKET） | FIXED_WINDOW |
| `keyExpression` | 按键限流的表达式（如 `{#arg0}`） | "" |
| `fallbackMethod` | 被限流时调用的降级方法名 | "" |
| `waitForNextSlot` | 是否阻塞等待下一个可用窗口 | false |
| `maxWaitTime` | 最大等待时间（毫秒） | 5000 |
| `distributed` | 启用分布式限流 | false |
| `distributedLockTimeout` | 分布式锁超时时间（毫秒） | 5000 |
| `name` | 用于指标的限流器名称 | "" |

### 限流策略

- **FIXED_WINDOW**：固定时间窗口计数器
- **SLIDING_WINDOW**：滑动时间窗口，更平滑的限流
- **TOKEN_BUCKET**：令牌桶算法，支持突发流量
- **LEAKY_BUCKET**：漏桶算法，恒定速率输出

### 限流配置

```java
public interface RateLimitService {

    @RateLimiter(limit = 5, period = 1000)
    String fixedWindowOperation(String input);

    @RateLimiter(
            limit = 2,
            period = 1000,
            keyExpression = "{#arg0}"
    )
    String perUserOperation(String userId, String data);

    @RateLimiter(
            limit = 2,
            period = 1000,
            fallbackMethod = "fallbackOperation"
    )
    String operationWithFallback(String input);

}

public class TestRateLimitService implements RateLimitService {

    @Override
    @RateLimiter(limit = 5, period = 1000)
    public String fixedWindowOperation(String input) {
        return "Fixed window result: " + input;
    }

    @Override
    @RateLimiter(
            limit = 2,
            period = 1000,
            keyExpression = "{#arg0}"
    )
    public String perUserOperation(String userId, String data) {
        return "Per user result for " + userId + ": " + data;
    }

    @Override
    @RateLimiter(
            limit = 2,
            period = 1000,
            fallbackMethod = "fallbackOperation"
    )
    public String operationWithFallback(String input) {
        return "Primary result: " + input;
    }

    public String fallbackOperation(String input) {
        return "Fallback result: " + input;
    }

}
```

### 限流测试示例

```java
public class RateLimiterExample {
    public void testRateLimiter() {
        ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
        AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);
        RateLimiterAspect rateLimiterAspect = new RateLimiterAspect();

        AOPService aopService = new AOPService(
                proxyFactory,
                isolationService,
                null, null, null, null, null, null, null, null, null,
                rateLimiterAspect,
                null
        );

        aopService.initialize();
        aopService.registerTestInterceptors(TestRateLimitService.class);

        AOPFactory aopFactory = new AOPFactory(aopService);
        RateLimitService service = aopFactory.create(TestRateLimitService.class);

        AtomicInteger successCount = new AtomicInteger(0);
        AtomicInteger failCount = new AtomicInteger(0);

        // 执行 10 次调用，限制每秒 5 次
        for (int i = 0; i < 10; i++) {
            try {
                service.fixedWindowOperation("test-" + i);
                successCount.incrementAndGet();
            } catch (Exception exception) {
                failCount.incrementAndGet();
            }
        }

        // successCount == 5, failCount == 5

        // 按用户限流
        AtomicInteger userASuccess = new AtomicInteger(0);
        AtomicInteger userBSuccess = new AtomicInteger(0);

        // 用户 A 调用 3 次（限制 2 次）
        for (int i = 0; i < 3; i++) {
            try {
                service.perUserOperation("userA", "data-" + i);
                userASuccess.incrementAndGet();
            } catch (Exception exception) {
                // 被限流
            }
        }

        // 用户 B 调用 3 次（独立限制）
        for (int i = 0; i < 3; i++) {
            try {
                service.perUserOperation("userB", "data-" + i);
                userBSuccess.incrementAndGet();
            } catch (Exception exception) {
                // 被限流
            }
        }

        // userASuccess == 2, userBSuccess == 2
    }
}
```

## 动态配置

`@DynamicConfig` 注解支持运行时配置注入与热加载。

### 动态配置示例

```java
public interface ConfigService {
    String getConfiguration();
}

public class TestConfigService implements ConfigService {

    @DynamicConfig(
            key = "app.timeout",
            defaultValue = "30",
            description = "Timeout in seconds"
    )
    private int timeout;

    @DynamicConfig(
            key = "app.maxRetries",
            defaultValue = "3",
            description = "Maximum retry attempts"
    )
    private int maxRetries;

    @DynamicConfig(
            key = "app.enabled",
            defaultValue = "true",
            description = "Feature enabled flag"
    )
    private boolean enabled;

    @Override
    public String getConfiguration() {
        return "timeout=" + timeout + ", maxRetries=" + maxRetries + ", enabled=" + enabled;
    }

}

public interface SystemPropertyConfigService {
    int getTimeout();
}

public class TestSystemPropertyConfigService implements SystemPropertyConfigService {

    @DynamicConfig(
            key = "test.custom.timeout",
            defaultValue = "10",
            source = "system"
    )
    private int customTimeout;

    @Override
    public int getTimeout() {
        return customTimeout;
    }

}
```

### 动态配置测试示例

```java
public class DynamicConfigExample {
    public void testDynamicConfig() {
        ClassLoaderIsolationService isolationService = new ClassLoaderIsolationService();
        AspectProxyFactory proxyFactory = new AspectProxyFactory(isolationService);

        DefaultConfigurationService configService = new DefaultConfigurationService();
        DynamicConfigAspect dynamicConfigAspect = new DynamicConfigAspect(configService);

        AOPService aopService = new AOPService(
                proxyFactory,
                isolationService,
                null, null, null, null, null, null, null, null, null, null,
                dynamicConfigAspect
        );

        aopService.initialize();
        aopService.registerTestInterceptors(TestConfigService.class);

        AOPFactory aopFactory = new AOPFactory(aopService);
        ConfigService service = aopFactory.create(TestConfigService.class);

        String result = service.getConfiguration();
        // result: "timeout=30, maxRetries=3, enabled=true"

        // 验证元数据已注册
        var metadata = configService.getMetadata("app.timeout");
        // metadata.getDefaultValue() == "30"
        // metadata.getDescription() == "Timeout in seconds"
    }
}
```

### 动态配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `key` | 配置键或路径（支持点号分隔） | "" |
| `defaultValue` | 配置未找到时的默认值 | "" |
| `source` | 使用的配置源 | "" |
| `required` | 配置是否必需（缺失时失败） | false |
| `refreshInterval` | 刷新间隔（毫秒，0 = 不刷新） | 0 |
| `cache` | 是否缓存配置值 | true |
| `profiles` | 适用的环境配置文件 | {} |
| `validation` | 验证规则（如 "min=1", "max=100", "regex=.*"） | {} |
| `version` | 用于回滚的配置版本 | "" |
| `watch` | 是否监听配置变更 | false |
| `onChangeCallback` | 配置变更时的回调方法名 | "" |
| `description` | 用于文档的配置描述 | "" |

## 监控与可观测性

### MonitoringAspect

通过 `MethodMetrics` 捕获调用次数、百分位延迟、失败率。

#### 监控配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `name` | 指标名称（必需） | - |
| `warnThreshold` | 警告阈值（毫秒） | 1000 |
| `includeArgs` | 在监控数据中包含方法参数 | false |

```java
public interface MonitoredService {

    @Monitored(name = "performOperation", warnThreshold = 100)
    String performOperation(String input);

}
```

通过 `AOPService#getMonitoringMetrics()` 访问指标，使用 `clearMonitoringMetrics()` 重置。

### TracingAspect

创建嵌套 Span，支持采样、标签注入和异步延续。

#### 追踪配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `operationName` | 自定义 Span 操作名称 | "" |
| `serviceName` | 追踪的服务名称 | "" |
| `includeParameters` | 在 Span 中包含方法参数 | true |
| `maxParameterSize` | 参数记录的最大长度 | 100 |
| `includeReturnValue` | 在 Span 中包含返回值 | false |
| `forceNewSpan` | 强制创建新 Span | false |
| `samplingRate` | 采样率（0.0-1.0，-1 = 使用默认） | -1.0 |
| `alwaysTrace` | 始终追踪（覆盖采样） | false |
| `tags` | Span 的附加标签 | {} |
| `includeStackTraces` | 错误时包含堆栈跟踪 | true |
| `maxNestingDepth` | 最大嵌套深度 | 10 |

```java
public interface TracedService {

    @Traced(
            operationName = "processOrder",
            includeReturnValue = true,
            samplingRate = 0.5
    )
    Order processOrder(OrderRequest request);

}
```

### LoggingAspect

格式化结构化入口/出口日志，支持参数/结果输出。

#### 日志配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `level` | 日志级别（TRACE, DEBUG, INFO, WARN, ERROR） | DEBUG |
| `includeArgs` | 在日志中包含方法参数 | false |
| `includeResult` | 在日志中包含返回值 | false |
| `format` | 自定义日志格式模板（支持 {method}, {args}, {result}, {duration}） | "" |

```java
public interface LoggedService {

    @Logged(
            level = Logged.LogLevel.INFO,
            includeArgs = true,
            includeResult = true,
            format = "Processing: {}"
    )
    Result process(Input input);

}
```

## 安全与校验

### SecurityAspect

集成 `SecurityProvider` 实现认证、RBAC 和审计追踪：

```java
public interface SecuredService {

    @Secured(
            authenticated = true,
            roles = {"ADMIN", "MODERATOR"},
            permissions = {"user:delete"},
            audit = true
    )
    void deleteUser(UUID userId);

    @Secured(
            authenticated = true,
            provider = "oauth2",
            rateLimited = true,
            maxRequests = 100,
            timeWindow = 60
    )
    void sensitiveOperation();

}
```

### 安全配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `authenticated` | 是否需要认证 | true |
| `roles` | 访问此方法所需的角色 | {} |
| `permissions` | 访问此方法所需的权限 | {} |
| `provider` | 安全提供者名称 | "default" |
| `audit` | 是否启用审计日志 | false |
| `rateLimited` | 是否启用速率限制 | false |
| `maxRequests` | 时间窗口内的最大请求数 | 100 |
| `timeWindow` | 速率限制的时间窗口（秒） | 60 |
| `csrfProtected` | 是否启用 CSRF 保护 | false |
| `validateInput` | 是否验证输入参数 | true |
| `policy` | 自定义安全策略类 | SecurityPolicy.class |
| `onAuthorizationFailure` | 授权失败时抛出的异常 | SecurityException.class |

### ValidationAspect

使用自定义验证器强制输入校验。`@ValidInput` 注解放置在方法参数上。

#### 输入校验配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `required` | 参数是否必填（非 null） | false |
| `minLength` | 字符串最小长度 | 0 |
| `maxLength` | 字符串最大长度 | Integer.MAX_VALUE |
| `min` | 数值最小值 | Double.MIN_VALUE |
| `max` | 数值最大值 | Double.MAX_VALUE |
| `pattern` | 验证用的正则表达式模式 | "" |
| `validator` | 自定义验证器类 | InputValidator.class |
| `message` | 验证失败消息 | "Invalid input value" |
| `onValidationFailure` | 验证失败时抛出的异常类型 | IllegalArgumentException.class |

```java
public interface ValidatedService {

    void createUser(
            @ValidInput(required = true, minLength = 3, maxLength = 50) String name,
            @ValidInput(min = 0, max = 150) int age
    );

    String validateEmail(
            @ValidInput(required = true, pattern = "^[A-Za-z0-9+_.-]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}$") String email
    );

}
```

## 异步执行策略

`@AsyncSafe` 切面协调同步/异步调度。

### 异步配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `target` | 目标线程类型（SYNC, ASYNC, VIRTUAL, CUSTOM） | SYNC |
| `timeout` | 执行超时时间（毫秒） | 30000 |
| `allowReentrant` | 允许同一线程重入 | false |
| `customExecutor` | 自定义执行器名称（用于 CUSTOM 类型） | "" |
| `customLockStrategy` | 自定义锁策略名称 | "" |
| `customTimeoutHandler` | 自定义超时处理器名称 | "" |
| `customProperties` | 自定义策略的附加属性 | {} |

```java
public interface AsyncService {

    @AsyncSafe(
            target = AsyncSafe.ThreadType.ASYNC,
            allowReentrant = false,
            timeout = 5000
    )
    CompletableFuture<Result> asyncProcess(Request request);

    @AsyncSafe(target = AsyncSafe.ThreadType.VIRTUAL)
    CompletableFuture<Result> virtualThreadProcess(Request request);

}
```

### 自定义执行策略

`@AsyncSafe` 注解通过 `ThreadType.CUSTOM` 模式支持自定义执行策略：

```java
public class CustomAsyncService {

    @AsyncSafe(
            target = AsyncSafe.ThreadType.CUSTOM,
            customExecutor = "my-executor",
            customLockStrategy = "my-lock",
            customTimeoutHandler = "my-timeout-handler",
            timeout = 5000,
            customProperties = {"threadName=CustomThread", "lockTimeout=3000"}
    )
    public String customOperation() {
        return "Executed with custom strategy";
    }

}
```

通过 `CustomExecutorRegistry` 注册自定义组件：

```java
public class CustomComponentSetup {
    public void registerComponents() {
        CustomExecutorRegistry registry = CustomExecutorRegistry.getInstance();

        // 注册自定义执行器
        registry.registerExecutor(new CustomExecutor() {
            @Override
            public String getName() { return "my-executor"; }

            @Override
            public Object execute(AspectContext context, MethodInvocation invocation,
                                  Properties properties) throws Throwable {
                return CompletableFuture.supplyAsync(() -> {
                    try {
                        return invocation.proceed();
                    } catch (Throwable t) {
                        throw new RuntimeException(t);
                    }
                });
            }
        });

        // 注册自定义锁策略
        registry.registerLockStrategy(new CustomLockStrategy() {
            @Override
            public String getName() { return "my-lock"; }

            @Override
            public <T> T executeWithLock(AspectContext context, Callable<T> operation,
                                         Properties properties) throws Exception {
                // 自定义锁逻辑
                return operation.call();
            }

            @Override
            public boolean isReentrant(AspectContext context) { return false; }
        });

        // 注册自定义超时处理器
        registry.registerTimeoutHandler(new CustomTimeoutHandler() {
            @Override
            public String getName() { return "my-timeout-handler"; }

            @Override
            public Object handleTimeout(AspectContext context, CompletableFuture<?> future,
                                        long timeout, Properties properties) throws Throwable {
                future.cancel(true);
                String fallback = properties.getProperty("fallbackValue");
                if (fallback != null) return fallback;
                throw new TimeoutException("Timed out after " + timeout + "ms");
            }
        });
    }
}
```

## 分布式事务

`@DistributedTransaction` 注解为企业环境提供两阶段提交协调：

```java
public interface TransactionalService {

    @DistributedTransaction(
            timeout = 5000,
            isolation = DistributedTransaction.Isolation.READ_COMMITTED,
            propagation = DistributedTransaction.Propagation.REQUIRED
    )
    String performTransactionalOperation(String data) throws Exception;

    @DistributedTransaction(
            timeout = 3000,
            readOnly = true,
            isolation = DistributedTransaction.Isolation.READ_COMMITTED
    )
    String performReadOnlyOperation(String id);

    @DistributedTransaction(
            rollbackFor = {IllegalStateException.class, IOException.class},
            noRollbackFor = {ValidationException.class}
    )
    void complexOperation() throws Exception;

}
```

### 事务配置选项

| 属性 | 描述 | 默认值 |
|-----|------|-------|
| `propagation` | 事务传播行为（REQUIRED、REQUIRES_NEW、NESTED 等） | REQUIRED |
| `isolation` | 隔离级别（DEFAULT、READ_UNCOMMITTED、READ_COMMITTED、REPEATABLE_READ、SERIALIZABLE） | DEFAULT |
| `timeout` | 事务超时时间（秒） | 30 |
| `readOnly` | 是否为只读事务 | false |
| `rollbackFor` | 触发回滚的异常类型 | {} |
| `noRollbackFor` | 不触发回滚的异常类型 | {} |
| `name` | 用于监控的事务名称 | "" |
| `enableTracing` | 启用分布式追踪 | true |

## Pointcut 表达式

AOP 模块支持 AspectJ 风格的切点表达式，实现灵活的方法匹配：

```java
public class PointcutExample {
    public void configurePointcuts() {
        PointcutExpressionParser parser = new PointcutExpressionParser();

        // 匹配 *Service 类中的所有方法
        Pointcut servicePointcut = parser.parse("execution(* net.legacy.library..*Service.*(..))");

        // 匹配特定包内的方法
        Pointcut withinPointcut = parser.parse("within(net.legacy.library.cache..*)");

        // 匹配带有特定注解的方法
        Pointcut annotationPointcut = parser.parse("@annotation(net.legacy.library.aop.annotation.Logged)");

        // 组合切点（AND/OR）
        Pointcut composite = parser.parse(
                "execution(* *Service.*(..)) && @annotation(net.legacy.library.aop.annotation.Monitored)"
        );
    }
}
```

### 自定义切点拦截器

```java
public class LoggingPointcutInterceptor extends PointcutMethodInterceptor {

    public LoggingPointcutInterceptor() {
        super("execution(* net.legacy.library..*Service.*(..))");
    }

    @Override
    public Object intercept(AspectContext context, MethodInvocation invocation) throws Throwable {
        Log.info("Entering: %s", context.getMethod().getName());
        Object result = invocation.proceed();
        Log.info("Exiting: %s", context.getMethod().getName());
        return result;
    }

}
```

## 异常处理

`@ExceptionWrapper` 切面规范化异常类型。

### 异常包装配置选项

| 属性 | 描述 | 默认值 |
|------|------|--------|
| `wrapWith` | 用于包装的目标异常类（必需） | - |
| `message` | 错误消息（支持 {method}, {args}, {original} 占位符） | "Method execution failed" |
| `logOriginal` | 在包装前记录原始异常 | true |
| `exclude` | 不进行包装的异常类型 | {} |

```java
public interface WrappedService {

    @ExceptionWrapper(
            wrapWith = ServiceException.class,
            message = "Service operation failed",
            logOriginal = true
    )
    void riskyOperation() throws ServiceException;

}
```

## 配置管理

### AOPModuleConfiguration

运行时切换各切面开关：

```java
public class ConfigurationExample {
    public void configureAOP(AOPService aopService) {
        // 启用所有切面
        aopService.setModuleConfiguration(AOPModuleConfiguration.enableAll());

        // 或单独配置
        AOPModuleConfiguration config = AOPModuleConfiguration.builder()
                .retryEnabled(true)
                .faultToleranceEnabled(true)  // 启用 CircuitBreaker
                .rateLimiterEnabled(true)
                .dynamicConfigEnabled(true)
                .monitoringEnabled(false)  // 禁用监控
                .tracingEnabled(false)     // 禁用追踪
                .debugMode(true)
                .build();

        aopService.setModuleConfiguration(config);
        aopService.initialize();
    }
}
```

## ClassLoader 隔离与生命周期

每个插件维护独立的切面元数据。关闭时正确清理至关重要：

```java
public class LifecycleExample {
    public void onPluginDisable(AOPService aopService) {
        // 方式 1：仅清理 ClassLoader 相关资源
        aopService.cleanupClassLoader(getClassLoader());

        // 方式 2：完全关闭（当 AOPService 实例不再需要时使用）
        // 这会关闭所有执行器、清空所有缓存并释放所有资源
        aopService.shutdown();
    }
}
```

### 关闭行为

`shutdown()` 方法执行完整清理：
- 关闭 AsyncSafeAspect 线程池
- 关闭 RetryAspect 调度器
- 清空所有 RateLimiter 状态
- 清空所有 CircuitBreaker 状态
- 清空 DynamicConfig 缓存
- 清空监控指标
- 移除所有已注册的拦截器

## 最佳实践

1. **顺序很重要**：切面按顺序执行。DynamicConfig (10) 最先执行，确保配置对其他切面可用。

2. **提供 Fallback 方法**：为关键操作的 `@Retry`、`@CircuitBreaker` 和 `@RateLimiter` 提供 fallback 方法。

3. **限流键表达式**：使用 `{#arg0}`、`{#arg1}` 进行基于参数的限流键，避免依赖参数名。

4. **断路器调优**：合理设置 `minimumNumberOfCalls` 避免断路器过早开启。

5. **关闭时清理**：插件关闭时调用 `cleanupClassLoader()` 防止内存泄漏。

6. **指标监控**：使用 `getMonitoringMetrics()`、`getRateLimiterMetrics()` 和 `getCircuitBreakerMetrics()` 进行可观测性。
