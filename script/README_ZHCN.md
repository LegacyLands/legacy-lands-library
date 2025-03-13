### 脚本 (Script) 模块

这是一个强大、灵活、可拓展且高性能的脚本执行引擎封装，支持 `Rhino` 和 `Nashorn` 两种 `JavaScript`
引擎，提供了统一的接口用于脚本执行、函数调用、编译优化等功能，适用于需要在应用中嵌入脚本能力的场景。

### 用法

```kotlin
// Dependencies
dependencies {
    // script module
    implementation("net.legacy.library:script:1.0-SNAPSHOT")
}
```

### 脚本引擎

该模块提供了两种强大的 `JavaScript` 引擎实现：

- **Rhino 引擎**：Mozilla 的 `JavaScript` 实现，兼容性好，适合大多数场景。
- **Nashorn 引擎**：一种常用且高效的 `JavaScript` 引擎，提供更好的 ES6 支持。
- **V8 引擎**: Google 的高性能 `JavaScript` 和 `WebAssembly` 引擎，适用于需要极高性能和低延迟的场景。

两种引擎都实现了相同的接口，可以根据需要灵活选择。
在拓展性上，我们允许任何基于 `ScriptEngineInterface` 与 `ScriptScope` 的新实现。

V8 的限制：

- **不支持脚本编译 (compile):**  `V8ScriptEngine` 的 `compile`、`executeCompiled` 和 `invokeCompiledFunction` 方法会抛出
  `UnsupportedOperationException`。
- **不支持 ScriptScope**: `V8ScriptEngine` 不支持 `ScriptScope`，
  所有操作（`execute`、`invokeFunction`、全局变量的设置/获取/删除）都在 V8 运行时的全局作用域中进行。 传入 `ScriptScope`
  参数会被忽略 (但仍然会检查是否为 `null`).
- **JavaScript 和 Java 类型映射：**  `V8ScriptEngine` 在内部处理了 `JavaScript` 和 `Java` 类型之间的转换：
    * 基本类型（`null`、`Integer`、`Double`、`Boolean`、`String`）会进行自动转换。
    * Java `Map` 会转换为 `V8Object`。
    * Java `List` 会转换为 `V8Array`。
    * `V8Object`、`V8Array`、`V8Function`、`V8TypedArray` 会在 Java 和 JavaScript 之间直接传递，不做转换。
    * 其他类型不受支持，会抛出 `IllegalArgumentException`。

### 基本用法

可以使用工厂类快速创建脚本引擎：

