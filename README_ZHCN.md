<div align="center">
    <img src="./logo.png">
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

这是一个基于 [Fairy Framework](https://github.com/FairyProject/fairy) 构建的综合性库，为现代 Minecraft
插件开发提供了必要的工具和实用程序。它是跨平台的，支持 Spigot Paper 和 Folia。

## 核心模块

- [**annotation**](annotation/README_ZHCN.md) - 强大的注解处理框架，具有灵活的扫描选项和生命周期管理。
- [**commons**](commons/README_ZHCN.md) - 基础工具集，包括 VarHandle 注入、任务调度、虚拟线程调度、JSON 操作和随机对象生成。。
- [**configuration**](configuration/README_ZHCN.md) - 基于 SimplixStorage 构建的灵活配置框架，支持序列化。
- [**mongodb**](mongodb/README_ZHCN.md) - 基于 Morphia 的精简 MongoDB 集成，用于高效数据持久化。
- [**cache**](cache/README_ZHCN.md) - 集成 Caffeine 和 Redis 的多级缓存系统，具有全面的锁机制与通用的线程安全资源管理框架。
- [**player**](player/README_ZHCN.md) - 高性能玩家数据管理，支持多级缓存和实时同步。
- [**script**](script/README_ZHCN.md) - 这是一个强大、灵活、可拓展且高性能的脚本执行引擎封装，支持 `Rhino`, `Nashorn`, `V8`
  三种 `JavaScript` 引擎。

- [**experimental**](experimental/README.md) - 一些实验性的模块，可以显著提高性能，但可能过于复杂而无法使用或暂时不稳定。
    - [**third-party-schedulers**](experimental/third-party-schedulers/README.md) - 通过 gRPC
      外部任务调度器实现分布式任务处理，从而实现大型插件解耦和性能提升（适用于不需要访问 Bukkit API 的后端）。
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

### Jenkins

[项目链接](http://129.226.219.222:8080/job/legacy-lands-library/)

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

## Star 历史

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

---
