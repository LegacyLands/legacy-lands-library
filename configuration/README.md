# 🔧 Configuration Module

A comprehensive and thread-safe configuration system built on top of 
[SimplixStorage](https://github.com/Simplix-Softworks/SimplixStorage). This module adds extra 
capabilities such as automatic serializer registration, factory-based builders, and robust 
serialization annotations, simplifying how you manage and persist configuration data.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)
[![SimplixStorage](https://img.shields.io/badge/SimplixStorage-3.2.7-orange.svg)](https://github.com/Simplix-Softworks/SimplixStorage)

## 📋 Table of Contents
- [Overview](#overview)
- [Installation](#installation)
- [Usage](#usage)
  - [1. Create Configuration Files](#1-create-configuration-files)
  - [2. Thread Safety](#2-thread-safety)
  - [3. Serializer Registration](#3-serializer-registration)
- [Key Classes](#key-classes)
  - [SimplixBuilderFactory](#simplixbuilderfactory)
  - [SimplixSerializerSerializableAutoRegisterProcessor](#simplixserializerserializableautoregisterprocessor)
- [Advanced Features](#advanced-features)
- [License](#license)
- [Contributing](#contributing)

## Overview

This configuration module allows you to:
1. Build local or directory-based configuration files using SimplixBuilder without having to manually 
   customize advanced settings each time.  
2. Securely read and write configuration values (e.g., JSON, YAML, TOML) in a thread-safe manner.  
3. Leverage annotation-based serialization via @SimplixSerializerSerializableAutoRegister, automatically 
   registering custom classes for simplified data persistence.  

It integrates seamlessly with the rest of the LegacyLands ecosystem, such as the annotation module 
and Fairy IoC, ensuring an efficient and cohesive workflow.

## Installation

Add the built artifact for the configuration module to your project. For example, in your Gradle 
(Kotlin DSL) file:

```kotlin
dependencies {
    compileOnly(files("libs/configuration-1.0-SNAPSHOT.jar"))
}
```

Adjust this to suit your preferred build system, repository placements, or versioning approach.

## Usage

Below is an outline of how to use the configuration module effectively within a Fairy-based plugin or 
other Java environment.

### 1. Create Configuration Files

You can utilize the SimplixBuilderFactory to create a SimplixBuilder in various ways (e.g., from a 
single File, a directory, or a path string). Once you have the builder, you can pick what format 
you'd like (YAML, JSON, etc.):

```java
SimplixBuilder builder = SimplixBuilderFactory.createSimplixBuilder("example", "D:/");

// Typically you will choose one (YAML/JSON/TOML).
Yaml yamlFile = builder.createYaml();
yamlFile.set("someConfigKey", "someValue");
```

### 2. Thread Safety

The underlying data structures in SimplixStorage, combined with automatic synchronization in this 
configuration module, allow safe concurrent reads/writes:
• No need to implement additional locks or concurrency layers manually.  
• Minimizes the risk of data corruption when multiple threads attempt to modify the same configuration.

### 3. Serializer Registration

With annotation scanning enabled (through the annotation module), any class annotated with 
@SimplixSerializerSerializableAutoRegister will automatically be registered as a SimplixSerializable 
implementation by the SimplixSerializerSerializableAutoRegisterProcessor. This ensures your custom 
types can be serialized/deserialized without extra manual steps.

Example:
```java
@SimplixSerializerSerializableAutoRegister
public class PlantSerializable implements SimplixSerializable<Plant> {
    @Override
    public Plant deserialize(@NonNull Object serializedObject) {
        // Implement your logic to turn "serializedObject" into a Plant instance
        return plantInstance;
    }

    @Override
    public Object serialize(@NonNull Plant plant) {
        // Convert your Plant object into a structure (Map, String, etc.) for storage
        return serializedData;
    }

    @Override
    public Class<Plant> getClazz() {
        return Plant.class;
    }
}
```

This is especially valuable for advanced domain objects or complex data structures that need storage 
in your configuration files.

## Key Classes

### SimplixBuilderFactory
Located at 
[configuration/factory/SimplixBuilderFactory.java](src/main/java/net/legacy/library/configuration/factory/SimplixBuilderFactory.java).

• Bundles common settings (DataType, config comments, reload behavior) into any newly created 
  SimplixBuilder.  
• Provides various static methods to quickly generate builder instances from Files, directories, or paths.

### SimplixSerializerSerializableAutoRegisterProcessor
Annotated with @AnnotationProcessor to detect your custom classes that implement SimplixSerializable.  
• Once triggered, it instantiates an object of that class and calls SimplixSerializer.registerSerializable(...).  
• Ensures you can skip manual registration steps for every new serializable class.

## Advanced Features

• Multi-Format Support (YAML, JSON, TOML): Switch easily to any format supported by SimplixStorage.  
• Automatic Reload Settings: By default, the SimplixBuilder uses ReloadSettings.AUTOMATICALLY, ensuring 
  changes are picked up promptly.  
• Fine-Tuned Data Sorting (DataType.SORTED): Keeps your configuration files organized for better readability.  
• Integration with IoC, letting you create or manage configuration objects via injection if desired.

## License

This project is licensed under the MIT License. Please see the [LICENSE](../LICENSE) file for more details.

## Contributing

We appreciate all contributions via pull requests, issue reports, and feature suggestions. Whether 
it's improving documentation, optimizing builder settings, or introducing new advanced serialization 
options, your help makes the module stronger for everyone!

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

