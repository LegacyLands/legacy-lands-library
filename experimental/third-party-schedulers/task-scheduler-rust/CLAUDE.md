# ✅ Rust 开发与协作规范（学习与编程行为指南）

> 本规范适用于你参与的全部 Rust 开发任务，条款为强制性，除非用户显式豁免，任何条目都不得忽视或删减。
>
> - 每次输出前必须深度理解项目背景、用户意图和技术栈特征；
> - 当回答依赖外部知识或信息不确定时，先查询至少一份权威资料（官方文档、RFC、源码）再作结论；
> - 引用外部资料须在回复中注明来源，可使用链接或版本号，保证可追溯；
> - 若用户需求含糊，先用一句话复述已知信息，并列出关键澄清问题，待用户确认后再继续；
> - 同一次回复中的术语、变量名、逻辑描述保持一致；发现冲突必须立即修正；
> - 仅回答与问题直接相关内容，避免冗余、无关扩展或教程式铺陈；
> - 面对复杂需求，先拆分为若干可管理子任务，在输出中按子任务顺序呈现，便于用户跟进；
> - 所有技术输出必须建立在准确、深度且详细思考过的基础之上，拒绝机械生成与无脑填充。
>
> 如果你已经了解所有的规范，你需要在用户第一次进行对话时：
>   - 说明 "我已充分了解 Rust 开发与写作规范（1.1）。"；
>   - 使用 PromptX MCP 切换角色为 rust-architect-ultra；
>   - 使用 PromptX MCP 读取目前所有记忆；
>   - 阅读项目下 TODO.md 文件；
>   - 根据下方要求，详细且深入地了解整个项目（包括但不限于源码、部署、配置、脚本、测试等）；
>   - 确保完全了解项目后继续分析用户的需求并执行。

## 1. 代码与文档编写规范

1. 你需要先查看其他模块的实现，从整个项目目录中查找并学习所有模块；
2. 主动学习、模仿现有代码的结构与风格，包括：
    - 缩进与换行格式（4个空格缩进）；
    - 命名习惯（snake_case、PascalCase 等）；
    - 文档注释风格（`///` 格式）；
    - 错误处理模式（`Result<T, E>` 和 `?` 操作符）。

## 2. 规范流程

1. 源码学习与 ToDo 拆分：
    - 在开始任务之前，必须先深入阅读并理解完整源代码（而非仅依赖文档）；
    - 借助 `TodoWrite` / `TodoRead` 等内建工具，将任务拆分到文件 / 函数级别，生成颗粒度清晰、精细且标准的 ToDo 清单；
    - 在整个开发过程中持续细化并实时更新 ToDo。
2. 编译检查：
    - 使用 `cargo build` 和 `cargo check` 进行编译检查；若出现错误必须修复后重新编译，直至编译通过；
    - 若构建目录过大导致磁盘空间不足，可执行 `cargo clean` 释放空间。
3. 测试规范：
    - 准备阶段：熟悉项目中已有的测试模块和 `#[cfg(test)]` 结构；阅读 `scripts/` 目录下现有 Bash 脚本，避免重复编写。
    - 组织形式：单元测试放在与代码同文件的 `#[cfg(test)]` 模块；集成测试放在 `tests/` 目录。
    - 编写要求：使用描述性测试函数名，确保每个测试仅验证一个功能点；覆盖核心业务逻辑与边界条件，避免无意义断言；核心业务代码力求接近
      100% 覆盖；使用 `assert_eq!`、`assert_ne!` 等断言宏。
    - 测试工具与运行：首选复用 `scripts/` 目录中的 Bash 脚本；可用 `proptest` 进行属性测试，`mockall`
      进行模拟测试；调试专用脚本在使用后必须删除。
    - 通过标准：全部测试通过后再次运行完整测试流程确保无回归。
4. 依赖管理：
    - 若需要为当前 crate 引入其他 crate，先检查是否会造成循环依赖；
    - 生产依赖使用 `[dependencies]`；测试依赖使用 `[dev-dependencies]`。
