# 📚 Annotation Module

Based on the [Reflections](https://github.com/ronmamo/reflections) library, this module automates the scanning and processing of annotations, supporting multiple scenarios and multiple ClassLoaders for plugins or other modular environments.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## 📋 Table of Contents
- [Annotation Module](#-annotation-module)
  - [Table of Contents](#table-of-contents)
  - [Key Features](#key-features)
  - [Module Overview](#module-overview)
  - [Getting Started](#getting-started)
    - [Dependency](#dependency)
    - [Core Concept](#core-concept)
    - [Usage Example](#usage-example)
      - [1. Define an Annotation](#1-define-an-annotation)
      - [2. Create a Custom Annotation Processor](#2-create-a-custom-annotation-processor)
      - [3. Integrate Within a Plugin/Class](#3-integrate-within-a-pluginclass)
  - [How It Works](#how-it-works)
    - [1. Multiple ClassLoaders](#1-multiple-classloaders)
    - [2. AnnotationProcessingService](#2-annotationprocessingservice)
  - [Detailed Architecture](#detailed-architecture)
    - [Key Classes and Responsibilities](#key-classes-and-responsibilities)
  - [Additional Notes](#additional-notes)
  - [License](#license)

## Key Features

- 🔍 Automated annotation scanning using Reflections, configurable via package names or explicit URLs.  
- 📚 Supports multiple ClassLoaders, which is especially useful for cross-plugin or multi-module environments.  
- 🔄 Simplifies boilerplate by allowing you to write custom processors for a whole category of annotated classes.

## Module Overview

This module uses the Reflections library to search for specified annotations within your codebase and pass each discovered class to your custom processor (implementing CustomAnnotationProcessor). In short:

1. It can locate classes annotated with a target annotation across multiple package paths, JARs, or service URLs.  
2. It assigns these classes to their appropriate processor, as identified by the @AnnotationProcessor meta-annotation.  
3. It integrates with the Fairy IoC container; processors can be injected as singletons or instantiated via reflection, offering flexibility depending on the application environment.

This is particularly suited to environments such as BungeeCord/Spigot/Folia where it is common to scan multiple plugins for shared annotations, thus simplifying registration or initialization tasks.

## Getting Started

### Dependency

Include this module's build artifact in your own project. The following example demonstrates how to reference it via a Gradle Kotlin DSL:

```kotlin
dependencies {
    // Annotation module
    compileOnly(files("libs/annotation-1.0-SNAPSHOT.jar"))
}
```

> Note: Adjust the setup as needed for your build system (e.g., Maven, Gradle) and your preferred repository arrangement.

### Core Concept

You can think of this module as a "library book index":

- You have a collection of "books" (classes) containing specific "stickers" (annotations).  
- You need to locate books with certain stickers (search for classes by annotation).  
- You then perform custom operations on these books (custom annotation processor logic).

AnnotationProcessingService coordinates the overall scanning, while each CustomAnnotationProcessor implementation defines how to handle classes that match a specific annotation.

### Usage Example

#### 1. Define an Annotation

Below is an example of an annotation (SimplixSerializerSerializableAutoRegister) indicating classes that should be automatically registered for serialization:

```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface SimplixSerializerSerializableAutoRegister {
}
```

#### 2. Create a Custom Annotation Processor

Use the @AnnotationProcessor meta-annotation to specify the target annotation for your processor, then implement the CustomAnnotationProcessor interface:

```java
@AnnotationProcessor(SimplixSerializerSerializableAutoRegister.class)
public class SimplixSerializerSerializableAutoRegisterProcessor implements CustomAnnotationProcessor {

    @Override
    public void process(Class<?> clazz) throws Exception {
        // Handle classes annotated with SimplixSerializerSerializableAutoRegister
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // Handle any exceptions arising during scanning or processing
    }
}
```

You can optionally override the before, after, or finallyAfter methods to execute additional logic before or after processing each class.

#### 3. Integrate Within a Plugin/Class

In a Fairy-based plugin, you might configure the service in your plugin's main class. For example:

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

        // Pass multiple ClassLoaders to discover annotated classes within external modules/plugins
        annotationProcessingService.processAnnotations(
            basePackages,
            false,
            this.getClassLoader(),
            ConfigurationLauncher.class.getClassLoader()
        );
    }
}
```

When you need to scan multiple plugins or modules for the same annotation, simply supply each relevant ClassLoader in the method call.

## How It Works

### 1. Multiple ClassLoaders

- Each plugin or module is typically managed by its own ClassLoader.  
- To discover classes from another plugin/module, pass the associated ClassLoader(s).  
- AnnotationProcessingService can aggregate classes from all these loaders, scanning them in a single run.

### 2. AnnotationProcessingService

This service forms the core of the module's functionality:

1. Given packages or URLs, it uses Reflections to find classes with the specified annotations.  
2. It checks each available processor (indicated by @AnnotationProcessor) and matches them to the correct annotation.  
3. For each discovered class, it calls the processor's process and exception methods, and optionally before, after, and finallyAfter if implemented.

## Detailed Architecture

### Key Classes and Responsibilities

• AnnotationProcessingService  
  - Implements AnnotationProcessingServiceInterface to govern scanning and invocation of processors.  
  - Utilizes AnnotationScanner and ReflectUtil to locate annotated classes and manage processor instances.

• AnnotationScanner  
  - Wraps Reflections usage, enabling you to locate annotated classes by package name or URL collection.

• CustomAnnotationProcessor  
  - Interface for creating custom processors; outlines how classes should be handled and how exceptions should be processed.

• @AnnotationProcessor  
  - Meta-annotation designating which annotation the processor targets.

• ReflectUtil  
  - A utility class to combine multiple ClassLoader scanning paths (URLs), suitable for multi-package or multi-module contexts.

## Additional Notes

When you need to handle multiple annotations, you can create separate processors or incorporate logic for multiple annotations in a single processor. Ensure each processor class is annotated with @AnnotationProcessor, specifying its target annotation(s).

Furthermore, since Fairy IoC can automatically manage and inject class instances, you may choose between a singleton approach or reflection-based construction, accommodating different scopes as your application requires.

## License

This project is licensed under the MIT License. See the [LICENSE](../LICENSE) file for more information.

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

