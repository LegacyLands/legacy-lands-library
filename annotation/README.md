# 📚 Annotation Module

> A fully automated annotation processor leveraging the [Reflections](https://github.com/ronmamo/reflections) library for efficient class operations.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## 📋 Table of Contents

- [📚 Annotation Module](#-annotation-module)
  - [📋 Table of Contents](#-table-of-contents)
  - [✨ Features](#-features)
  - [📝 Introduction](#-introduction)
  - [💻 Usage](#-usage)
    - [Dependencies](#dependencies)
    - [Concept](#concept)
    - [Example](#example)
      - [Define an Annotation](#define-an-annotation)
      - [Implement a Processor](#implement-a-processor)
      - [Usage in a Plugin](#usage-in-a-plugin)
  - [📚 ClassLoader Explanation](#-classloader-explanation)
    - [Key Concepts](#key-concepts)
  - [🔗 Additional Resources](#-additional-resources)
  - [📄 License](#-license)

## ✨ Features

- 🔍 Automated annotation processing
- 📚 Supports multiple class loaders
- 🔄 Simplifies repetitive class operations

## 📝 Introduction

This module combines with the [Reflections](https://github.com/ronmamo/reflections) library to implement a fully automated annotation processor. It's particularly useful for performing repetitive operations on classes.

## 💻 Usage

### Dependencies

```kotlin
dependencies {
    // Annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))
}
```

### Concept

Imagine you have many books at home, each with different stickers on the cover. When you want to find books with a specific type of sticker, you can ask a helper to look through all the books, identify those with the desired stickers, and pick them out.

- **AnnotationProcessingService**: Finds classes with specific annotations (like finding books with certain stickers).
- **CustomAnnotationProcessor**: Performs operations on these classes (like organizing the selected books).

### Example

#### Define an Annotation

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimplixSerializerSerializableAutoRegister {
}
```

#### Implement a Processor

```java
@AnnotationProcessor(SimplixSerializerSerializableAutoRegister.class)
public class SimplixSerializerSerializableAutoRegisterProcessor implements CustomAnnotationProcessor {
    @Override
    public void process(Class<?> clazz) throws Exception {
        // Perform operations
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // Handle exceptions
    }
}
```

#### Usage in a Plugin

```java
@FairyLaunch
public class Launcher extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
            "org.example",
            "net.legacy.library.configuration.serialize.annotation"
        );

        annotationProcessingService.processAnnotations(
            basePackages,
            false,
            this.getClassLoader(),
            ConfigurationLauncher.class.getClassLoader()
        );
    }
}
```

## 📚 ClassLoader Explanation

Each module operates as a plugin, akin to a library containing various books (classes). To access classes from other plugins, you need their `ClassLoader`.

### Key Concepts

- **ClassLoader**: Think of it as a library. Each plugin is an independent library storing classes it uses.
- **Multiple ClassLoaders**: Allow scanning across different plugins, ensuring all relevant classes are processed.

By providing multiple `ClassLoaders`, `AnnotationProcessingService` can scan and match all classes managed by these loaders, handing over the correct classes to `CustomAnnotationProcessor` for processing.

## 🔗 Additional Resources

- [Reflections Library Documentation](https://github.com/ronmamo/reflections)
- [Java Annotation Processing](https://docs.oracle.com/javase/tutorial/java/annotations/processing.html)

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details
