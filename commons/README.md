# 🌟 Commons Module

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

This module is packed with useful utilities and features that can enhance your projects in various ways. It's a versatile collection that will be updated regularly with new content.

## 📋 Contents

- [📦 Installation](#-installation)
- [🔧 VarHandleReflectionInjector](#-varhandlereflectioninjector)
- [⏰ Task Management](#-task-management)
- [🔗 Related Modules](#-related-modules)
- [📝 License](#-license)

## 📦 Installation

### Gradle

```kotlin
dependencies {
    compileOnly(files("libs/commons-1.0-SNAPSHOT.jar"))
}
```

## 🔧 VarHandleReflectionInjector

The [VarHandleReflectionInjector](src/main/java/me/qwqdev/library/commons/injector/VarHandleReflectionInjector.java) is an injector designed to work seamlessly with [VarHandleAutoInjection](src/main/java/me/qwqdev/library/commons/injector/annotation/VarHandleAutoInjection.java). It simplifies the process of assigning `VarHandle` without the need for verbose code.

### Example Usage

```java
public class Example {
    public static void main(String[] args) {
        Test test = new Test();

        // Use TField_HANDLE to set the value
        Test.TField_HANDLE.set(test, 2);

        // Prints 2
        System.out.println(test.getTField());
    }
}
```

```java
@Getter
@Setter
public class Test {
    private volatile int tField = 100;

    @VarHandleAutoInjection(fieldName = "tField")
    public static VarHandle TField_HANDLE;

    static {
        // Managed by Fairy IoC or can be created directly
        InjectorFactory.createVarHandleReflectionInjector().inject(Test.class);
    }
}
```

## ⏰ Task Management

The [TaskInterface](src/main/java/me/qwqdev/library/commons/task/TaskInterface.java) simplifies task scheduling by providing convenient methods that align with the Fairy Framework's [MCScheduler](https://docs.fairyproject.io/core/minecraft/scheduler).

### Example Usage

```java
public class Example {
    public static void main(String[] args) {
        TaskInterface taskInterface = new TaskInterface() {
            @Override
            public ScheduledTask<?> start() {
                // Prints "Hello, world!" every second
                return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 1000);
            }
        };

        // Start the task
        taskInterface.start();
    }
}
```

### Task Automation

Utilize [TaskAutoStartAnnotation](src/main/java/me/qwqdev/library/commons/task/annotation/TaskAutoStartAnnotation.java) to automate the start of tasks at specific times, reducing manual management.

```java
@TaskAutoStartAnnotation(isFromFairyIoC = false)
public class Example implements TaskInterface {
    @Override
    public ScheduledTask<?> start() {
        // Prints "Hello, world!" every second
        return scheduleAtFixedRate(() -> System.out.println("Hello, world!"), 0, 1000);
    }
}
```

## 🔗 Related Modules

For details on making annotation processors work with your plugins, refer to the [annotation module](../annotation/README.md).

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
