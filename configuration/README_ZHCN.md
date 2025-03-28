### 配置 (Configuration) 模块

本模块简单地封装了 [SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage)
，并提供了序列化注解和工厂模式，以便更快地使用。它在内部处理线程安全，因此可以在多线程环境中安全使用。

### 用法

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))

    // configuration module
    compileOnly(files("libs/configuration-1.0-SNAPSHOT.jar"))
}
```

```java
public class Example extends Plugin {
    @Override
    public void onPluginEnable() {
        SimplixBuilder simplixBuilder =
                SimplixBuilderFactory.createSimplixBuilder("example", "D:/");

        Yaml yaml = simplixBuilder.createYaml(); // json / toml / yaml

        // 进行一些操作，例如：
        yaml.set("example", "test"); // 线程安全

        // 更多关于 SimplixStorage 的信息：https://github.com/Simplix-Softworks/SimplixStorage/wiki
    }
}
```

我们建议使用 `SimplixSerializer` 进行序列化和反序列化，它可以在内部使用 `Gson` 实现。

`SimplixSerializerSerializableAutoRegister` 注解将自动注册序列化器，需要搭配 `annotation` 使用。

```java
@SimplixSerializerSerializableAutoRegister
public class PlantSerializable implements SimplixSerializable<Plant> {
    @Override
    public Plant deserialize(@NonNull Object object) throws ClassCastException {
        // deserialize
    }

    @Override
    public Object serialize(@NonNull Plant plant) throws ClassCastException {
        // serialize
    }

    @Override
    public Class<Plant> getClazz() {
        return Plant.class; // 返回对象的类
    }
}
```

```java
public class Example {
    public static void main(String[] args) {
        SimplixSerializer.serialize(plant).toString();
        SimplixSerializer.deserialize(plantString, Plant.class);
    }
}
```