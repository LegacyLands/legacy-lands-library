# 🛠 Commons Framework

A collection of essential utilities and tools focusing on reflection injection, task scheduling, and common operations. Built with flexibility and ease of use in mind.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🔄 **VarHandle Injection System**
  - Automated VarHandle injection via `@VarHandleAutoInjection`
  - Support for both direct and static method-based injection
  - Thread-safe field access through VarHandle
  - Flexible injection strategies with `StaticInjectorInterface`

- ⏰ **Task Management**
  - Rich task scheduling API through `TaskInterface`
  - Support for both sync and async execution
  - Flexible scheduling with ticks or Duration
  - Auto-start capability via `@TaskAutoStartAnnotation`

- 🔧 **Utility Components**
  - Thread-safe JSON operations with `GsonUtil`
  - Customizable Gson configuration
  - Factory patterns for component creation
  - Integration with Fairy IoC container

## 📚 Quick Start

### Installation

```kotlin
dependencies {
    implementation("net.legacy.library:commons:1.0-SNAPSHOT")
}
```

### Basic Usage

1️⃣ **VarHandle Injection**
```java
public class MyClass {
    @VarHandleAutoInjection(fieldName = "targetField")
    private static VarHandle TARGET_FIELD_HANDLE;
    
    private String targetField;
}

// Inject the VarHandle
StaticInjectorInterface injector = InjectorFactory.createVarHandleReflectionInjector();
injector.inject(MyClass.class);
```

2️⃣ **Task Scheduling**
```java
@TaskAutoStartAnnotation
public class MyTask implements TaskInterface {
    @Override
    public ScheduledTask<?> start() {
        return scheduleAtFixedRate(
            () -> System.out.println("Task executed"),
            Duration.ZERO,
            Duration.ofSeconds(5)
        );
    }
}
```

3️⃣ **JSON Operations**
```java
// Customize Gson
GsonUtil.customizeGsonBuilder(builder -> {
    builder.setPrettyPrinting();
    builder.serializeNulls();
});

// Use the shared Gson instance
Gson gson = GsonUtil.getGson();
```

## 🔧 Core Components

### Injection System
- `VarHandleReflectionInjector`: Core injection implementation
- `StaticInjectorInterface`: Base interface for static injectors
- `ObjectInjectorInterface`: Base interface for object injectors
- `@VarHandleAutoInjection`: Annotation for marking injection targets

### Task Framework
- `TaskInterface`: Rich API for task scheduling
- `@TaskAutoStartAnnotation`: Auto-start task marker
- `TaskAutoStartAnnotationProcessor`: Annotation processor for tasks
- Support for various scheduling patterns:
  - Fixed-rate execution
  - Delayed execution
  - Conditional repetition
  - Duration-based scheduling

### Utilities
- `GsonUtil`: Thread-safe JSON operations
- `InjectorFactory`: Factory for creating injector instances

## 🎯 Advanced Features

### Custom VarHandle Injection
```java
@VarHandleAutoInjection(
    fieldName = "field",
    staticMethodName = "getHandle",
    staticMethodPackage = "com.example.Handles"
)
private static VarHandle FIELD_HANDLE;
```

### Complex Task Scheduling
```java
@Override
public ScheduledTask<?> start() {
    return scheduleAtFixedRate(
        () -> complexOperation(),
        Duration.ofSeconds(1),
        Duration.ofMinutes(5),
        result -> shouldContinue(result)
    );
}
```

### Thread-Safe JSON Configuration
```java
GsonUtil.setNewGson(() -> new GsonBuilder()
    .setPrettyPrinting()
    .serializeNulls()
    .registerTypeAdapter(MyType.class, new MyTypeAdapter())
);
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

