# ✅ 开发与协作规范（学习与编程行为指南）

> 本规范适用于所有由你参与的开发任务。你在接受信息、响应时应主动进行自我判断，包括但不限于：
>
> - 是否需要进行 Web 搜索以获取最新资料；
> - 是否需要进行深度思考（例如架构分析、API 选择、边界条件预判等），尤其在面临复杂设计或模糊需求时；
> - 深度思考应作为默认行为出现，可频繁执行，甚至每次响应都应进行；
> - 当存在风险判断不明确、实现方式多样、逻辑存在隐患时，必须先思考再生成；
> - 如判断用户意图不清，应先通过提问明确目标，再执行操作；
>
> 所有技术输出必须建立在准确、思考过的基础之上，拒绝机械生成与无脑填充。

## 1. 当你尝试编写一个新功能、添加新类或书写 Javadoc 时

1. 你需要先查看其他类的实现，尤其是相同模块中的类；
2. 主动学习、模仿现有代码的结构与风格，包括：
    - 缩进与换行格式；
    - 命名习惯；
    - Javadoc 注释风格。

## 2. 你必须制定清晰的 ToDo 任务清单，并在开发完成后遵循以下规则：

1. 使用 Gradle 的 `shadowJar` Task 尝试打包；
2. 若出现编译错误，必须修复后重新编译，直至编译通过；
3. 编写测试时，需编写基于功能的集成测试，测试内不得包含没有意义的测试，确保新功能的正确性和现有功能的稳定性；
4. 测试编写完成后，将测试流程交由用户执行，由用户反馈日志进行进一步调整；
5. 除非用户有明确说明，否则你不应该写任何的 Example 或文档。

## 3. 命名规范（类、字段、变量、方法）

1. 命名应清晰、准确、语义明确：
    - ✅ 正确示例：`Exception exception`，`Throwable throwable`，`IOException exception`
    - ❌ 错误示例（过于简单化）：`Exception e`，`Throwable t`
    - ❌ 错误示例（过于复杂化）：`Exception exceptionWhenFailedToInitializeXxx`
    - ❌ 错误示例（无意义）：`Cache whatIsThis = ...`
2. 出现命名重复时，应提升语义层级，而非简单加数字：
    - ✅ 正确示例：`Cache cache; Cache redisCache`
    - ❌ 错误示例：`Cache cache; Cache cache2`

## 4. 数据处理优先使用 `Stream API`（尤其是过滤、映射、聚合等操作）

- 避免使用传统 `for` 循环完成可以用 `stream` 完成的链式操作；
- 确保代码简洁、函数式、可读性强。

## 5. Lambda 表达式需保持简洁，避免冗余语法

1. ✅ 正确用法（单行时省略 `{}` 与 `return`，可使用方法引用）：
    - `() -> "something"`
    - `list.forEach(CustomExecutor::shutdown)`
2. ❌ 错误用法（冗余、过度包装）：
    - `(x) -> { doSomething(x); }` → 简化为 `x -> doSomething(x)`
    - `() -> { return "something"; }` → 简化为 `() -> "something"`
    - `list.forEach(x -> { CustomExecutor.shutdown(x); });` → 应使用方法引用

## 6. Javadoc 编写规范

1. 你必须优先参考 `net.legacy.library.annotation.service` 包下的类作为注释风格基准；
2. 若该包中无类似内容，再自行查阅其他类学习注释方式，但必须保持风格一致；
3. 常规结构应包括：
    - 简洁说明性首句（以动词开头），句末加英文句号；
    - 标签顺序统一：`@param` → `@return` → `@throws`；
    - 不遗漏边界条件、默认行为或异常说明；
4. 禁止使用不规范注释格式，特别是：
    - ❌ `@param input the input`
    - ✅ `@param input the user-defined script to evaluate`
5. 如果原始 Javadoc 中存在不合理的格式，应记录问题并征求负责人同意后统一修正；
6. 若方法涉及复杂逻辑，必须标明：
    - 是否线程安全；
    - 对 null 的处理策略；
    - 默认行为或返回值；
    - 抛出异常的触发条件与意义；
7. 必须模仿已有项目注释中的语气、缩进、空行规则，不得混用不同风格；
8. 如果使用接口、抽象类、继承等，重写的方法若没有需要注重的内容，则可以对形参、返回值、异常、描述等等使用 `{@inheritDoc}`；
9. 禁止写没必要的 `Usage example`；
10. 不需要为非 `public` 字段或方法写 Javadoc，除非真的有需要；
11. 其他标签使用规范：
    - `@see`：列出相关类、接口或方法，按出现顺序书写；
    - `@since`：首次引入版本；
    - `@deprecated`：标记过时元素，并使用 `@see` 指向替代方案；
    - `@implSpec`：说明实现细节，供继承或实现者阅读；
    - `@implNote`：记录实现者备注（性能、限制等），不影响规范语义；
    - `@apiNote`：公开 API 额外说明（示例、用法注意事项等）；
