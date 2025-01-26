<div align="center">
    <img src="./logo.png">
    <br /><br />
    <a href="https://app.codacy.com/gh/LegacyLands/legacy-lands-library/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/cccd526f9bc94aaabc990dd65920cd21"/></a>
    <a><img alt="Issues" src="https://img.shields.io/github/issues/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Stars" src="https://img.shields.io/github/stars/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Forks" src="https://img.shields.io/github/forks/LegacyLands/legacy-lands-library"></a>
    <a><img alt="License" src="https://img.shields.io/github/license/LegacyLands/legacy-lands-library"></a>
    <br /><br />
    <p>基于 <a href="https://github.com/FairyProject/fairy" target="_blank">Fairy Framework</a>，作为插件运行，旨在封装多种现有库来简化 <a href="https://github.com/PaperMC/Folia" target="_blank">Folia</a> 插件的开发过程。</p>
</div>

## 📚 概述

这是一个基于 [Fairy Framework](https://github.com/FairyProject/fairy) 构建的综合性库，为现代 Minecraft 插件开发提供了必要的工具和实用程序。虽然针对 Folia 进行了优化，但完全兼容 Spigot 和 Paper 平台。

## 🎯 核心模块

- [🎯 **annotation**](annotation/README.md) - 强大的注解处理框架，具有灵活的扫描选项和生命周期管理
- [🛠 **commons**](commons/README.md) - 基础工具集，包括 VarHandle 注入、任务调度和 JSON 操作
- [⚙️ **configuration**](configuration/README.md) - 基于 SimplixStorage 构建的灵活配置框架，支持序列化
- [🗄️ **mongodb**](mongodb/README.md) - 基于 Morphia 的精简 MongoDB 集成，用于高效数据持久化
- [🚀 **cache**](cache/README.md) - 集成 Caffeine 和 Redis 的多级缓存系统，具有全面的锁机制
- [👤 **player**](player/README.md) - 高性能玩家数据管理，支持多级缓存和实时同步
- 🔒 **security** - *即将推出*

## 🚀 使用方法

### 分发包说明

从 [Actions](https://github.com/LegacyLands/legacy-lands-library/actions) 下载：
- `-javadoc`：生成的 API 文档
- `-plugin`：可直接用于服务器的编译插件
- `-sources`：包含源代码和编译类（推荐用于开发）

### Maven 仓库

首先配置 GitHub 认证（[了解更多](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)）

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
    implementation("net.legacy.library:模块名称:版本号")
}
```

## 🌟 特性

- **模块化架构**：各模块独立但无缝集成
- **类型安全**：全面的泛型支持和编译时检查
- **性能优先**：针对高吞吐量服务器环境优化
- **开发友好**：丰富的文档和直观的 API
- **生产就绪**：在实际应用中经过验证

## 🤝 贡献

我们欢迎各种形式的贡献：
- 报告问题
- 提出功能建议
- 提交代码改进

## 📖 文档

- 每个模块的 README 中都有详细文档
- `-javadoc` 包中包含生成的 JavaDoc
- [English Version](README.md)

## 💬 社区

- QQ群：1022665227
- [Github Issues](https://github.com/LegacyLands/legacy-lands-library/issues)
- [哔哩哔哩](https://space.bilibili.com/1253128469)（中文教程）

## ❤️ 赞助商

本项目由 [LegacyLands](https://github.com/LegacyLands) 全力赞助和维护。

![legacy-lands-logo.png](./legacy-lands-logo.png)

## ⭐ Star 历史

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

---

由 [LegacyLands Team](https://github.com/LegacyLands) 用 ❤️ 制作