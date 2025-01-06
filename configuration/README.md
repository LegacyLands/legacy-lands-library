# 🔧 Configuration Module

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![SimplixStorage](https://img.shields.io/badge/SimplixStorage-3.2.7-orange.svg)](https://github.com/Simplix-Softworks/SimplixStorage)

A powerful configuration module that encapsulates [SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage) with additional features:
- ✨ Serialization annotations
- 🏭 Factory mode support
- 🔒 Built-in thread safety
- 🚀 Enhanced performance

## 📋 Contents

- [📦 Installation](#-installation)
- [🚀 Quick Start](#-quick-start)
- [💾 Serialization](#-serialization)
  - [Custom Serializer](#custom-serializer)
  - [Using SimplixSerializer](#using-simplixserializer)
- [🔗 Related Links](#-related-links)
- [📝 License](#-license)

## 📦 Installation

### Gradle Configuration

```kotlin
dependencies {
    // Required modules
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))
    compileOnly(files("libs/configuration-1.0-SNAPSHOT.jar"))
}
```

## 🚀 Quick Start

Here's a basic example of how to use the configuration module:

```java
public class Example extends Plugin {
    @Override
    public void onPluginEnable() {
        // Create a SimplixBuilder instance
        SimplixBuilder simplixBuilder = 
            SimplixBuilderFactory.createSimplixBuilder("example", "D:/");

        // Choose your preferred format (JSON/TOML/YAML)
        Yaml yaml = simplixBuilder.createYaml();

        // Thread-safe operations
        yaml.set("example", "test");
        
        // For more operations, visit:
        // https://github.com/Simplix-Softworks/SimplixStorage/wiki
    }
}
```

## 💾 Serialization

### Custom Serializer

Create a custom serializer by implementing `SimplixSerializable` and using the `@SimplixSerializerSerializableAutoRegister` annotation:

```java
@SimplixSerializerSerializableAutoRegister
public class PlantSerializable implements SimplixSerializable<Plant> {
    @Override
    public Plant deserialize(@NonNull Object object) throws ClassCastException {
        // Implement deserialization logic
        return plant;
    }

    @Override
    public Object serialize(@NonNull Plant plant) throws ClassCastException {
        // Implement serialization logic
        return serializedData;
    }

    @Override
    public Class<Plant> getClazz() {
        return Plant.class;
    }
}
```

### Using SimplixSerializer

Easily serialize and deserialize objects using the `SimplixSerializer`:

```java
// Serialization
String serialized = SimplixSerializer.serialize(plant).toString();

// Deserialization
Plant plant = SimplixSerializer.deserialize(plantString, Plant.class);
```

## 🌟 Key Features

- **Thread Safety**: Built-in thread safety mechanisms for reliable concurrent operations
- **Multiple Formats**: Support for JSON, YAML, and TOML configurations [1](https://github.com/Simplix-Softworks/SimplixStorage)
- **Auto-Registration**: Automatic serializer registration using annotations
- **Factory Pattern**: Simplified object creation through factory methods
- **Type Safety**: Strong typing support for configuration values

## 🔗 Related Links

- [SimplixStorage Documentation](https://github.com/Simplix-Softworks/SimplixStorage/wiki)
- [SimplixStorage GitHub Repository](https://github.com/Simplix-Softworks/SimplixStorage)

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

## ⚡ Performance

The module is optimized for performance with:
- Efficient thread synchronization
- Minimal overhead for serialization operations
- Smart caching mechanisms