5. 格式化与静态检查：
    - 使用 `cargo fmt` 格式化代码；
    - 使用 `cargo clippy` 检查代码质量；所有警告应被修复或合理忽略。
6. 文档与 PromptX 记录：
    - 除非用户明确说明，否则不应撰写任何示例、README、部署等文件；
    - 若有必要可使用 PromptX MCP 记录详细内容。
7. TODO 同步：
    - 完成新任务或积累经验时，使用 PromptX MCP 记录详细内容（如经验、踩坑记录等）；
    - 在 `TODO.md` 同步更新已完成事项与待办事项的详细说明。

## 3. 命名规范（类型、字段、变量、函数、模块）

1. 命名应清晰、准确、语义明确：
    - ✅ 正确示例：`result: Result<(), Error>`，`config: Config`，`user_service: UserService`
    - ❌ 错误示例（过于简单化）：`r: Result<(), Error>`，`c: Config`
    - ❌ 错误示例（过于复杂化）：`result_when_failed_to_initialize_database_connection: Result<(), Error>`
    - ❌ 错误示例（无意义）：`some_thing: SomeThing = ...`
2. 使用 Rust 标准命名约定：
    - 类型（结构体、枚举、特征）：`PascalCase`
    - 函数、变量、模块：`snake_case`
    - 常量：`SCREAMING_SNAKE_CASE`
    - 生命周期参数：短小明确，如 `'a`、`'b`
3. 出现命名重复时，应提升语义层级，而非简单加数字：
    - ✅ 正确示例：`cache: Cache; redis_cache: RedisCache`
    - ❌ 错误示例：`cache: Cache; cache2: Cache`

## 4. 数据处理优先使用迭代器和函数式编程风格

1. 使用 `Iterator` trait 的方法链进行数据处理：`.map()`、`.filter()`、`.collect()` 等；
2. 避免使用传统 `for` 循环完成可以用迭代器完成的链式操作；
3. 优先使用 `Vec::iter()` 和 `Vec::into_iter()` 而非索引访问；
4. 确保代码简洁、函数式、可读性强。

## 5. 闭包表达式需保持简洁，避免冗余语法

1. ✅ 正确用法（单行时省略大括号和显式返回，可使用函数指针）：
    - `|x| x + 1`
    - `items.iter().map(Item::process)`
    - `|| "default_value"`
2. ❌ 错误用法（冗余、过度包装）：
    - `|x| { x + 1 }` → 简化为 `|x| x + 1`
    - `|| { "default_value" }` → 简化为 `|| "default_value"`
    - `items.iter().map(|x| { x.process() })` → 应使用方法引用

## 6. 文档注释编写规范

1. 你必须优先参考现有项目中的文档注释风格作为基准；
2. 若该项目中无类似内容，参考 Rust 官方 API 设计指南的文档规范；
3. 文档注释结构应包括：
    - 简洁说明性首句（以动词开头），句末加英文句号；
    - 使用 `///` 进行外部文档注释，`//!` 进行模块级注释；
    - 标准结构：描述 → 参数说明 → 返回值 → 错误说明 → 示例；
    - 不遗漏边界条件、默认行为或错误说明。
4. 必须包含的章节（如适用）：
    - `# Examples` - 提供使用示例，展示*为什么*使用而非仅仅*如何*使用；
    - `# Errors` - 描述可能的错误条件；
    - `# Panics` - 说明函数可能 panic 的场景；
    - `# Safety` - 对于 unsafe 函数，解释调用者需要保证的不变性；
5. 禁止使用不规范注释格式，特别是：
    - ❌ `/// param: the param`
    - ✅ `/// Processes the user input and returns the validation result`
6. 示例代码规范：
    - 使用 `?` 进行错误处理而非 `unwrap()` 或 `try!()`；
    - 对于可能失败的示例，使用隐藏的 `main() -> Result<(), Box<dyn Error>>`；
    - 确保示例代码可以编译运行；
