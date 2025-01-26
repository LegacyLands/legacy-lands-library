# 🎯 Annotation Processing Framework

A powerful yet elegant framework for automated annotation processing, built on top of [Reflections](https://github.com/ronmamo/reflections) library. Designed for modern Java applications with a focus on plugin architectures and modular systems.

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🔍 **Flexible Scanning Options**
  - Package-based scanning via `processAnnotations(String basePackage, ...)`
  - Multi-package scanning via `processAnnotations(List<String> basePackages, ...)`
  - Direct URL scanning via `processAnnotations(Collection<URL> urls, ...)`

- 🔌 **ClassLoader Integration**
  - Support for multiple ClassLoaders in a single scan
  - Ideal for plugin architectures where classes are loaded by different ClassLoaders
  - Utility methods in `ReflectUtil` for resolving URLs across ClassLoaders

- 🛠 **Comprehensive Processor Lifecycle**
  - `before()`: Pre-processing setup
  - `process()`: Main processing logic
  - `after()`: Post-processing cleanup
  - `exception()`: Error handling
  - `finallyAfter()`: Guaranteed cleanup execution

- 🎯 **Fairy IoC Integration**
  - Optional singleton injection via Fairy IoC container
  - Support for both IoC-managed and reflection-based instantiation
  - `@InjectableComponent` support for dependency injection

- ⚡️ **Efficient Processing**
  - Lazy loading through Reflections library
  - Optimized URL resolution for ClassLoader scanning
  - Reusable scanning results via `AnnotationScanner`

- 🔒 **Error Handling**
  - Dedicated exception handling per processor
  - Granular error control at class level
  - Comprehensive logging through Fairy's Log system

## 📚 Table of Contents

- [Quick Start](#-quick-start)
- [Core Concepts](#-core-concepts)
- [Advanced Usage](#-advanced-usage)
- [API Reference](#-api-reference)
- [Use Cases](#-use-cases)

## 🚀 Quick Start

### Installation

#### Gradle (Kotlin DSL)
```kotlin
dependencies {
    implementation("net.legacy.library:annotation:1.0-SNAPSHOT")
}
```

#### Maven
```xml
<dependency>
    <groupId>net.legacy.library</groupId>
    <artifactId>annotation</artifactId>
    <version>1.0-SNAPSHOT</version>
</dependency>
```

### Basic Usage

1️⃣ **Define Your Annotation**
```java
@Target(ElementType.TYPE)
@Retention(RetentionPolicy.RUNTIME)
public @interface MyCustomAnnotation {
    String value() default "";
}
```

2️⃣ **Create a Processor**
```java
@AnnotationProcessor(MyCustomAnnotation.class)
public class MyCustomProcessor implements CustomAnnotationProcessor {
    @Override
    public void process(Class<?> clazz) throws Exception {
        MyCustomAnnotation annotation = clazz.getAnnotation(MyCustomAnnotation.class);
        // Process the annotated class
    }

    @Override
    public void exception(Class<?> clazz, Exception exception) {
        // Handle exceptions
    }
    
    // Optional lifecycle hooks
    @Override
    public void before(Class<?> clazz) {
        // Pre-processing logic
    }
    
    @Override
    public void after(Class<?> clazz) {
        // Post-processing logic
    }
}
```

3️⃣ **Initialize Processing**
```java
@FairyLaunch
public class MyPlugin extends Plugin {
    @Autowired
    private AnnotationProcessingServiceInterface processingService;

    @Override
    public void onEnable() {
        processingService.processAnnotations(
            List.of("com.example.package"),
            true,  // Use Fairy IoC singleton
            getClassLoader()
        );
    }
}
```

## 🎯 Core Concepts

### Architecture Components

- **AnnotationProcessingService**
  - Core service orchestrating the scanning and processing workflow
  - Manages processor lifecycle and exception handling
  - Supports multiple scanning strategies

- **CustomAnnotationProcessor**
  - Interface for implementing custom processors
  - Rich lifecycle hooks (before, process, after, exception)
  - Type-safe annotation processing

- **AnnotationScanner**
  - Efficient class scanning using Reflections
  - Support for multiple ClassLoaders
  - URL-based and package-based scanning

### Processing Lifecycle

1. **Initialization**: Configure scanning parameters and ClassLoaders
2. **Discovery**: Scan for annotated classes using specified strategy
3. **Processing**: Execute processor lifecycle for each discovered class
4. **Completion**: Invoke cleanup and finalization hooks

## 🔧 Advanced Usage

### Multiple ClassLoader Support

```java
processingService.processAnnotations(
    List.of("com.example.package"),
    true,
    mainClassLoader,
    pluginClassLoader,
    moduleClassLoader
);
```

### Custom Scanning Strategy

```java
Collection<URL> customUrls = Sets.newHashSet();
customUrls.add(new URL("file:///path/to/classes"));
processingService.processAnnotations(customUrls, true);
```

### Error Handling

```java
@AnnotationProcessor(MyCustomAnnotation.class)
public class ResilientProcessor implements CustomAnnotationProcessor {
    @Override
    public void exception(Class<?> clazz, Exception exception) {
        Log.error("Failed to process " + clazz.getName(), exception);
        // Implement recovery strategy
    }
}
```

## 🎯 Use Cases

- **Plugin Systems**: Auto-registration of plugin components
- **API Extensions**: Dynamic feature discovery and loading
- **Configuration**: Annotation-based configuration processing
- **Event Systems**: Automatic event handler registration
- **Service Discovery**: Cross-module service registration
- **Validation**: Automated validation rule processing

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

