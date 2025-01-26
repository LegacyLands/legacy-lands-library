# 🔧 Configuration Framework

A flexible configuration framework built on top of [SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage), providing thread-safe configuration management with automatic serialization support.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🏭 **Factory-Based Builder System**
  - Simplified builder creation via `SimplixBuilderFactory`
  - Pre-configured settings for common use cases
  - Support for file, directory, and path-based configurations
  - Automatic reload capabilities

- 📦 **Serialization Framework**
  - Automatic serializer registration via `@SimplixSerializerSerializableAutoRegister`
  - Integration with annotation processing system
  - Thread-safe serialization operations
  - Support for custom data types

- 🔄 **I/O Operations**
  - Comprehensive file operations through `IOUtil`
  - Thread-safe file reading and writing
  - Directory scanning and management
  - File manipulation utilities

## 📚 Quick Start

### Installation

```kotlin
dependencies {
    implementation("net.legacy.library:configuration:1.0-SNAPSHOT")
}
```

### Basic Usage

1️⃣ **Create Configuration**
```java
// Create a builder with default settings
SimplixBuilder builder = SimplixBuilderFactory.createSimplixBuilder(
    "config",
    "plugins/MyPlugin"
);

// Create YAML configuration
Yaml config = builder.createYaml();
config.set("server.name", "MyServer");
config.set("server.port", 25565);
```

2️⃣ **Custom Type Serialization**
```java
@SimplixSerializerSerializableAutoRegister
public class PlayerDataSerializer implements SimplixSerializable<PlayerData> {
    @Override
    public PlayerData deserialize(Object obj) {
        // Deserialize logic
    }

    @Override
    public Object serialize(PlayerData data) {
        // Serialize logic
    }
}
```

3️⃣ **File Operations**
```java
// Read file content
String content = IOUtil.readFileAsString("config.yml");

// Write to file
IOUtil.writeStringToFile("config.yml", "key: value");

// List all files in directory
List<File> files = IOUtil.getAllFiles("plugins/configs");
```

## 🔧 Core Components

### Configuration Building
- `SimplixBuilderFactory`: Factory for creating pre-configured builders
- Support for multiple formats (YAML, JSON, TOML)
- Automatic reload and comment preservation
- Sorted data storage for consistency

### Serialization System
- `@SimplixSerializerSerializableAutoRegister`: Auto-registration annotation
- `SimplixSerializerSerializableAutoRegisterProcessor`: Registration processor
- Integration with SimplixStorage serialization
- Thread-safe operation handling

### I/O Utilities
- File reading and writing operations
- Directory management
- File copying and moving
- Path manipulation utilities

## 🎯 Advanced Features

### Custom Builder Configuration
```java
SimplixBuilder builder = SimplixBuilderFactory
    .createSimplixBuilderFromFile(new File("config.yml"))
    .setDataType(DataType.SORTED)
    .setConfigSettings(ConfigSettings.PRESERVE_COMMENTS)
    .setReloadSettings(ReloadSettings.AUTOMATICALLY);
```

### Complex File Operations
```java
// Copy with directory creation
IOUtil.createDirectories("plugins/data");
IOUtil.copyFile("source.yml", "plugins/data/target.yml");

// Batch file processing
List<File> files = IOUtil.getAllFiles("plugins/data");
files.forEach(file -> {
    if (IOUtil.getFileExtension(file.getPath()).equals("yml")) {
        // Process YAML files
    }
});
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