7. 必须模仿已有项目注释中的语气、缩进、空行规则，不得混用不同风格；
8. 链接和引用规范：
    - 使用 `[`Type`]` 语法链接到相关类型；
    - 使用 `[`module::function`]` 链接到函数；
    - 避免过长的完整路径，使用简短引用；
9. 禁止写没必要的用法示例，除非真正有助于理解；
10. 不需要为非 `pub` 项目写文档注释，除非确有必要；
11. 其他标签使用规范：
    - 使用标准 Markdown 语法进行格式化；
    - 使用代码块标记 Rust 代码：\`\`\`rust
    - 谨慎使用 HTML 标签，仅在确实提升可读性时使用。

## 7. Git 提交规范（Commit Message）

1. 提交信息必须简洁明确，并符合项目现有格式风格。你需要：
    - 查看近期的 Git 历史记录（`git log --oneline` 或 `git log -n 10`）；
    - 学习当前项目所使用的 commit 语言风格、结构模板等；
    - 避免使用口语、不相关内容或个人语气；
    - 若项目采用语义化提交规范（Conventional Commits），你必须严格遵守其格式。
2. 通用规范（若无特殊格式要求）：
    - ✅ 正确示例：
        - `fix: resolve panic in async task handler`
        - `refactor: extract error handling into separate module`
        - `feat: add support for custom task schedulers`
        - `docs: update API documentation for TaskRegistry`
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
    - `feat:` 新增功能、结构体、函数
    - `fix:` 修复 bug
    - `refactor:` 重构（不涉及功能行为改变）
    - `docs:` 修改文档或注释
    - `test:` 添加或修复测试
    - `chore:` 依赖更新、配置项更新等
    - `perf:` 性能优化

## 8. 自主学习与风格一致性要求

1. 你必须具备 "自我学习、自我适配" 的意识，凡遇风格不确定、规则模糊的场景，须优先：
    - 主动查阅现有代码；
    - 模仿当前 crate 的实现方式；
    - 避免引入破坏风格一致性的代码。
2. 遇到新模块、空白模块时：
    - 可以参考标准库或知名 crate 的风格作为基本模板；
    - 所有新的命名风格、设计结构，必须与项目现有命名方式协调一致；
    - 若存在不确定性，可暂时标记 `// TODO: 确认风格规范` 后告诉开发组。
3. 在开始编码前，必须熟悉并优先使用以下 Rust 生态系统功能，避免重复实现：
    - 异步处理：优先使用 `tokio` 或 `async-std` 等成熟运行时；
    - 错误处理：使用 `thiserror`、`anyhow` 等标准错误处理库；
    - 序列化：使用 `serde` 进行数据序列化/反序列化；
    - 日志记录：使用 `log`、`tracing` 等日志框架；
    - HTTP 客户端/服务器：使用 `reqwest`、`axum`、`actix-web` 等；
4. 若现有依赖已满足需求，禁止自写替代实现；如确认存在缺口，需在 Pull Request 描述中说明：
    - 已检索过的相关 crate 或功能；
    - 为什么现有实现不足；
    - 新实现的范围与改进点。

## 9. 注释与回答语言规范

### 9.1 注释语言统一为英文

1. 项目代码中的注释必须使用英文，包括但不限于：
    - 文档注释（`///`）；
    - 行内注释（`//`）；
    - 块注释（`/* */`）；
    - `TODO`、`FIXME` 标签说明；
2. 禁止使用夹杂式语言（中英混用）。
    - ✅ 正确示例：`// Handle async task execution`
    - ❌ 错误示例：`// 处理 async task 执行`

### 9.2 注释复杂度要求适中

1. 注释内容应清晰说明目的、逻辑和关键边界条件，但不得赘述显而易见的代码含义：
    - ✅ 合理注释示例：`// Retry connection with exponential backoff`
    - ❌ 过度简略：`// Retry`
    - ❌ 过度复杂：冗长解释显而易见的逻辑

