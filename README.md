<div align="center">
    <img src="./logo.png" alt="legacy-lands-library-logo">
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

A Modular Plugin Toolkit built on [Fairy Framework](https://github.com/FairyProject/fairy), featuring modular design and
leveraging modern Java 21 features.
It provides essential tools and utilities for modern Minecraft plugin development with cross-platform support for
Spigot, Paper, and Folia.

## Core Modules

- [**foundation**](foundation/README.md) - Core foundation module providing essential testing infrastructure, utilities,
  and base abstractions for all library modules.
- [**annotation**](annotation/README.md) - Powerful annotation processing framework with flexible scanning options and
  lifecycle management.
- [**aop**](aop/README.md) - Enterprise-grade Aspect-Oriented Programming framework with ClassLoader isolation,
  providing performance monitoring, thread safety, logging, and exception handling aspects.
- [**commons**](commons/README.md) - Essential utilities including VarHandle injection, task scheduling, virtual thread
  scheduling, JSON operations, and random object generation.
- [**configuration**](configuration/README.md) - Flexible configuration framework built on SimplixStorage with
  serialization support.
- [**mongodb**](mongodb/README.md) - Streamlined MongoDB integration with Morphia for efficient data persistence.
- [**cache**](cache/README.md) - Multi-tier caching system integrating Caffeine and Redis, providing comprehensive lock
  management and a generic framework for thread-safe resource access.
- [**player**](player/README.md) - Enterprise-grade distributed data management framework building high-performance
  entity-relationship data layers. Capable of **seamlessly handling thousands of inter-entity** relationship networks
  and state synchronization.
- [**script**](script/README.md) - Powerful, flexible, extensible, and high-performance script execution engine wrapper
  that supports `Rhino`, `Nashorn` and `V8` `JavaScript` engines.

- [**experimental**](experimental/README.md) - Some experimental modules that can significantly improve performance, but
  may be too complex to use or temporarily unstable.
    - [**third-party-schedulers**](experimental/third-party-schedulers/README.md) - Achieves distributed task processing
      via gRPC external task schedulers (currently implemented in Rust), enabling large plugin decoupling and
      performance improvements (for backends
      that cannot directly access the Bukkit API). Ideal for machine learning, anti-cheat large data computation,
      mathematics, and similar
      applications.

- **security** - *Coming soon.*

## Usage

Although the version number is currently still a SNAPSHOT, this does not mean it is unsuitable for production use.
In fact, we plan to extensively utilize this version in a large-scale plugin development project, and will release the
first official version once we have thoroughly validated its stability and functionality.

### Distribution Packages

Download from [Actions](https://github.com/LegacyLands/legacy-lands-library/actions):

- `-javadoc`: Generated API documentation
- `-plugin`: Compiled plugin for direct server use
- `-sources`: Source code with compiled classes (recommended for development)

Detailed documentation in each module's `README`

### Maven Repository

Configure GitHub authentication
first ([Learn More](https://docs.github.com/en/authentication/keeping-your-account-and-data-secure/managing-your-personal-access-tokens))

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
    compileOnly("net.legacy.library:module-name:version")
}
```

## Community

- [中文文档](README_ZHCN.md)
- [QQ Group](http://qq.legacylands.cn)
- [Github Issues](https://github.com/LegacyLands/legacy-lands-library/issues)
- [Bilibili](https://space.bilibili.com/1253128469) (Chinese tutorials)

We will release more development tutorials and experience sharing about this module
or [Fairy Framework](https://github.com/FairyProject/fairy) in the [Bilibili](https://space.bilibili.com/1253128469)
channel!

## Sponsors

**Fully sponsored and maintained** by [LegacyLands](https://github.com/LegacyLands).

![legacy-lands-logo.png](./legacy-lands-logo.png)

[Luminol](https://github.com/LuminolMC/Luminol)
is a Folia fork with many useful optimizations, configurable vanilla features, and more API supports.

![luminol-mc-logo.png](./luminol-mc-logo.png)

We are using YourKit to optimize our performance. They support open-source with innovative [Java](https://www.yourkit.com/java/profiler/) and [.NET](https://www.yourkit.com/dotnet-profiler/) profiling tools.

<img src="./yklogo.png" width="300">

## Contributing

We warmly welcome more developers to join our open source project and contribute their strength. Whether it's sharing
ideas, creating Issues, or submitting Pull Requests, every contribution is significant!

We are very liberal with our use of AI. You can use any AI you want to write and contribute code, as long as it all
works and meets the requirements in [CLAUDE.md](CLAUDE.md) (this is a Chinese document).

## Future plans

We are treating this library as a major dependency for keeping our entire server running (LegacyLands Minecraft server),
and we are actively introducing Scala 3 into it. There will be small portions of non-breaking migration to Scala 3 in
the future, leveraging its superior type system, concurrency model, and functional programming capabilities for better
performance and maintainability.

## Star History

[![Star History Chart](https://api.star-history.com/svg?repos=LegacyLands/legacy-lands-library&type=Date)](https://star-history.com/#LegacyLands/legacy-lands-library&Date)

---
