### configuration

This module simply encapsulates [SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage) and provides
serialization annotations and factory mode for faster use. It handles **thread safety** internally, so it can be used
safely in a multi-threaded environment.

### usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly("me.qwqdev.library:annotation:1.0-SNAPSHOT")

    // configuration module
    compileOnly("me.qwqdev.library:configuration:1.0-SNAPSHOT")
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

The `SimplixSerializerSerializableAutoRegister` annotation will automatically register the serializer.

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
SimplixSerializer.serialize(plant).toString();
SimplixSerializer.deserialize(plantString, Plant .class);
```