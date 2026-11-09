### configuration

This module simply encapsulates [SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage) and provides
serialization annotations and factory mode for faster use. It handles thread safety internally, so it can be used
safely in a multithreaded environment.

### usage

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

        // do something eg:
        yaml.set("example", "test"); // thread safety

        // more about SimplixStorage: https://github.com/Simplix-Softworks/SimplixStorage/wiki
    }
}
```

We recommend using `SimplixSerializer` for serialization and deserialization, which can be implemented internally using
`Gson`.

The `SimplixSerializerSerializableAutoRegister` annotation will automatically register the serializer, needs to be used with `annotation`.

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
        return Plant.class; // return the class of the object
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
### Migrating Shipped Files

A plugin writes its default file once and never looks at the shipped copy again, and a loader such as Fairy's `loadAndSave()` keeps every key the file already has, so a changed default reaches no server that has started once.
The `migration` package delivers such changes without touching anything the operator wrote, by one rule: a value equal to one the plugin has **ever** shipped at that path is the plugin's own and is upgraded; any other value is the operator's and is kept, and named in the log.

- `ShippedBaselines` is the union of every shipped file. Never compare against the newest file alone: an untouched file from an older release would read as wall-to-wall edits. `ShippedBaselines.ofHistory` reads a file whose leaves list the values shipped at each path, for tracking only some paths (such as only the wording).
- `ConfigurationMigration` applies, in order: renames (`rename`), versioned steps (`step`), retired paths (`retire` keeps an edited value, `discard` drops it and reports it), rewrites of shipped values, additions (`add`) and the version stamp.
- **Additions follow the union rule**: a missing key has no fingerprint, so a path is added only if no shipped file ever had it; a path any shipped file had may have been deleted on purpose and is left out.
- **Stamped or not.** With `versionKey`, a file stamped at `version` is never touched, and every change of a default must raise `version` and add the replaced file to the baselines, or it reaches no file already stamped. Without a `current` tree, a shipped value is removed so the loader writes its new default, which is how a Fairy configuration is migrated. Without `versionKey`, a `current` tree is required and the migration runs, idempotently, on every load; pass an insertion record so an added path the operator deletes is not added again.

`YamlMigrationUtil` migrates a file with SnakeYAML before the plugin's loader reads it. It keeps a key written with nothing after it (`key:`, which Bukkit's loader deletes), reads keys such as `on:` as text rather than `true`, never replaces an earlier backup, writes through a temporary file, and never throws. It does not keep comments, which suits a loader that writes its own, such as Fairy's. `SimplixMigrationUtil` migrates a SimplixStorage file through SimplixStorage itself, keeping its comments.

```java
public class MigrationExample {
    public void migrateMessages(Path dataFolder, ClassLoader classLoader) {
        ConfigurationMigration migration = ConfigurationMigration.builder()
                // Every messages.yml this plugin has shipped, oldest included
                .baselines(YamlMigrationUtil.readBaselines(classLoader,
                        "migration/messages-1.yml", "migration/messages-2.yml"))
                .versionKey("schemaVersion")   // a field of the Fairy configuration, so the stamp survives saves
                .version(3)
                .rename("shardsName", "moneyName")
                .discard("commandInfoLine")
                .build();

        // Run before loadAndSave(): shipped values are removed and rewritten by Fairy with the new defaults
        YamlMigrationUtil.migrate(dataFolder.resolve("messages.yml"), migration);
    }
}
```