### 9.3 回答语言规范

1. 你在与用户交互时必须全程使用中文；
2. 若希望临时切换语言，可以明确告知，否则始终保持中文为默认沟通语言。

## 10. PR 评论与协作要求

1. 提交 Pull Request 时必须添加清晰的描述；
2. PR 审查流程要求提供具体的修改建议；
3. PR 提交时的分支命名：
    - 推荐格式：`<feature/bugfix>-<short-description>`
    - 例如：`feature/async-task-support`、`bugfix/fix-memory-leak`

## 11. 日志规范

1. 根据项目选择合适的日志框架：
    - 简单项目：使用 `log` crate 配合 `env_logger`
    - 复杂项目：使用 `tracing` 进行结构化日志记录
2. 日志级别说明：
    - `trace!()`: 非常详细的调试信息
    - `debug!()`: 调试信息
    - `info!()`: 常规操作信息
    - `warn!()`: 警告信息
    - `error!()`: 错误信息
3. 日志记录规范：
    - 日志内容应包含必要的上下文信息；
    - 错误日志必须包含错误信息，使用 `{:?}` 或 `{:#?}` 格式化；
    - 避免无意义输出，如单纯的 "Enter function"；
4. 使用结构化日志时，优先使用字段记录而非字符串插值：
    - ✅ `info!(user_id = %user.id, "User logged in")`
    - ❌ `info!("User {} logged in", user.id)`

## 12. 字符串格式化规范

1. 优先使用 `format!` 宏族进行字符串格式化：
    - `format!()`: 创建格式化字符串
    - `println!()`: 打印到标准输出
    - `eprintln!()`: 打印到标准错误
2. 使用合适的格式说明符：
    - `{}`: 默认格式化
    - `{:?}`: Debug 格式化
    - `{:#?}`: 美化的 Debug 格式化
    - `{:x}`: 十六进制格式化
3. 避免不必要的字符串分配，考虑使用 `&str` 而非 `String`。

## 13. 导入规范

1. 导入规范：
    - 按照标准库、第三方 crate、本地模块的顺序组织导入；
    - 使用 `use` statements 明确导入所需项目；
    - 避免使用过于宽泛的导入，如 `use module::*`；
2. 推荐导入风格：
    ```rust
    // 标准库
    use std::collections::HashMap;
    use std::sync::Arc;
    
    // 第三方 crate
    use serde::{Deserialize, Serialize};
    use tokio::sync::Mutex;
    
    // 本地模块
    use crate::error::TaskError;
    use crate::models::Task;
    ```

## 14. 依赖管理规范

1. 合理使用 Cargo features：
    - 将可选功能设为 feature gates；
    - 避免默认启用所有 features；
2. 版本管理：
    - 使用语义化版本控制；
    - 对于库 crate，谨慎处理破坏性变更；
3. 依赖选择原则：
    - 优先选择维护活跃、文档完善的 crate；
    - 避免引入过多小型依赖造成依赖膨胀；
    - 定期更新依赖版本，使用 `cargo audit` 检查安全漏洞。

## 15. Rust 版本与特性使用规范

1. 明确项目使用的 Rust 版本和 edition：
    - 在 `Cargo.toml` 中明确指定 `edition`（推荐 "2021" 或更新）；
    - 确保所使用的语法和特性与该版本兼容；
2. 合理使用现代 Rust 特性：
    - 使用 `match` 表达式和模式匹配；
    - 使用 `if let` 和 `while let` 简化代码；
    - 合理使用 `async/await` 进行异步编程；
3. 避免使用不稳定特性，除非项目明确要求使用 nightly Rust。

## 16. 属性和宏使用约定

1. 必要属性的使用：
    - 对于可能未使用的项目，合理使用 `#[allow(dead_code)]`；
    - 对于 FFI 和特殊用途，使用 `#[repr(C)]` 等属性；
    - 使用 `#[derive()]` 自动派生常见 trait。
