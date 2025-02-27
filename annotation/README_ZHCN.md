### 注解 (Annotation) 模块

本模块结合 [reflections](https://github.com/ronmamo/reflections) 库，实现了一个全自动的注解处理器。这对于重复执行某些针对类的操作非常有用。

### 用法

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))
}
```

### 核心概念

初看之下，整个流程似乎有些复杂。别担心，让我们化繁为简，先思考一个问题：`annotation` 的意义是什么？

想象一下，您是一位藏书爱好者，拥有琳琅满目的藏书。您喜欢在每本书的封面上贴上不同类型的贴纸，以便快速区分它们。

现在，您想找出所有贴有 “科幻小说” 贴纸的书籍。您可能会请一位助手来帮忙：

* 助手一： 负责浏览所有藏书，找出贴有“科幻小说”贴纸的书籍。这位助手就是 `AnnotationProcessingService`。
* 助手二： 负责对找到的书籍进行整理，比如把它们按照出版年份排序。这位助手就是 `CustomAnnotationProcessor`。

`AnnotationProcessingService` 会扫描大量的类（如同翻阅书籍），检查每个类上是否标注了它所关心的注解（如同贴纸）。
一旦发现目标注解，它就会将这个类提取出来，交给您指定的 `CustomAnnotationProcessor` 进行后续处理。

### 举个例子

例如，我们想获取所有带有 `SimplixSerializerSerializableAutoRegister` 注解的类，并对它们进行一些处理。

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimplixSerializerSerializableAutoRegister {
}
```

```java
@AnnotationProcessor(SimplixSerializerSerializableAutoRegister.class)
public class SimplixSerializerSerializableAutoRegisterProcessor implements CustomAnnotationProcessor {
    @Override
    public void process(Class<?> clazz) throws Exception {
        // 进行一些操作
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // 发生异常时
    }
}
```

我们定义了一个运行时注解，并定义了一个实现了 `CustomAnnotationProcessor` 接口并使用 `AnnotationProcessor` 注解的处理器。

这可能有点混乱，但我们慢慢来。

`SimplixSerializerSerializableAutoRegister` 注解用于标记我们需要处理的类。
`AnnotationProcessor` 注解需要指明该注解处理器专门用于处理某个注解。`CustomAnnotationProcessor` 接口用于确定处理方法。

这很简单，对吧？但是现在定义完成了，这一切是如何工作的呢？如果我们只想处理几个特定的注解呢？

```java
@FairyLaunch
public class Launcher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
                // 预期要处理的包
                "org.example",

                /*
                 * 要处理的注解的 Processor 所在的包
                 * 例如 "annotation.serialize.net.legacy.library.configuration.SimplixSerializerSerializableAutoRegister"
                 * 所以包名是 "net.legacy.library.configuration.serialize.annotation"
                 */
                "net.legacy.library.configuration.serialize.annotation"
        );

        annotationProcessingService.processAnnotations(
                basePackages,

                // 是否通过 Fairy IoC 进行 Processor 的依赖注入
                false,

                /*
                 * 用于 basePackages 扫描的所有 ClassLoader
                 * 这里，我们需要传入的不仅仅是当前类的 ClassLoader，
                 * 当我们需要使用 legacy-lands-library 自带的 Processor 时，还需要传入 Launcher 的 ClassLoader
                 */
                this.getClassLoader(),
                ConfigurationLauncher.class.getClassLoader() // configuration 模块的类加载器
                // 或者 CommonsLauncher.class.getClassLoader(), 这是 commons 模块的类加载器
        );
    }
}
```

让我们看看这个，我们只需要通过依赖注入获取 `AnnotationProcessingService`，然后调用 `processAnnotations`。

### ClassLoader?

为什么我们需要传入其他模块（如 `ConfigurationLauncher`）的 `ClassLoader`？
在插件化架构中，每个模块都如同一个独立的 “图书馆”。每个 “图书馆” 都有自己的 “馆藏”（即类）。

* `ClassLoader`：相当于 “图书馆” 的管理员，负责管理和加载类。
* 如果您只提供当前插件的 `ClassLoader`，`AnnotationProcessingService` 就只能在当前 “图书馆” 中查找。
* 要访问其他 “图书馆” 的 “馆藏”，您需要将它们的 `ClassLoader` 一并提供给 `AnnotationProcessingService`。

这样，`AnnotationProcessingService` 才能跨越 “图书馆” 的界限，找到所有符合条件的类，并将它们交给
`CustomAnnotationProcessor` 进行处理。