12. 内联标签使用规范：
    - `{@link ClassName#memberName}`：在描述中插入可点击引用，避免冗长全名。例如：  
      `Parses the given {@link java.nio.file.Path} instance.`
    - `{@code ...}`：在描述或列表中嵌入等宽代码片段。例如：  
      `Returns {@code null} if the input is empty.`
    - `{@literal ...}`：插入按字面量输出的字符串，避免被解析为 HTML/标签：  
      `Converts the string {@literal "<none>"} to an empty value.`
    - `{@value #CONSTANT}`：引用常量值，保持文档与代码同步。
13. 标题、列表、段落等 HTML 标签仅在确实提升可读性时使用，禁止为追求样式而滥用。
14. 使用任何标签或 HTML 元素时应遵循 "必要且充分" 原则；

## 7. Git 提交规范（Commit Message）

1. 提交信息必须简洁明确，并符合项目现有格式风格。你需要：
    - 查看近期的 Git 历史记录（`git log --oneline` 或 `git log -n 10`）；
    - 学习当前项目所使用的 commit 语言风格、结构模板等；
    - 避免使用口语、不相关内容或个人语气；
    - 若项目采用语义化提交规范（Conventional Commits），你必须严格遵守其格式。

2. 通用规范（若无特殊格式要求）：
    - ✅ 正确示例：
        - `Fix: resolve NPE in TaskHandler`
        - `Refactor: extract method from UserSessionManager`
        - `Add: support for async task execution`
        - `Docs: update Javadoc for ConfigLoader`
    - ❌ 错误示例：
        - `修复一下问题`
        - `update`
        - `提交代码`

3. 推荐格式（若尚无约定）：
    ```
    <类型>: <简要说明（英文首字母大写）>
   
    <可选说明：描述变更原因、影响范围、解决的问题等>
    ```

   常见类型包括：
    - `Add:` 新增功能、类、方法
    - `Fix:` 修复 bug
    - `Refactor:` 重构（不涉及功能行为改变）
    - `Docs:` 修改文档或注释
    - `Test:` 添加或修复测试
    - `Chore:` 杂项、配置项更新等

4. 合并（merge）请求前，务必 squash commit 或整理历史，保持提交记录清晰有序。

## 8. 自主学习与风格一致性要求

1. 你必须具备 "自我学习、自我适配" 的意识，凡遇风格不确定、规则模糊的场景，须优先：
    - 主动查阅现有代码；
    - 模仿当前模块的实现方式；
    - 避免引入破坏风格一致性的代码。

2. 遇到新模块、空白模块时：
    - 可以参考 `net.legacy.library.*` 包中的类为基本风格模板；
    - 所有新的命名风格、设计结构，必须与项目现有命名方式协调一致；
    - 若存在不确定性，且确认已经进行学习后仍然不清楚，可暂时标记 `// TODO: 确认命名规范` 后告诉开发组，待开发组确认后再定稿。

3. 在大型重构或多人协作中，应保持沟通、拉取最新代码，确保提交不会引起大面积冲突或风格割裂。

## 9. 注释与回答语言规范

### 9.1 注释语言统一为英文

1. 项目代码中的注释必须使用英文，除非明确说明或学习中确定项目注释大多为中文，包括但不限于：
    - 类注释；
    - 方法注释；
    - 行内注释（`//`）；
    - 多行注释（`/* */`）；
    - `TODO`、`FIXME` 标签说明；

2. 禁止使用夹杂式语言（中英混用）。
    - ✅ 正确示例：`// Handle WebSocket handshake logic`
    - ❌ 错误示例：`// WebSocket 握手逻辑`

### 9.2 注释复杂度要求适中（不得过度复杂或过度简易）

1. 注释内容应清晰说明目的、逻辑和关键边界条件，但不得赘述显而易见的代码含义：
    - ✅ 合理注释示例：
      `
      // Redirect to login page if user is not logged in
      `
    - ❌ 过度简略：
      `
      // Login
      `
    - ❌ 过度复杂：
      `
      // If the current user session state has not reached the expected "authenticated" status,
      // the system will attempt to guide the user back to the preset login page to complete the login process.
      // This step is necessary to ensure the security and validity of subsequent API calls.
      `

### 9.3 回答语言规范（即你与用户交流时）

1. 你在与用户交互时必须全程使用中文；
2. 若希望临时切换语言，可以明确告知，否则始终保持中文为默认沟通语言。

## 10. PR 评论与协作要求

1. 提交 Pull Request 时必须添加清晰的描述，解释本次更改的目的、修复的 bug 或新增的功能。
2. PR 审查流程：
    - 你需确保代码符合团队的风格规范，并对代码的可读性、逻辑性、性能等方面提供反馈。
    - 你需要给出具体的修改建议，除非已经可以接受合并，否则不接受简单的 "LGTM" 评论。

