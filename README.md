<div align="center">
    <img src="./logo.png">
    <br /><br />
    <a href="https://app.codacy.com/gh/LegacyLands/legacy-lands-library/dashboard?utm_source=gh&utm_medium=referral&utm_content=&utm_campaign=Badge_grade"><img src="https://app.codacy.com/project/badge/Grade/cccd526f9bc94aaabc990dd65920cd21"/></a>
    <a><img alt="Issues" src="https://img.shields.io/github/issues/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Stars" src="https://img.shields.io/github/stars/LegacyLands/legacy-lands-library"></a>
    <a><img alt="Forks" src="https://img.shields.io/github/forks/LegacyLands/legacy-lands-library"></a>
    <a><img alt="License" src="https://img.shields.io/github/license/LegacyLands/legacy-lands-library"></a>
    <br /><br />
    <p>Based on <a href="https://github.com/FairyProject/fairy" target="_blank">Fairy Framework</a>, it runs as a plugin, aiming to encapsulate various existing libraries to simplify the development of plugins.</p>
</div>

## Overview

A comprehensive library built on [Fairy Framework](https://github.com/FairyProject/fairy), providing essential tools and utilities for modern Minecraft plugin development. It is cross-platform and supports Spigot Paper and Folia.

## Core Modules

- [**annotation**](annotation/README.md) - Powerful annotation processing framework with flexible scanning options and lifecycle management
- [**commons**](commons/README.md) - Essential utilities including VarHandle injection, task scheduling, and JSON operations
- [**configuration**](configuration/README.md) - Flexible configuration framework built on SimplixStorage with serialization support
- [**mongodb**](mongodb/README.md) - Streamlined MongoDB integration with Morphia for efficient data persistence
- [**cache**](cache/README.md) - Multi-level caching system integrating Caffeine and Redis with comprehensive lock mechanisms
- [**player**](player/README.md) - High-performance player data management with multi-tier caching and real-time synchronization

- [**experimental**](experimental/README.md) - Some experimental modules that can significantly improve performance, but may be too complex to use or temporarily unstable


- **security** - *Coming soon*

## Usage

### Distribution Packages

Download from [Actions](https://github.com/LegacyLands/legacy-lands-library/actions):
- `-javadoc`: Generated API documentation
- `-plugin`: Compiled plugin for direct server use
- `-sources`: Source code with compiled classes (recommended for development)

Detailed documentation in each module's `README`

### Maven Repository

Configure GitHub authentication first ([Learn More](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens))

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
    implementation("net.legacy.library:module-name:version")
}
```

### Jenkins

[Link](http://129.226.219.222:8080/job/legacy-lands-library/)

## Community

- [中文文档](README_ZHCN.md)
- [QQ Group](http://qq.legacylands.cn)
- [Github Issues](https://github.com/LegacyLands/legacy-lands-library/issues)
- [Bilibili](https://space.bilibili.com/1253128469) (Chinese tutorials)

We will release more development tutorials and experience sharing about this module or [Fairy Framework](https://github.com/FairyProject/fairy) in the [Bilibili](https://space.bilibili.com/1253128469) channel!

## Sponsors

Fully sponsored and maintained by [LegacyLands](https://github.com/LegacyLands).

![legacy-lands-logo.png](./legacy-lands-logo.png)

[Luminol](https://github.com/LuminolMC/Luminol)
is a Folia fork with many useful optimizations, configurable vanilla features, and more API supports.

![luminol-mc-logo.png](./luminol-mc-logo.png)

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)
