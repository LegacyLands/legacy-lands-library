### Foundation Module

The testing infrastructure every library module builds its in-server integration tests on: the `@ModuleTest` annotation that describes a test class, `AbstractModuleTestRunner` that runs the test classes and validates the run, `TestResultSummary` and `TestMetrics` for the results, `TestTimer` for timings, and `TestLogger` for the log lines.

### Usage

```kotlin
dependencies {
    compileOnly("net.legacy.library:foundation:1.0-SNAPSHOT")
}
```

### TestLogger Message Formatting

Every `TestLogger` method takes a message and optional format arguments.
The message is passed through `String.format` only when arguments are given; without arguments it is logged exactly as written.
A message that is already formatted, or that carries text such as an exception message, may therefore contain `%`:

```java
// Logged as is: no arguments, so "%" needs no escaping
TestLogger.logFailure("player", "Save failed: " + exception.getMessage());

// Formatted: arguments are given
TestLogger.logInfo("player", "Saved %d of %d entities", saved, total);
```
