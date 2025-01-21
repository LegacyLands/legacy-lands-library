<div align="center">
    <img src="./logo.png">
    <br /><br />
    <a href="https://app.codacy.com/gh/LegacyLands/legacy-lands-library/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/cccd526f9bc94aaabc990dd65920cd21"/></a>
    <a><img alt="Issues" src="https://img.shields.io/github/issues/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Stars" src="https://img.shields.io/github/stars/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Forks" src="https://img.shields.io/github/forks/LegacyLands/legacy-lands-library"></a>
    <a><img alt="License" src="https://img.shields.io/github/license/LegacyLands/legacy-lands-library"></a>
    <br /><br />
    <p>基于 <a href="https://github.com/FairyProject/fairy" target="_blank">Fairy Framework</a>，作为插件运行，目标是封装多种已有库来简化 <a href="https://github.com/PaperMC/Folia" target="_blank">Folia</a> 插件的开发过程。</p>
</div>

## 概览

本项目整体依赖 [Fairy Framework](https://github.com/FairyProject/fairy)。并不会包含大量冗余或重复打包的大型库。目前最适用于 Folia 场景，但其实也可以在 Spigot 与 Paper 等平台使用。

## 使用方法

在 [Actions](https://github.com/LegacyLands/legacy-lands-library/actions) 下载的压缩包中：  
• "-javadoc" 包含生成后的 Javadoc。  
• "-plugin" 仅包含编译完成的 class 文件，用于直接作为插件运行。  
• "-sources" 不但包含编译好的 class 文件，同时也带有源代码，便于 IDE 内查看注释。

各个模块的具体使用方式都写在对应模块的 `README.md` 文件里。

值得注意的是，整个库完全依赖 [Fairy Framework](https://github.com/FairyProject/fairy)，极大简化了我们的开发流程，并提供了各种实用功能。其中也依赖了 [fairy-lib-plugin](https://github.com/FairyProject/fairy-lib-plugin)。

我们推荐开发者引入 "-sources" 包，以便在 IDE 中查看 Javadoc 以及源代码实现，从而更方便地理解与使用。

如果您想以插件形式运行（推荐方式），则直接将 "-plugin" 文件放入插件文件夹即可。  
**请务必注意模块之间的依赖关系！**

### API 配置示例

使用前需先在本地或 CI 环境中配置 GitHub 用户名和 Token（参考 [GitHub Docs](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens)）。例如在 Gradle 的 Kotlin DSL 中：

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
    implementation("net.legacy.library:subproject-name:version")
}
```

## 模块列表

- [annotation](annotation/README.md)  
- [commons](commons/README.md)  
- [configuration](configuration/README.md)  
- [mongodb](mongodb/README.md)  
- [cache](cache/README.md)  
- [player](player/README.md) - 开发中  
- security - 暂未开始

## 赞助商

本项目由 [LegacyLands](https://github.com/LegacyLands) 全力赞助与维护。

![legacy-lands-logo.png](./legacy-lands-logo.png)

## Star 记录

感谢各位开发者对本库的支持！

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

## 相关教程（简体中文）

目前我们正在规划并制作 [Fairy Framework](https://github.com/FairyProject/fairy) 的使用教程视频和相关文档，后续会通过哔哩哔哩 [LegacyLands 官方账号](https://space.bilibili.com/1253128469) 进行发布，欢迎关注！ 


## 技术交流

我们欢迎各位开发者在遇到问题或有功能建议时来到这些地方进行反馈，这将帮助与激励我们不断完善它，并一直成长！

- QQ Group: 1022665227
- [Github Issues](https://github.com/LegacyLands/legacy-lands-library/issues)