2. 条件编译：
    - 使用 `#[cfg()]` 进行平台特定代码；
    - 使用 `#[cfg(test)]` 标记测试代码；
3. 宏使用原则：
    - 优先使用声明式宏（`macro_rules!`）而非过程宏；
    - 确保宏的卫生性和安全性；
    - 为复杂宏提供充分的文档。

## 17. 错误处理规范

1. 使用 `Result<T, E>` 进行错误处理：
    - 优先使用 `?` 操作符进行错误传播；
    - 避免使用 `unwrap()` 和 `expect()`，除非在测试代码中；
2. 错误类型设计：
    - 使用 `thiserror` 创建自定义错误类型；
    - 提供有意义的错误信息和上下文；
3. 错误处理最佳实践：
    - 在适当的层次处理错误；
    - 使用 `anyhow` 进行应用级错误处理；
    - 记录错误但避免重复记录。

## 18. 并发和异步编程规范

1. 异步编程：
    - 使用 `async fn` 定义异步函数；
    - 合理使用 `await` 关键字；
    - 避免在异步代码中使用阻塞操作；
2. 并发原语：
    - 使用 `Arc<Mutex<T>>` 或 `Arc<RwLock<T>>` 进行线程间共享；
    - 优先使用 `tokio` 的异步同步原语；
3. 生命周期和所有权：
    - 明确理解借用检查器的规则；
    - 合理使用生命周期参数；
    - 避免不必要的克隆操作。

## 19. TODO 与 FIXME 约定

1. 每个 `TODO` 或 `FIXME` 必须包含责任人标识或任务链接：
   ```rust
   // TODO(username): implement retry logic for failed tasks
   // FIXME(issue-123): handle edge case in parser
   ```
2. `TODO` 代表可延后但需完成的功能，`FIXME` 表示已知问题，优先级高于 `TODO`；
3. 禁止出现无上下文信息的注释，如 `// TODO: fix`。

## 20. Unsafe 与 FFI 代码约定

1. 基本原则
    - 必须最小化 `unsafe` 代码范围，并尽可能将其隔离到单一模块。
    - 在可能的情况下，优先使用社区成熟的绑定库（如 `libc`、`windows-sys`、`openssl-sys` 等），避免手写 FFI 声明。
2. 注释要求（// SAFETY:）
    - 每个 `unsafe` 代码块都必须紧随一行 `// SAFETY:` 注释，清晰说明为什么此处安全、调用者需要保证的前提以及违反前提会导致的后果。
      ```rust
      // SAFETY: `ptr` comes from `Vec::into_raw_parts`, hence non-null and properly aligned.
      unsafe { ptr.write_bytes(0, len) };
      ```  
3. FFI 边界
    - 所有 `extern "C"` 函数必须放在 `ffi` 模块或带有 `ffi` 后缀的文件中，并导出最小必要接口；
    - 如果需要跨语言共享数据结构，应使用 `#[repr(C)]` 或 `#[repr(transparent)]` 并避免包含 `Vec<T>`、`String` 等具有内部指针的类型；
    - 禁止直接操作裸指针或手写 `mem::transmute`，应封装在安全抽象中：
      ```rust
      pub struct FooHandle(*mut ffi::Foo); // Opaque handle
 
      impl Drop for FooHandle {
          fn drop(&mut self) {
              unsafe { ffi::foo_destroy(self.0) }; // SAFETY: handle created by foo_create
          }
      }
      ```  
4. 资源管理
    - 对外部资源（文件句柄、C 结构体指针、socket 等）必须封装成 RAII 类型，实现 `Drop` 以确保正确释放；
    - 对同一资源的多次释放（double-free）或使用后释放（use-after-free）属于严重安全缺陷，CI 必须启用 Miri 或 Address
      Sanitizer 进行检测。
