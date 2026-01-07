<div align="center">
    <img src="./logo.png" alt="legacy-lands-library-logo">
    <br /><br />
    <a href="https://app.codacy.com/gh/LegacyLands/legacy-lands-library/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/cccd526f9bc94aaabc990dd65920cd21"/></a>
    <a><img alt="Issues" src="https://img.shields.io/github/issues/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Stars" src="https://img.shields.io/github/stars/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Forks" src="https://img.shields.io/github/forks/LegacyLands/legacy-lands-library"></a>
    <a><img alt="License" src="https://img.shields.io/github/license/LegacyLands/legacy-lands-library"></a>
    <br /><br />
    <p>基于 <a href="https://github.com/FairyProject/fairy" target="_blank">Fairy Framework</a>，作为插件运行，旨在封装多种现有库来简化插件的开发过程。</p>
</div>

## 概述

这是一个基于 [Fairy Framework](https://github.com/FairyProject/fairy) 构建的模块化插件工具库，采用模块化设计，深度利用 Java 21 现代化特性。
为现代 Minecraft 插件开发提供了必要的工具和实用程序，跨平台支持 Spigot、Paper 和 Folia。

## 核心模块

- [**foundation**](foundation/README_ZHCN.md) - 核心基础模块，为所有库模块提供基本的测试基础设施、工具类和基础抽象。
- [**annotation**](annotation/README_ZHCN.md) - 强大的注解处理框架，具有灵活的扫描选项和生命周期管理。
- [**aop**](aop/README_ZHCN.md) - 企业级面向切面编程框架，具有 ClassLoader 隔离功能，提供性能监控、线程安全、日志记录和异常处理切面。
- [**commons**](commons/README_ZHCN.md) - 基础工具集，包括 VarHandle 注入、任务调度、虚拟线程调度、JSON 操作和随机对象生成。。
- [**configuration**](configuration/README_ZHCN.md) - 基于 SimplixStorage 构建的灵活配置框架，支持序列化。
- [**mongodb**](mongodb/README_ZHCN.md) - 基于 Morphia 的精简 MongoDB 集成，用于高效数据持久化。
- [**cache**](cache/README_ZHCN.md) - 集成 Caffeine 和 Redis 的多级缓存系统，具有全面的锁机制与通用的线程安全资源管理框架。
- [**player**](player/README_ZHCN.md) - 企业级分布式数据管理框架，构建高性能实体-关系数据层。能够 **无缝处理数千实体间** 的关系网络与状态同步。
- [**script**](script/README_ZHCN.md) - 这是一个强大、灵活、可拓展且高性能的脚本执行引擎封装，支持 `Rhino`, `Nashorn`, `V8`
  三种 `JavaScript` 引擎。

- [**experimental**](experimental/README.md) - 一些实验性的模块，可以显著提高性能，但可能过于复杂而无法使用或暂时不稳定。
    - [**third-party-schedulers**](experimental/third-party-schedulers/README.md) - 通过 gRPC
      外部任务调度器实现分布式任务处理（目前基于 Rust 实现），从而实现大型插件解耦和性能提升（适用于不需要访问 Bukkit API 的后端）。
      非常适合机器学习、反作弊大数据计算、数学和类似应用。

- **security** - *即将推出。*

## 使用方法

虽然版本号目前仍是 SNAPSHOT，但这并不代表它不能用于生产环境。事实上，我们计划在一个大型插件开发项目中广泛使用此版本，并在充分验证其稳定性和功能性后，发布第一个正式版本。

### 分发包说明

从 [Actions](https://github.com/LegacyLands/legacy-lands-library/actions) 下载：

- `-javadoc`：生成的 API 文档
- `-plugin`：可直接用于服务器的编译插件
- `-sources`：包含源代码和编译类（推荐用于开发）

每个模块的 `README` 中都有详细文档

### Maven 仓库

首先配置 GitHub
认证（[了解更多](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)）

```kotlin
repositories {
    maven {
        url = uri("https://maven.pkg.github.com/LegacyLands/legacy-lands-library")
        credentials {
            username = project.findProperty("gpr.user") as String? ?: System.getenv("USERNAME")
            password = project.findProperty("gpr.key") as String? ?: System.getenv("TOKEN")
        }
    }
}

dependencies {
    compileOnly("net.legacy.library:模块名称:版本号")
}
```

## 社区

- [English Version](README.md)
- [QQ 群](http://qq.legacylands.cn)
- [Github Issues](https://github.com/LegacyLands/legacy-lands-library/issues)
- [哔哩哔哩](https://space.bilibili.com/1253128469)（中文教程）

我们将在 [哔哩哔哩](https://space.bilibili.com/1253128469)
频道发布更多关于此模块和 [Fairy Framework](https://github.com/FairyProject/fairy) 的开发教程与经验分享！

## 赞助商

本项目由 [LegacyLands](https://github.com/LegacyLands) 全力赞助和维护。

![legacy-lands-logo.png](./legacy-lands-logo.png)

[Luminol](https://github.com/LuminolMC/Luminol) 是一个基于 Folia 的分支，具有许多有用的优化、可配置的原版特性和更多的 API
支持。

![luminol-mc-logo.png](./luminol-mc-logo.png)

本项目由 YourKit 提供的 [Java](https://www.yourkit.com/java/profiler/) 与 [.NET](https://www.yourkit.com/dotnet-profiler/) 性能分析工具驱动。

<img src="./yklogo.png" width="300">

## 贡献

我们热烈欢迎更多开发者加入我们的开源项目，贡献自己的力量。无论是分享想法、创建 Issue，还是提交 Pull Request，每一份贡献都是有意义的！

我们对使用 AI 非常宽容。您可以使用任何 AI 来编写和贡献代码，只要它能正常工作并满足 [CLAUDE.md](CLAUDE.md) 中的要求即可。

## 未来计划

我们将这个库作为维持整个服务器运行（LegacyLands Minecraft 服务器）的主要依赖，并正在积极引入 Scala 3。未来将进行小部分的非破坏性迁移到
Scala 3，利用其优越的类型系统、并发模型和函数式编程能力来获得更好的性能和可维护性。

## Star 历史

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

---