3. PR 提交时的分支命名：
    - 分支命名需清晰简洁，推荐格式：`<feature/bugfix>-<short-description>`，例如：`feature/login-logic`、
      `bugfix/fix-nullpointer`。

## 11. 日志规范

1. 正式功能代码中必须使用 `io.fairyproject.log.Log` 作为日志框架，不得引入其他日志组件（如 Log4j、SLF4J 等）。
    - 所有日志打印必须通过 `Log` 进行，统一管理，保证日志输出格式一致。
    - 必须明确日志级别的使用，避免使用过多或不必要的日志输出。

2. 测试代码中必须使用 `net.legacy.library.foundation.util.TestLogger` 替代正式日志类。
    - `TestLogger` 同样支持占位符格式（如 `%s`、`%d`），使用方式与 `Log` 一致。
    - 使用 `TestLogger` 便于测试日志隔离、日志捕获及测试用例输出控制。

3. 日志级别说明：
    - 使用适当的日志级别：
        - `debug()`：调试信息
        - `info()`：常规操作信息
        - `warn()`：警告信息，提醒开发人员注意
        - `error()`：错误信息，记录异常堆栈

4. 日志记录规范：
    - 日志内容应包含必要的上下文信息，如方法名、参数、调用状态等；
    - 异常日志必须包含异常栈信息，便于问题定位；
    - 日志信息应避免无意义输出，例如：
        - 不应出现 `Log.info("Enter function");`
        - 应改为 `Log.info("Entering executeTask(), taskId=%s", taskId);`

## 12. 字符串格式化规范

1. `Log` 类已经内建支持格式化字符串，直接使用占位符形式（例如 `%s`、`%d` 等）传递变量，无需手动格式化：
    - ✅ 正确示例：
      `
      Log.info("Successfully processed %d items", itemCount);
      `
    - ❌ 错误示例：
      `
      Log.info(String.format("Successfully processed %d items", itemCount));
      `
      或
      `
      Log.info("Successfully processed " + itemCount + " items");
      `

2. 避免使用不必要的字符串拼接或 `String.format()`：
    - `Log` 类的 `info()`、`debug()`、`warn()` 等方法自带占位符格式化功能，直接在消息中使用 `%s`、`%d` 占位符即可，性能更优。

3. 占位符类型：
    - `%s` 用于格式化字符串；
    - `%d` 用于格式化整数；
    - `%f` 用于格式化浮动数值；
    - `%x` 用于格式化十六进制整数等。

## 13. 导入规范

1. 导入规范：
    - 禁止使用 `import *`，必须明确导入所需类，避免不必要的类导入。
    - 示例：
        - ✅ 正确示例：`import io.fairyproject.SomeClass;`
        - ❌ 错误示例：`import io.fairyproject.*;`

2. 函数名、变量名应具有清晰的语义，遵循 camelCase 命名规范，不得使用不明确或缩写的名称。

## 14. Lombok 使用规范

1. 尽可能使用 Lombok 来减少样板代码，特别是：
    - 使用 `@Getter` 和 `@Setter` 代替手动编写 getter 和 setter 方法；
    - 使用 `@AllArgsConstructor` 和 `@NoArgsConstructor` 来简化构造器；
    - 使用 `@Builder` 来构建复杂对象，避免冗长的构造方法。

## 15. Java 版本与特性使用规范

1. 在编写代码之前，必须了解当前项目所使用的 Java 版本，确保所使用的语法和特性与该版本兼容。
    - 例如：若当前项目基于 Java 21，则可以使用增强 switch、Text Blocks 等特性；若是 Java 8，则禁止使用高版本特性。

2. 除非另有说明，否则禁止使用 `var`、`val` 等类型推断关键字，即使当前 Java 版本支持也不允许。
    - 所有变量必须显式声明类型，以提高代码可读性和明确性。

3. 建议在 Java 版本允许的前提下，合理使用语法糖以提升代码简洁度和表达力，例如：
    - 增强 switch 表达式（Java 12+）：
      `
      String result = switch (status) {
          case 1 -> "STARTED";
          case 2 -> "STOPPED";
          default -> "UNKNOWN";
      };
      `

    - Text Blocks（Java 13+）：
      `
      String json = """
          {
              "name": "test",
              "enabled": true
          }
          """;
      `

    - Pattern Matching for instanceof（Java 16+）：
      `
      if (obj instanceof String str) {
          Log.info("Length: %d", str.length());
      }
      `

4. 禁止使用项目未明确支持的 Java 语法与特性，避免在构建、部署等阶段产生兼容性问题。
    - 若不确定某语法是否可用，请先 web 搜索，查阅 Java 官方版本说明，或询问维护者确认项目支持范围。