5. 错误传播与异常边界
    - C API 返回错误码时，Rust 侧应映射为 `Result<T, Error>`；
    - 如果 C 函数可能抛出异常或 `longjmp`，必须在文档中标注并通过信号/回调等方式处理；
    - 禁止在 FFI 回调跨越语言边界传播 Rust panic，也禁止让 C 异常直接进入 Rust 代码。
6. 编译与 LTO 设置
    - 在 Release 配置中启用 `lto = "thin"`、`codegen-units = 1` 以减小二进制与符号暴露面；
    - 使用 `cargo-deny` 或 `cargo auditable` 检查 FFI 依赖的许可证和安全公告。
7. 安全审计流程
    - 新增或修改 `unsafe`/FFI 代码时，PR 描述必须列出：
        - 触及的 `unsafe` 块位置及行号；
        - 每个 `// SAFETY:` 注释的简要摘要；
        - 引入外部库名称、版本及其许可证；

## 21. 运行环境与部署规范

1. Script-first 原则
    - 仅允许使用 Bash 脚本，且若明确只会执行一次，则直接自行执行而不要写没有意义的多余脚本；
    - 部署相关任务（Docker 镜像构建、Kubernetes 资源、Grafana 监控等）必须先检查 `scripts/` 目录是否已有对应 Bash 脚本；
    - 若已有脚本，可复用或扩展；若是添加新功能、重写、更改，严禁创建全新脚本。若确定有必要，则创建新脚本后放入 `scripts/` 目录；
    - 根据评估，尽可能所有自动化步骤都通过脚本完成，而非手动或临时命令，但严禁为明知只会执行一次的命令新建脚本。
2. Docker 构建
    - 使用多阶段（multi-stage）构建精简镜像体积，并固定基础镜像标签（如 `rust:1.76-slim`）；
    - 镜像内运行进程须使用非 root 用户，必要时在 `Dockerfile` 中创建并切换用户；
    - 构建产物应符合 OCI 规范，镜像标签包含版本与 Git 提交哈希，例如 `my-app:${VERSION}-${GIT_SHA}`；
    - 若需额外构建参数，应通过 `--build-arg` 或环境变量传入，而非硬编码。
3. Kubernetes 部署
    - 推荐使用 Helm Chart 或 Kustomize 管理清单；文件放在 `deploy/` 对应目录；
    - 必须为每个 Pod 配置 `readinessProbe`、`livenessProbe`、`resources.requests/limits`；
    - 机密信息一律通过 Kubernetes Secret 或外部密钥管理（如 Vault）注入，不得写入镜像或仓库；
    - 部署脚本应支持 `kubectl apply` 与回滚（`kubectl rollout undo`），并在 CI 中可自动执行。
4. 监控与日志（Grafana / Prometheus）
    - 若项目已有监控脚本（如 `scripts/setup_grafana.sh`），优先复用；无则编写脚本创建数据源、仪表盘 JSON 并自动导入；
    - 应暴露 Prometheus 指标端点（默认 `/metrics`），并在 Helm/Kustomize 中配置 `ServiceMonitor` 或 `PodMonitor`；
    - 日志统一输出到 stdout/stderr，保证容器环境下可被采集。
5. CI/CD 集成
    - 在 CI 中：
        1. 执行 `cargo build --release` → 单元/集成测试 → `docker build` → 推送镜像到 Registry；
        2. 触发部署脚本更新 Kubernetes（滚动更新）并校验健康状态；
        3. 若部署失败则自动回滚并标红流水线。
    - 所有 CI/CD YAML 或 Workflow 文件放置于 `.github/workflows/`、`.gitlab/ci.yml` 或 `.ci/`。
6. 环境变量与配置管理
    - 非机密配置通过环境变量或 `config/{env}.toml` 注入；机密配置通过 Secret 管理器；
    - 创建脚本 `scripts/render_env.sh`（示例）统一生成或校验 `.env` 文件，避免手动遗漏。
