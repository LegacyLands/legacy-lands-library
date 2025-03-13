### Script Module

This is a powerful, flexible, extensible, and high-performance script execution engine wrapper
that supports both `Rhino` and `Nashorn` `JavaScript` engines. It provides a unified interface for script execution,
function invocation, compilation optimization, and other features, suitable for scenarios where you need to embed
scripting capabilities in your application.

### Usage

```kotlin
// Dependencies
dependencies {
    // script module
    implementation("net.legacy.library:script:1.0-SNAPSHOT")
}
```

### Script Engines

This module provides two powerful `JavaScript` engine implementations:

- **Rhino engine**: Mozilla's `JavaScript` implementation, with good compatibility and suitable for most scenarios.
- **Nashorn engine**: A commonly used and efficient `JavaScript` engine that provides better ES6 support.
- **V8 engine**: Google's high-performance `JavaScript` and `WebAssembly` engine, suitable for scenarios that require extremely high performance and low latency.

Both engines implement the same interface, allowing you to choose flexibly based on your needs. In terms of
extensibility, we allow any new implementation based on `ScriptEngineInterface` and `ScriptScope`.

V8 limitations:

- **Script compilation is not supported:** `V8ScriptEngine`'s `compile`, `executeCompiled`, and `invokeCompiledFunction` methods throw
  `UnsupportedOperationException`.
- **ScriptScope is not supported**: `V8ScriptEngine` does not support `ScriptScope`,
  all operations (`execute`, `invokeFunction`, setting/getting/deleting global variables) are performed in the global scope of the V8 runtime. The `ScriptScope` parameter is ignored (but it is still checked for `null`).
- **JavaScript and Java type mapping:** `V8ScriptEngine` handles conversions between `JavaScript` and `Java` types internally:
* Primitive types (`null`, `Integer`, `Double`, `Boolean`, `String`) are automatically converted.
* Java `Map` is converted to `V8Object`.
* Java `List` is converted to `V8Array`.
* `V8Object`, `V8Array`, `V8Function`, `V8TypedArray` are passed directly between Java and JavaScript without conversion.
* Other types are not supported and will throw `IllegalArgumentException`.

### Basic Usage

You can quickly create a script engine using the factory class:

```java
public class Example {
    public static void main(String[] args) {
        // Create a Rhino engine
        ScriptEngineInterface rhinoEngine = ScriptEngineFactory.createRhinoScriptEngine();
        // Or create a Nashorn engine
        ScriptEngineInterface nashornEngine = ScriptEngineFactory.createNashornScriptEngine();
        // Or create a V8 engine
        ScriptEngineInterface v8Engine = ScriptEngineFactory.createV8ScriptEngine();

        try {
            // Execute a script directly
            Object result = rhinoEngine.execute("1 + 1", null); // or rhinoEngine.execute("1 + 1");
            System.out.println(result); // Output: 2.0
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

`ScriptEngineInterface` implementations themselves are not thread-safe.
You should create separate engine instances for each thread, or use synchronization blocks/locks to protect access to
shared engine instances.

### Script Scopes

Scopes are used to isolate variable environments, providing more flexible variable management:

```java
public class Example {
    public static void main(String[] args) {
        RhinoScriptEngine scriptEngine = new RhinoScriptEngine();

        try {
            // Create a script scope
            RhinoScriptScope scriptScope = RhinoScriptScope.of(scriptEngine);

            // Set variables
            scriptScope.setVariable("x", 10);
            scriptScope.setVariable("name", "Legacy");

            // Execute a script in the scope
            Object result = scriptEngine.execute("'Hello, ' + name + '! x = ' + x", scriptScope);
            System.out.println(result); // Output: Hello, Legacy! x = 10

            // Get a variable
            Object value = scriptScope.getVariable("name");
            System.out.println(value); // Output: Legacy

            // Remove a variable
            scriptScope.removeVariable("name");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

When `ScriptScope` is passed `null`, the engine-level scope is used.

Note: `V8ScriptEngine` does not support `ScriptScope`, and the passed one will be automatically ignored.

### Function Invocation

You can define and invoke JavaScript functions:

```java
public class Example {
    public static void main(String[] args) {
        ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();
        RhinoScriptScope scope = RhinoScriptScope.of((RhinoScriptEngine) engine);

        try {
            // Define a function
            String script = """
                    function calculateTotal(price, quantity, taxRate) {
                        return price * quantity * (1 + taxRate);
                    }
                    """;

            // Execute the script to define the function
            engine.execute(script, scope);

            // Invoke the function
            Object total = engine.invokeFunction("calculateTotal", scope, 19.99, 5, 0.08);
            System.out.println("Total: $" + total); // Output: Total: $107.946
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

### Script Compilation

Improve performance through pre-compilation, suitable for repeatedly executed scripts:

```java
public class Example {
    public static void main(String[] args) {
        ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();
        RhinoScriptScope scope = RhinoScriptScope.of((RhinoScriptEngine) engine);

        try {
            String complexScript = """
                    function fibonacci(n) {
                        if (n <= 1) return n;
                        return fibonacci(n-1) + fibonacci(n-2);
                    }
                    
                    function processData(data) {
                        let result = {};
                        result.original = data;
                        result.processed = true;
                        result.fibonacci = fibonacci(data % 20); // Limit n to prevent excessive computation
                        result.timestamp = new Date().getTime();
                        return JSON.stringify(result); // Return a JSON string for easy result viewing
                    }
                    """;

            // 1. Without pre-compilation
            System.out.println("===== Without Pre-compilation =====");
            long startNoCompile = System.currentTimeMillis();

            for (int i = 1; i <= 50; i++) {
                // Re-execute the entire script each time
                engine.execute(complexScript, scope);
                Object result = engine.invokeFunction("processData", scope, i);
                if (i <= 3) {
                    System.out.println("Result " + i + ": " + result);
                }
            }

            long endNoCompile = System.currentTimeMillis();
            long noCompileTime = endNoCompile - startNoCompile;

            // 2. With pre-compilation (invokeCompiledFunction)
            System.out.println("===== With Pre-compilation =====");
            long startCompile = System.currentTimeMillis();

            // Compile only once
            Object compiled = engine.compile(complexScript);
            engine.executeCompiled(compiled, scope);

            for (int i = 1; i <= 50; i++) {
                Object result = engine.invokeCompiledFunction("processData", scope, i);
                if (i <= 3) {
                    System.out.println("Result " + i + ": " + result);
                }
            }

            long endCompile = System.currentTimeMillis();
            long compileTime = endCompile - startCompile;

            // Output performance comparison
            System.out.println("===== Performance Comparison =====");
            System.out.println("Without pre-compilation: " + noCompileTime + " ms");
            System.out.println("With pre-compilation: " + compileTime + " ms");
            System.out.println("Performance improvement: " + (noCompileTime * 100 / compileTime) + "%");
            System.out.println("Time saved: " + (noCompileTime - compileTime) + " ms");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

```text
[16:41:58 INFO]: [script] [STDOUT] ===== Without Pre-compilation =====
[16:41:58 INFO]: [script] [STDOUT] Result 1: {"original":1,"processed":true,"fibonacci":1,"timestamp":1741855318300}
[16:41:58 INFO]: [script] [STDOUT] Result 2: {"original":2,"processed":true,"fibonacci":1,"timestamp":1741855318311}
[16:41:58 INFO]: [script] [STDOUT] Result 3: {"original":3,"processed":true,"fibonacci":2,"timestamp":1741855318315}
[16:41:58 INFO]: [script] [STDOUT] ===== With Pre-compilation =====
[16:41:58 INFO]: [script] [STDOUT] Result 1: {"original":1,"processed":true,"fibonacci":1,"timestamp":1741855318446}
[16:41:58 INFO]: [script] [STDOUT] Result 2: {"original":2,"processed":true,"fibonacci":1,"timestamp":1741855318446}
[16:41:58 INFO]: [script] [STDOUT] Result 3: {"original":3,"processed":true,"fibonacci":2,"timestamp":1741855318447}
[16:41:58 INFO]: [script] [STDOUT] ===== Performance Comparison =====
[16:41:58 INFO]: [script] [STDOUT] Without pre-compilation: 274 ms
[16:41:58 INFO]: [script] [STDOUT] With pre-compilation: 8 ms
[16:41:58 INFO]: [script] [STDOUT] Performance improvement: 3425%
[16:41:58 INFO]: [script] [STDOUT] Time saved: 266 ms
```

Note: `V8ScriptEngine` does not support `compile`, `executeCompiled`, and `invokeCompiledFunction`.

### Global Variable Management

You can manage global variables at the engine level:

```java
public class Example {
    public static void main(String[] args) {
        ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

        try {
            // Set global variables
            engine.setGlobalVariable("VERSION", "1.0.0");
            engine.setGlobalVariable("DEBUG", true);

            // Use global variables in the script
            Object result = engine.execute("""
                    if (DEBUG) {
                        'Running version ' + VERSION + ' in debug mode';
                    } else {
                        'Running version ' + VERSION;
                    }
                    """, null);
            System.out.println(result); // Output: Running version 1.0.0 in debug mode

            // Get a global variable
            Object version = engine.getGlobalVariable("VERSION");
            System.out.println("Current version: " + version);

            // Remove a global variable
            engine.removeGlobalVariable("DEBUG");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

### Complete Example

Below is a more complete example showing how to use the script module in a practical application:

```java
public class Example {
    public static void main(String[] args) {
        try {
            // Create the engine
            RhinoScriptEngine scriptEngine = new RhinoScriptEngine();
            RhinoScriptScope scope = RhinoScriptScope.of(scriptEngine);

            // Set variables
            scope.setVariable("playerName", "Steve");
            scope.setVariable("playerLevel", 42);
            scope.setVariable("inventory", Map.of(
                    "diamond", 64,
                    "iron_ingot", 128,
                    "emerald", 32
            ));

            // Define game logic functions
            String gameScript = """
                    function canCraftDiamondArmor(inventory) {
                        return inventory.get("diamond") >= 24;
                    }
                    
                    function getPlayerTitle(name, level) {
                        if (level < 10) return name + ' the Novice';
                        if (level < 30) return name + ' the Adventurer';
                        if (level < 50) return name + ' the Expert';
                        return name + ' the Legendary';
                    }
                    
                    function calculateWorth(inventory) {
                        const prices = { diamond: 10, iron_ingot: 1, emerald: 15 };
                        let total = 0;
                        const entries = inventory.entrySet().toArray();
                        for (let i = 0; i < entries.length; i++) {
                            const item = entries[i].getKey();
                            const amount = entries[i].getValue();
                            if (prices[item]) {
                                total += amount * prices[item];
                            }
                        }
                        return total;
                    }
                    """;

            // Compile and execute
            Object compiled = scriptEngine.compile(gameScript);
            scriptEngine.executeCompiled(compiled, scope);

            // Invoke functions
            Object canCraft = scriptEngine.invokeCompiledFunction(null, "canCraftDiamondArmor", scope, scope.getVariable("inventory"));
            Object playerTitle = scriptEngine.invokeCompiledFunction(null, "getPlayerTitle", scope, scope.getVariable("playerName"), scope.getVariable("playerLevel"));
            Object inventoryWorth = scriptEngine.invokeCompiledFunction(null, "calculateWorth", scope, scope.getVariable("inventory"));

            // Output results
            System.out.println("Player Title: " + playerTitle);
            System.out.println("Can craft diamond armor? " + canCraft);
            System.out.println("Inventory worth: " + inventoryWorth + " gold");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```
