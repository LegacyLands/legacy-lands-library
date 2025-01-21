# 🌟 Commons Module

A versatile collection of utilities and services designed to simplify development tasks. Its features include reflection-based injection (e.g., VarHandleReflectionInjector), easy scheduling utilities (e.g., TaskInterface for timed and recurring tasks), and additional helpers like GsonUtil for JSON processing. The commons module integrates with ecosystem components such as Fairy IoC and the annotation module to streamline your workflow across various projects.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## 📋 Table of Contents
- [Introduction](#introduction)
- [Key Features](#key-features)
- [Installation](#installation)
- [Code Overview](#code-overview)
  - [VarHandleReflectionInjector](#varhandlereflectioninjector)
  - [Task Management](#task-management)
  - [Miscellaneous Utilities](#miscellaneous-utilities)
- [Related Modules](#related-modules)
- [License](#license)
- [Contributing](#contributing)

## Introduction

The commons module acts as a foundation layer, providing a range of smaller utilities and abstractions that are frequently used across multiple modules. This includes reflection-based injection mechanisms, a flexible task scheduling system, and basic JSON utilities, among others.

Common use cases:
1. Injecting VarHandle references into static fields without writing verbose reflection code.  
2. Scheduling periodic (or one-time) tasks in a consistent manner, integrated with Fairy's MCScheduler system.  
3. Simplifying JSON configuration and data handling with a shared Gson instance and optional customization methods.  

## Key Features

• Simple reflection-based injection using VarHandleReflectionInjector  
• Automated scheduling with TaskInterface and optional TaskAutoStartAnnotation  
• Thread-safe JSON handling via GsonUtil (supporting custom builder modifications)  
• Class-level injection integration with Fairy IoC  
• Minimal setup, offering a set of ready-to-use patterns and classes  

## Installation

Add the commons module's built artifact to your project. The following example is in Gradle (Kotlin DSL):

```kotlin
dependencies {
    compileOnly(files("libs/commons-1.0-SNAPSHOT.jar"))
}
```

Adjust accordingly for your preferred build system or repository hosting method.

## Code Overview

Below are some of the primary classes and submodules found within the commons source code, along with their typical usage scenarios.

### VarHandleReflectionInjector

Located in [VarHandleReflectionInjector.java](src/main/java/net/legacy/library/commons/injector/VarHandleReflectionInjector.java).  
• Purpose: Injects a VarHandle reference into a static field annotated with @VarHandleAutoInjection.  
• Typical Use Case: Allows you to manipulate a field (e.g., set or get its value) using VarHandle without writing repetitive reflection code.

For example, if you annotate a static field:
```java
@VarHandleAutoInjection(fieldName = "someField")
public static VarHandle SOME_FIELD_HANDLE;
```
VarHandleReflectionInjector automatically locates the declared field "someField" on the target class and assigns the resulting VarHandle to the static field. This can simplify concurrency-related operations or advanced reflection tasks.

### Task Management

Located under [task](src/main/java/net/legacy/library/commons/task) package.  
• Classes of Interest: TaskInterface, TaskAutoStartAnnotation, TaskAutoStartAnnotationProcessor.  
• Purpose: Provide a consistent approach to scheduling tasks using Fairy's MCSchedulers.  

Key points:
1. Implementing TaskInterface in your class:  
   - Override start() to schedule recurring or one-time tasks asynchronously (by default).  
   - Utilize additional helper methods for intervals, durations, or custom repeat predicates.

2. TaskAutoStartAnnotation & Processor:  
   - Annotate your class with @TaskAutoStartAnnotation if you want tasks to automatically start when the annotation processor runs.  
   - The processor checks if the class should be IoC-managed or instantiated reflectively, then calls start().  

Example:
```java
@TaskAutoStartAnnotation(isFromFairyIoC = false)
public class ExampleTask implements TaskInterface {
    @Override
    public ScheduledTask<?> start() {
        // Print a message every second
        return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 20);
    }
}
```

### Miscellaneous Utilities

• GsonUtil (in [GsonUtil.java](src/main/java/net/legacy/library/commons/util/GsonUtil.java)):  
  - Provides a thread-safe way to customize and access a shared Gson instance.  
  - Supports chaining new settings or configuring the GsonBuilder with `customizeGsonBuilder(Consumer<GsonBuilder>)`.  

Example usage:
```java
// Add custom serialization rules
GsonUtil.customizeGsonBuilder(builder -> {
    builder.excludeFieldsWithoutExposeAnnotation();
    builder.setPrettyPrinting();
});

// Retrieve the updated Gson
Gson gson = GsonUtil.getGson();
```

## Related Modules

• [Annotation Module](../annotation/README.md):  
  - Delivers annotation scanning and processing functionality.  
  - The commons module can rely on it for auto-start tasks (TaskAutoStartAnnotation) or other reflection-based tasks.

## License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## Contributing

You are welcome to contribute by opening issues, proposing new features, or making PRs that expand or refine the existing codebase. Whether it's improving documentation, adding new utility classes, or optimizing existing ones, your contributions help make the library more robust for everyone.

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