```java
public class Example {
    public static void main(String[] args) {
        // 创建 Rhino 引擎
        ScriptEngineInterface rhinoEngine = ScriptEngineFactory.createRhinoScriptEngine();
        // 或创建 Nashorn 引擎
        ScriptEngineInterface nashornEngine = ScriptEngineFactory.createNashornScriptEngine();
        // 或创建 V8 引擎
        ScriptEngineInterface v8Engine = ScriptEngineFactory.createV8ScriptEngine();

        try {
            // 直接执行脚本
            Object result = rhinoEngine.execute("1 + 1", null); // 或 rhinoEngine.execute("1 + 1");
            System.out.println(result); // 输出：2.0
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

`ScriptEngineInterface` 实现本身不是线程安全的，应该为每个线程创建单独的引擎实例，或者使用同步块/锁来保护对共享引擎实例的访问。

### 脚本作用域

作用域用于隔离变量环境，提供了更灵活的变量管理：

```java
public class Example {
    public static void main(String[] args) {
        RhinoScriptEngine scriptEngine = new RhinoScriptEngine();

        try {
            // 创建脚本作用域
            RhinoScriptScope scriptScope = RhinoScriptScope.of(scriptEngine);

            // 设置变量
            scriptScope.setVariable("x", 10);
            scriptScope.setVariable("name", "Legacy");

            // 在作用域中执行脚本
            Object result = scriptEngine.execute("'Hello, ' + name + '! x = ' + x", scriptScope);
            System.out.println(result); // 输出：Hello, Legacy! x = 10

            // 获取变量
            Object value = scriptScope.getVariable("name");
            System.out.println(value); // 输出：Legacy

            // 移除变量
            scriptScope.removeVariable("name");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

当 `ScriptScope` 传入 `null`，则使用引擎级别作用域。

注意：`V8ScriptEngine` 不支持 `ScriptScope`，传入将会自动忽略。

### 函数调用

可以定义并调用 JavaScript 函数：

```java
public class Example {
    public static void main(String[] args) {
        ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();
        RhinoScriptScope scope = RhinoScriptScope.of((RhinoScriptEngine) engine);

        try {
            // 定义函数
            String script = """
                    function calculateTotal(price, quantity, taxRate) {
                        return price * quantity * (1 + taxRate);
                    }
                    """;

            // 执行脚本以定义函数
            engine.execute(script, scope);

            // 调用函数
            Object total = engine.invokeFunction("calculateTotal", scope, 19.99, 5, 0.08);
            System.out.println("Total: $" + total); // 输出：Total: $107.946
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

### 脚本编译

通过预编译提高性能，适合重复执行的脚本：

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
                        result.fibonacci = fibonacci(data % 20); // 限制n防止计算过大
                        result.timestamp = new Date().getTime();
                        return JSON.stringify(result); // 返回JSON字符串便于查看结果
                    }
                    """;

            // 1. 不使用预编译方式
            System.out.println("===== 不使用预编译 =====");
            long startNoCompile = System.currentTimeMillis();

            for (int i = 1; i <= 50; i++) {
                // 每次都重新执行整个脚本
                engine.execute(complexScript, scope);
                Object result = engine.invokeFunction("processData", scope, i);
                if (i <= 3) {
                    System.out.println("结果 " + i + ": " + result);
                }
            }

            long endNoCompile = System.currentTimeMillis();
            long noCompileTime = endNoCompile - startNoCompile;

            // 2. 使用预编译方式 (invokeCompiledFunction)
            System.out.println("===== 使用预编译 =====");
            long startCompile = System.currentTimeMillis();

            // 只编译一次
            Object compiled = engine.compile(complexScript);
            engine.executeCompiled(compiled, scope);

            for (int i = 1; i <= 50; i++) {
                Object result = engine.invokeCompiledFunction("processData", scope, i);
                if (i <= 3) {
                    System.out.println("结果 " + i + ": " + result);
                }
            }

            long endCompile = System.currentTimeMillis();
            long compileTime = endCompile - startCompile;

            // 输出性能比较
            System.out.println("===== 性能比较 =====");
            System.out.println("不使用预编译: " + noCompileTime + " 毫秒");
            System.out.println("使用预编译: " + compileTime + " 毫秒");
            System.out.println("性能提升: " + (noCompileTime * 100 / compileTime) + "%");
            System.out.println("时间节省: " + (noCompileTime - compileTime) + " 毫秒");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

```text
[16:41:58 INFO]: [script] [STDOUT] ===== 不使用预编译 =====
[16:41:58 INFO]: [script] [STDOUT] 结果 1: {"original":1,"processed":true,"fibonacci":1,"timestamp":1741855318300}
[16:41:58 INFO]: [script] [STDOUT] 结果 2: {"original":2,"processed":true,"fibonacci":1,"timestamp":1741855318311}
[16:41:58 INFO]: [script] [STDOUT] 结果 3: {"original":3,"processed":true,"fibonacci":2,"timestamp":1741855318315}
[16:41:58 INFO]: [script] [STDOUT] ===== 使用预编译 =====
[16:41:58 INFO]: [script] [STDOUT] 结果 1: {"original":1,"processed":true,"fibonacci":1,"timestamp":1741855318446}
[16:41:58 INFO]: [script] [STDOUT] 结果 2: {"original":2,"processed":true,"fibonacci":1,"timestamp":1741855318446}
[16:41:58 INFO]: [script] [STDOUT] 结果 3: {"original":3,"processed":true,"fibonacci":2,"timestamp":1741855318447}
[16:41:58 INFO]: [script] [STDOUT] ===== 性能比较 =====
[16:41:58 INFO]: [script] [STDOUT] 不使用预编译: 274 毫秒
[16:41:58 INFO]: [script] [STDOUT] 使用预编译: 8 毫秒
[16:41:58 INFO]: [script] [STDOUT] 性能提升: 3425%
[16:41:58 INFO]: [script] [STDOUT] 时间节省: 266 毫秒
```

注意：`V8ScriptEngine` 不支持 `compile`、`executeCompiled` 和 `invokeCompiledFunction`。

### 全局变量管理

可以在引擎级别管理全局变量：

```java
public class Example {
    public static void main(String[] args) {
        ScriptEngineInterface engine = ScriptEngineFactory.createRhinoScriptEngine();

        try {
            // 设置全局变量
            engine.setGlobalVariable("VERSION", "1.0.0");
            engine.setGlobalVariable("DEBUG", true);

            // 在脚本中使用全局变量
            Object result = engine.execute("""
                    if (DEBUG) {
                        'Running version ' + VERSION + ' in debug mode';
                    } else {
                        'Running version ' + VERSION;
                    }
                    """, null);
            System.out.println(result); // 输出：Running version 1.0.0 in debug mode

            // 获取全局变量
            Object version = engine.getGlobalVariable("VERSION");
            System.out.println("Current version: " + version);

            // 移除全局变量
            engine.removeGlobalVariable("DEBUG");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```

### 完整示例

下面是一个更完整的示例，展示了如何在实际应用中使用脚本模块：

```java
public class Example {
    public static void main(String[] args) {
        try {
            // 创建引擎
            RhinoScriptEngine scriptEngine = new RhinoScriptEngine();
            RhinoScriptScope scope = RhinoScriptScope.of(scriptEngine);

            // 设置变量
            scope.setVariable("playerName", "Steve");
            scope.setVariable("playerLevel", 42);
            scope.setVariable("inventory", Map.of(
                    "diamond", 64,
                    "iron_ingot", 128,
                    "emerald", 32
            ));

            // 定义游戏逻辑函数
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

            // 编译并执行
            Object compiled = scriptEngine.compile(gameScript);
            scriptEngine.executeCompiled(compiled, scope);

            // 调用函数
            Object canCraft = scriptEngine.invokeCompiledFunction(null, "canCraftDiamondArmor", scope, scope.getVariable("inventory"));
            Object playerTitle = scriptEngine.invokeCompiledFunction(null, "getPlayerTitle", scope, scope.getVariable("playerName"), scope.getVariable("playerLevel"));
            Object inventoryWorth = scriptEngine.invokeCompiledFunction(null, "calculateWorth", scope, scope.getVariable("inventory"));

            // 输出结果
            System.out.println("玩家称号: " + playerTitle);
            System.out.println("可以制作钻石装备? " + canCraft);
            System.out.println("背包总价值: " + inventoryWorth + " 金币");
        } catch (ScriptException exception) {
            exception.printStackTrace();
        }
    }
}
```
