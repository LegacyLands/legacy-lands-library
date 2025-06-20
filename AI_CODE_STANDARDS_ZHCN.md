## AI 集成标准与实践指南

AI 代码生成技术已成为现代软件开发的重要组成部分。本项目积极采用 AI 辅助开发，以提升开发效率和代码质量。

本文档旨在建立项目 AI 工具的使用标准和最佳实践，确保团队成员能够高效、一致地利用 AI 工具（主要使用 Claude Code 和
PromptX）进行开发工作。

## 生成标准

参考 [CLAUDE.md](CLAUDE.md) 文件。

## PromptX

PromptX 是本项目采用的 AI 能力增强框架， 在实际使用中现出了显著的实用价值。
角色系统让 AI 快速切换到特定专业模式，记忆管理有效解决了上下文限制问题，避免重复介绍和了解项目架构。
对于复杂的多模块项目，它能维持一致的编码风格和质量标准，降低团队学习成本，让最佳实践得以有效传承。

```
> 你有什么角色可以使用？
  ⎿
     ============================================================
     🎯 锦囊目的：为AI提供可用角色信息，以便AI向主人汇报专业服务选项
     ============================================================
     … +91 lines (ctrl+r to expand)

● 我可以使用以下7个专业角色：

  1. assistant - 智能助手角色
  2. role-designer - 角色设计师
  3. product-manager - 产品经理
  4. java-backend-developer - Java后端开发者
  5. test-role - 测试角色
  6. xiaohongshu-marketer - 小红书营销专家
  7. frontend-developer - 前端开发者

  请选择您需要的角色ID，然后我将激活对应的专业能力为您服务。
```

### 推荐角色配置

在 legacy-lands-library 项目开发中，推荐使用 `java-backend-developer` 角色，该角色具备完整的 Java
后端开发知识体系，符合项目规范的编码标准，模块化架构设计最佳实践。

```
> 4

● promptx:promptx_action (MCP)(role: "java-backend-developer")
  ⎿
     ============================================================
     🎯 锦囊目的：激活特定AI角色，分析并生成具体的思维模式、行为模式和知识学习计划
     ============================================================
     … +30 lines (ctrl+r to expand)

● ✅ Java后端开发者角色已激活！

  我现在具备了Java后端开发的专业技能，可以帮助您处理Java相关的开发任务。基于当前项目结构，我看到这是一个模块化的Java库项目，包含：

  - annotation - 注解处理模块
  - cache - 缓存服务模块
  - commons - 通用工具模块
  - configuration - 配置管理模块
  - mongodb - MongoDB集成模块
  - player - 玩家数据管理模块
  - script - 脚本引擎模块

  有什么Java开发任务需要我协助的吗？
```

### 知识记忆与持久化

PromptX 的记忆功能为项目提供了关键的知识持久化能力，能够将复杂的模块架构、API 设计和最佳实践长期保存，避免上下文丢失导致的知识断层。

```
> 请你阅读 annotation 模块源码，并且记住这个模块的使用方式

...

● promptx:promptx_remember (MCP)(content: ...)
  ⎿
     ============================================================
     🎯 锦囊目的：增强AI长期记忆能力，主动内化专业知识、最佳实践和项目经验
     ============================================================
     … +227 lines (ctrl+r to expand)

● ...

  该模块已被记录到我的专业知识体系中，可以随时为您提供相关的开发指导和最佳实践建议。
```

```
> /clear
> 请你切换角色为java后端开发者，读取所有记忆 /home/qwqdev/Desktop/dev/java/legacy-lands-library/.promptx/memory/declarative.md

...

● 已成功切换到Java后端开发者角色，并读取了记忆文件。从记忆中了解到这个项目是Legacy Lands Library，一个Java后端工具库项目，具有注解处理、缓存、配置、播放器数据管理等多个模块。

  记忆中保存了Annotation模块的详细使用指南，包括：
  - 核心组件和架构
  - 注解处理器的创建和使用方式
  - 生命周期管理
  - 最佳实践

  现在我已准备好以Java后端开发者的身份为您提供专业服务。有什么需要帮助的吗？
```
