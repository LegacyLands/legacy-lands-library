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
### 迁移已发布的配置文件

插件只会在文件不存在时写入一次默认文件，之后再也不会读取 jar 中的副本，而 Fairy 的 `loadAndSave()` 这类加载器会保留文件中已有的每个键，因此修改默认值对任何已经启动过一次的服务器都不会生效。`migration` 包可以在不触碰运营者任何改动的前提下交付这类修改，规则只有一条：与插件**曾经**在该路径发布过的某个值完全相同的值属于插件自己，可以升级；其他任何值都是运营者写的，原样保留并在日志中列出。

- `ShippedBaselines` 是所有已发布文件的并集。绝不能只与最新文件比较：旧版本生成且从未改动的文件会被整体误判为运营者的改动。
  `ShippedBaselines.ofHistory` 读取叶子为"该路径发布过的值列表"的历史文件，用于只追踪部分路径（例如只追踪文案）。
- `ConfigurationMigration` 依次执行：重命名 (`rename`)、按版本的步骤 (`step`)、退役路径（`retire` 保留被改动的值， `discard` 无论如何都删除并报告）、改写已发布的值、新增 (`add`)，最后写入版本戳。
- **新增遵循并集规则**：缺失的键没有任何指纹，因此只有从未出现在任何已发布文件中的路径才会被新增；任何已发布文件中有过的路径，其缺失可能是运营者有意删除，不会补回。
- **带版本戳或不带。** 设置 `versionKey` 后，已标记为 `version` 的文件不会再被改动；每次修改默认值都必须提高 `version`，并把被替换的文件加入 baselines，否则已标记的文件收不到修改。不提供 `current` 树时，已发布的值会被删除，由加载器写入新的默认值， Fairy 配置就是这样迁移的。不设置 `versionKey` 时必须提供 `current` 树，迁移在每次加载时都会（幂等地）执行；
  请传入新增记录文件，以免运营者删除的新增路径被再次加回。

`YamlMigrationUtil` 在插件的加载器读取之前用 SnakeYAML 迁移文件。它会保留没有值的键（`key:`，Bukkit 的加载器会删除它），把 `on:` 这类键读作文本而不是 `true`，从不覆盖已有的备份，通过临时文件写入，并且从不抛出异常。它不保留注释，适合自己生成注释的加载器（例如 Fairy）。`SimplixMigrationUtil` 通过 SimplixStorage 本身迁移 SimplixStorage 文件，保留其注释。

```java
public class MigrationExample {
    public void migrateMessages(Path dataFolder, ClassLoader classLoader) {
        ConfigurationMigration migration = ConfigurationMigration.builder()
                // 本插件发布过的每一个 messages.yml，包括最早的版本
                .baselines(YamlMigrationUtil.readBaselines(classLoader,
                        "migration/messages-1.yml", "migration/messages-2.yml"))
                .versionKey("schemaVersion")   // Fairy 配置类中的字段，保证版本戳在保存后仍然存在
                .version(3)
                .rename("shardsName", "moneyName")
                .discard("commandInfoLine")
                .build();

        // 在 loadAndSave() 之前执行：已发布的值被删除，由 Fairy 以新的默认值重新写入
        YamlMigrationUtil.migrate(dataFolder.resolve("messages.yml"), migration);
    }
}
```
