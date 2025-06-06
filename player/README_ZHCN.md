### 玩家 (Player) 模块

一个企业级分布式数据管理框架，为游戏服务构建高性能实体-关系数据层。
基于三级缓存架构(Caffeine、Redis、MongoDB)，提供 TTL 管理、N 向关系映射、事务化操作和复杂查询能力。
支持跨服务器数据同步和分布式锁，适用于大规模多服务器环境下的复杂数据管理需求，能够 **无缝处理数千实体间** 的关系网络与状态同步。

### 用法

```kotlin
// Dependencies
dependencies {
    // 核心依赖模块
    compileOnly("net.legacy.library:annotation:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:configuration:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:cache:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:commons:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:mongodb:1.0-SNAPSHOT")

    // player 模块
    compileOnly("net.legacy.library:player:1.0-SNAPSHOT")
}
```

### 多级缓存设计

Player 模块采用三层缓存架构以优化性能和可扩展性：

- **L1缓存 (Caffeine)**：本地内存缓存，存储所有在线玩家的数据。提供纳秒级的读写性能，支持自动过期和基于大小的淘汰策略。每个服务器实例维护自己的
  L1 缓存。
- **L2 缓存 (Redis)**：分布式缓存，存储热数据和共享数据。支持毫秒级的读写性能，提供跨服务器的数据共享，实现分布式锁和 Redis
  Stream 消息队列。
- **持久层 (MongoDB)**：提供可靠的持久化存储。支持复杂查询和索引，处理大规模数据集，适用于非频繁访问的历史数据。

### 读取路径

1. 首先尝试从L1缓存读取数据（Caffeine）
2. 如果 L1 缓存未命中，尝试从L2缓存读取（Redis）
3. 如果 L2 缓存未命中，从数据库读取（MongoDB）
4. 数据加载后，自动填充L1和L2缓存

### 写入路径

1. 数据首先写入L1缓存（Caffeine）
2. 通过 Redis Stream 同步到 L2 缓存（Redis）
3. 定时任务或显式调用将数据持久化到数据库（MongoDB）

### 基本服务配置

```java
public class ServiceInitExample {
    public static void main(String[] args) {
        // 1. 创建 MongoDB 连接配置
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                "playerdb",  // 数据库名称
                "mongodb://localhost:27017/",  // MongoDB 连接 URI
                UuidRepresentation.STANDARD  // UUID 表示形式
        );

        // 2. 创建 Redis 配置
        Config redisConfig = new Config();
        redisConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setDatabase(0)
                .setConnectionMinimumIdleSize(5)
                .setConnectionPoolSize(10);

        // 3. 创建玩家数据服务 (使用默认配置)
        LegacyPlayerDataService service = LegacyPlayerDataService.of(
                "player-data-service",  // 服务名称，用于区分多个服务实例
                mongoConfig,  // MongoDB 配置
                redisConfig,  // Redis 配置
                // 扫描的包列表，用于自动发现 RStreamAccepter 实现
                List.of("your.package", "net.legacy.library.player"),
                // 类加载器列表，用于扫描注解
                List.of(PlayerLauncher.class.getClassLoader())
        );
    }
}
```

### 数据库索引管理 (可选但推荐)

为了优化查询性能，特别是对于 `findEntitiesByType`、`findEntitiesByAttribute` 和 `findEntitiesByRelationship` 的查询，在
MongoDB 中创建适当的索引至关重要。`LegacyIndexManager` 类可以帮助管理这些索引。

建议在应用程序启动过程中，初始化 `MongoDBConnectionConfig` 和相关服务之后，调用所需的 `ensure...` 方法。

```java
public class IndexInitializationExample {
    public void initializeIndexes(MongoDBConnectionConfig mongoConfig) {
        try {
            // 创建索引管理器
            LegacyIndexManager indexManager = LegacyIndexManager.of(mongoConfig);

            // === 确保 LegacyEntityData 的索引 ===

            // 按 entityType 查询的索引 (强烈推荐)
            indexManager.ensureEntityTypeIndex();

            // 示例：为常用查询的属性（例如 "status"）创建索引。对可选属性使用 sparse=true
            indexManager.ensureAttributeIndex("status", true);

            // 示例：为常用查询的关系类型（例如 "member"）创建索引
            indexManager.ensureRelationshipIndex("member");
            indexManager.ensureRelationshipIndex("owner"); // 根据需要为其他类型添加

            // === 确保 LegacyPlayerData 的索引 ===

            // 示例：为常用查询的玩家数据字段（例如 "guildId"）创建索引
            indexManager.ensurePlayerDataIndex("guildId", true);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
```

**注意:** `ensure...` 方法是幂等的。在启动时重复调用它们是安全的，并且可以确保索引存在，即使索引已经存在也不会导致错误。

### 高级服务配置

```java
public class AdvancedServiceInitExample {
    public static void main(String[] args) {
        // 创建自定义配置的服务
        LegacyPlayerDataService customService = LegacyPlayerDataService.of(
                "custom-player-service",
                mongoConfig,
                redisConfig,
                Duration.ofMinutes(15),  // 数据自动保存间隔（默认2小时）
                List.of("your.package", "net.legacy.library.player"),
                List.of(PlayerLauncher.class.getClassLoader()),
                Duration.ofSeconds(1)  // Redis Stream 轮询间隔（默认2秒）
        );
    }
}
```

### 基本数据 CRUD 操作

```java
public class PlayerDataExample {
    public void basicOperations(LegacyPlayerDataService service, UUID playerUuid) {
        // 1. 获取玩家数据
        LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

        // 2. 读取单个属性
        String coins = playerData.getData("coins");  // 如果不存在返回 null

        // 3. 带类型转换的读取
        Integer coinsValue = playerData.getData("coins", Integer::parseInt);
        Long lastLoginTime = playerData.getData("lastLogin", Long::parseLong);
        Boolean isPremium = playerData.getData("premium", Boolean::parseBoolean);

        // 4. 添加/更新单个属性
        playerData.addData("level", "5");  // 所有值都以字符串形式存储

        // 5. 添加/更新多个属性
        Map<String, String> playerStats = new HashMap<>();
        playerStats.put("strength", "10");
        playerStats.put("agility", "15");
        playerStats.put("intelligence", "20");
        playerStats.put("lastUpdated", String.valueOf(System.currentTimeMillis()));
        playerData.addData(playerStats);  // 批量更新

        // 6. 删除属性
        playerData.removeData("temporaryBuff");  // 移除单个属性
    }
}
```

### 仅存在于单端的热数据

```java
public class EasyPlayerRawCacheExample {
    public void singleServerHotDataUsage() {
        // 1. 创建 EasyPlayerRawCacheDataService 实例
        EasyPlayerRawCacheDataService cacheService = new EasyPlayerRawCacheDataService("game-session-cache");

        // 2. 获取玩家的临时数据缓存（get 方法传入布尔值，意为若不存在则创建）
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        EasyPlayerRawCacheData playerCache = cacheService.get(playerUuid, true).orElseThrow();

        // 3. 存储临时会话数据（这些数据只存在于当前服务器的内存中，不会持久化）
        playerCache.getRawCache().getResource().put("lastClickTime", String.valueOf(System.currentTimeMillis()));
        playerCache.getRawCache().getResource().put("currentZone", "pvp_arena_5");
        playerCache.getRawCache().getResource().put("temporaryBuffs", "speed:30,strength:15");

        // 4. 读取临时会话数据
        String lastClickTime = playerCache.getRawCache().getResource().getIfPresent("lastClickTime");
        String currentZone = playerCache.getRawCache().getResource().getIfPresent("currentZone");

        // 5. 为特定功能创建自定义缓存（例如：玩家当前会话击杀记录）
        CacheServiceInterface<Cache<UUID, Integer>, Integer> killCountCache =
                CacheServiceFactory.createCaffeineCache();
        playerCache.addCustomRawCache("killCount", killCountCache);

        // 使用自定义缓存
        Cache<UUID, Integer> killCounts = playerCache.getCustomRawCache("killCount").getResource();

        // 记录玩家击杀
        UUID victimId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        killCounts.put(victimId, 1);

        // 检查玩家是否有特定击杀记录
        boolean hasKilled = killCounts.getIfPresent(victimId) != null;

        // 增加击杀计数
        Integer currentKills = killCounts.getIfPresent(victimId);
        if (currentKills != null) {
            killCounts.put(victimId, currentKills + 1);
        }

        // 6. 检查玩家是否有特定类型的自定义缓存
        boolean hasInventoryCache = playerCache.hasCustomRawCache("tempInventory");

        // 7. 从另一个服务获取相同缓存服务
        EasyPlayerRawCacheDataService sameService =
                EasyPlayerRawCacheDataService.EASY_PLAYER_RAW_CACHE_DATA_SERVICES
                        .getResource()
                        .getIfPresent("game-session-cache");

        // 8. 使用场景示例：存储玩家当前会话的临时排行榜数据
        CacheServiceInterface<Cache<String, Long>, Long> scoreCache =
                CacheServiceFactory.createCaffeineCache();
        playerCache.addCustomRawCache("sessionScores", scoreCache);

        Cache<String, Long> scores = playerCache.getCustomRawCache("sessionScores").getResource();
        scores.put("kills", 15L);
        scores.put("deaths", 3L);
        scores.put("assists", 7L);
        scores.put("flagCaptures", 2L);

        // 计算临时KDA比率（只计算当前游戏会话，不影响持久数据）
        Long kills = scores.getIfPresent("kills");
        Long deaths = scores.getIfPresent("deaths");
        Long assists = scores.getIfPresent("assists");

        if (kills != null && deaths != null && assists != null && deaths > 0) {
            double kda = (kills + assists) / (double) deaths;
            scores.put("kda", Double.doubleToRawLongBits(kda));
        }
    }
}
```

### 处理不存在的数据

```java
public class PlayerDataHandlingExample {
    public void handleNonexistentData(LegacyPlayerDataService service, UUID playerUuid) {
        // 获取玩家数据 - 即使数据库中不存在也会返回一个新的空对象
        LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

        // 检查特定属性是否存在
        String rank = playerData.getData("rank");
        if (rank == null) {
            // 属性不存在，设置默认值
            playerData.addData("rank", "DEFAULT");
        }

        // 安全地读取数值并提供默认值
        int coins = Optional.ofNullable(playerData.getData("coins"))
                .map(Integer::parseInt)
                .orElse(0);  // 如果 coins 属性不存在或解析失败，返回 0

        // 也可以使用 Java 8 Stream 处理批量数据
        boolean hasAllRequiredFields = Stream.of("name", "rank", "level")
                .allMatch(field -> playerData.getData(field) != null);
    }
}
```

## 跨服务器数据同步

当需要在多个服务器之间同步数据变更时，使用 Redis Stream 机制可确保数据一致性。

### 通过玩家名称更新数据

注意：此方法是异步的，发布后立即返回。数据更新会在所有订阅的服务器上异步执行。

```java
public class CrossServerUpdateExample {
    public void updatePlayerByName(LegacyPlayerDataService service, String playerName) {
        // 1. 准备要更新的数据
        Map<String, String> updates = new HashMap<>();
        updates.put("lastLogin", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "ONLINE");
        updates.put("currentServer", "lobby-1");

        // 2. 创建并发布更新任务
        // 这将向 Redis Stream 发送消息，所有订阅的服务器都会收到并处理
        service.pubRStreamTask(
                PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        playerName,  // 玩家名称
                        updates,  // 要更新的数据
                        Duration.ofMinutes(5)  // 任务在 Stream 中的存活时间
                )
        );
    }
}
```

### 通过 UUID 更新数据

```java
public class CrossServerUpdateByUuidExample {
    public void updatePlayerByUuid(LegacyPlayerDataService service, UUID playerUuid) {
        // 准备要更新的数据
        Map<String, String> updates = new HashMap<>();
        updates.put("lastSeen", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "OFFLINE");

        // 创建并发布 UUID 更新任务
        service.pubRStreamTask(
                PlayerDataUpdateByUuidRStreamAccepter.createRStreamTask(
                        playerUuid,  // 玩家 UUID
                        updates,  // 要更新的数据
                        Duration.ofMinutes(5)  // 任务超时时间
                )
        );
    }
}
```

### 自定义数据同步

您可以创建自定义的 Redis Stream 处理器来处理特定的数据同步需求：

```java

@RStreamAccepterRegister  // 这个注解使系统自动发现并注册此接收器
public class PlayerAchievementUpdateAccepter implements RStreamAccepterInterface {
    // 可以选择使用 commons 中的 GsonUtil
    private static final Gson GSON = new Gson();

    // 定义此接收器的名称
    @Override
    public String getActionName() {
        return "player-achievement-update";
    }

    // 同一任务是否要在单个服务器上被处理多次
    @Override
    public boolean isRecordLimit() {
        return true;
    }

    // 处理接收到的数据
    @Override
    public void accept(RStream<Object, Object> stream,
                       StreamMessageId id,
                       LegacyPlayerDataService service,
                       String data) {
        try {
            // 解析任务数据
            AchievementData achievementData = GSON.fromJson(
                    data, new TypeToken<AchievementData>() {
                    }.getType()
            );

            // 获取玩家数据
            UUID playerUuid = UUID.fromString(achievementData.getPlayerId());
            LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

            // 更新成就数据
            String existingAchievements = playerData.getData("achievements");
            // 处理成就逻辑...

            // 更新玩家数据
            playerData.addData("achievements", updatedAchievements);
            playerData.addData("lastAchievementTime", String.valueOf(System.currentTimeMillis()));

            // 成功处理后确认任务完成（删除消息）
            ack(stream, id);
        } catch (Exception exception) {
            // 处理失败，不确认任务，允许重试或其他服务器处理
            log.error("Failed to process achievement update", exception);
        }
    }

    // 创建成就更新任务
    public static RStreamTask createTask(UUID playerId, String achievementId, Duration expiry) {
        AchievementData data = new AchievementData(playerId.toString(), achievementId);
        return RStreamTask.of(
                "player-achievement-update",
                GSON.toJson(data),
                expiry
        );
    }

    // 内部数据类
    private static class AchievementData {
        private final String playerId;
        private final String achievementId;

        public AchievementData(String playerId, String achievementId) {
            this.playerId = playerId;
            this.achievementId = achievementId;
        }

        public String getPlayerId() {
            return playerId;
        }

        public String getAchievementId() {
            return achievementId;
        }
    }
}
```

上述实例为 `LegacyPlayerData`，若想为 `LegacyEntityData` 创建，
则只需要使用 `EntityRStreamAccepterRegister` 与 `EntityRStreamAccepterInterface` 即可。

## Stream Accepter 弹性处理框架

针对 Redis Stream accepter 操作中的失败情况，传统的手动异常处理缺乏标准化且难以保证系统一致性。为此提供了一个全面的弹性处理框架，具有可配置重试策略、异常类型识别、补偿机制和监控支持，同时保持完全向后兼容。

### 基本用法

```java
public class ResilienceExample {
    public void basicResilience(LegacyPlayerDataService playerService) {
        // 为所有发现的流接收器启用弹性处理
        List<String> basePackages = List.of("your.package", "net.legacy.library.player");
        List<ClassLoader> classLoaders = List.of(PlayerLauncher.class.getClassLoader());

        // 创建具有默认设置的弹性任务（3次重试，指数退避）
        ResilientRStreamAccepterInvokeTask resilientTask =
                ResilientRStreamAccepterInvokeTask.ofResilient(
                        playerService, basePackages, classLoaders, Duration.ofSeconds(5)
                );
        resilientTask.start();
    }
}
```

### 自定义弹性策略

```java
public class CustomResilienceExample {
    public void customStrategies() {
        // 策略1：仅重试网络错误
        ResilientRStreamAccepter networkResilient = ResilienceFactory.createForNetworkErrors(originalAccepter);

        // 策略2：快速重试（更多次数，更短延迟）
        ResilientRStreamAccepter fastRetry = ResilienceFactory.createFastRetry(originalAccepter);

        // 策略3：保守重试（更少次数，更长延迟）
        ResilientRStreamAccepter conservativeRetry = ResilienceFactory.createConservativeRetry(originalAccepter);

        // 策略4：自定义策略与特定补偿
        RetryPolicy customPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofSeconds(2))
                .exponentialBackoff(true)
                .retryCondition(ex -> ex instanceof IOException)
                .build();

        CompensationAction customCompensation = context -> {
            // 自定义清理逻辑
            Log.error("{}次尝试后失败: {}",
                    context.getAttemptNumber(), context.getException().getMessage());
            context.getStream().remove(context.getMessageId());
        };

        ResilientRStreamAccepter customResilient = ResilienceFactory.createCustom(
                originalAccepter, customPolicy, customCompensation
        );
    }
}
```

### 监控和管理

```java
public class ResilienceMonitoringExample {
    public void monitorResilience(ResilientRStreamAccepter resilientAccepter) {
        // 监控重试统计
        StreamMessageId messageId = new StreamMessageId(System.currentTimeMillis(), 0);
        int retryCount = resilientAccepter.getRetryCount(messageId);
        int totalTracked = resilientAccepter.getTrackedMessageCount();

        Log.info("消息 {} 已重试 {} 次", messageId, retryCount);
        Log.info("当前正在跟踪 {} 条消息的重试", totalTracked);

        // 清除已完成消息的重试跟踪
        resilientAccepter.clearRetryTracking(messageId);
    }
}
```

### 失败处理模式

```java
public class FailureHandlingPatternsExample {
    public void handleNetworkErrors(RStreamAccepterInterface accepter) {
        // 使用指数退避重试网络错误
        ResilientRStreamAccepter networkResilient = ResilienceFactory.createForNetworkErrors(accepter);
    }

    public void handleDataValidationErrors(RStreamAccepterInterface accepter) {
        // 不重试验证错误，仅记录日志并移除
        RetryPolicy noRetryPolicy = RetryPolicy.noRetry();
        CompensationAction logAndRemove = CompensationAction.composite(
                CompensationAction.LOG_FAILURE,
                CompensationAction.REMOVE_MESSAGE
        );
        ResilientRStreamAccepter validationResilient = ResilienceFactory.createCustom(
                accepter, noRetryPolicy, logAndRemove
        );
    }

    public void handleResourceContention(RStreamAccepterInterface accepter) {
        // 使用更长延迟的保守重试
        ResilientRStreamAccepter conservativeResilient = ResilienceFactory.createConservativeRetry(accepter);
    }
}
```

### 实体数据管理系统

除了玩家数据外，我们还提供了灵活的实体数据管理系统，适用于任何需要持久化存储的游戏对象，如公会等。

### 实体服务初始化

```java
public class EntityServiceExample {
    public void initEntityService() {
        // 创建实体数据服务
        LegacyEntityDataService entityService = LegacyEntityDataService.of(
                "game-entity-service",  // 服务名称
                mongoConfig,  // MongoDB 配置
                redisConfig,  // Redis 配置
                List.of("your.package", "net.legacy.library.player"),  // 包扫描列表
                List.of(PlayerLauncher.class.getClassLoader())  // 类加载器列表
        );
    }
}
```

### 创建和管理实体

```java
public class EntityManagementExample {
    public void manageEntities(LegacyEntityDataService entityService) {
        // 1. 创建公会实体
        UUID guildId = UUID.randomUUID();
        LegacyEntityData guild = LegacyEntityData.of(guildId, "guild");

        // 2. 设置实体属性
        guild.addAttribute("name", "Shadow Warriors");
        guild.addAttribute("level", "5");
        guild.addAttribute("founded", String.valueOf(System.currentTimeMillis()));
        guild.addAttribute("description", "Elite PvP guild for hardcore players");

        // 3. 批量添加属性
        Map<String, String> guildStats = new HashMap<>();
        guildStats.put("members", "15");
        guildStats.put("maxMembers", "20");
        guildStats.put("reputation", "850");
        guild.addAttributes(guildStats);

        // 4. 建立实体关系
        UUID leaderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID viceLeaderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        // 添加成员关系
        guild.addRelationship("leader", leaderId);
        guild.addRelationship("officer", viceLeaderId);

        // 添加多个普通成员
        for (UUID memberId : memberIds) {
            guild.addRelationship("member", memberId);
        }

        // 6. 检查关系
        boolean isLeader = guild.hasRelationship("leader", leaderId);  // true
        int memberCount = guild.countRelationships("member");  // 返回成员数量

        // 7. 获取所有特定关系的实体ID
        Set<UUID> allMembers = guild.getRelatedEntities("member");

        // 8. 删除关系
        guild.removeRelationship("member", leavingMemberId);

        // 9. 修改属性
        guild.addAttribute("reputation", "900");  // 更新声望值

        /* 
          10. 保存实体：将实体数据立即存入 L1 缓存（本机内存），并调度一个异步任务将其持久化到 L2 缓存 (Redis) 和数据库 (MongoDB)。
            - 调用此方法的意义：确保数据最终被保存，能在服务重启后恢复，并可供其他服务通过 Redis 或数据库访问。
            - 如果不调用：对实体的修改（如添加/修改属性、添加/删除关系）仅存在于当前服务的内存 (L1) 中，一旦服务关闭或实体从 L1 缓存中移除，未保存的更改将丢失。
            - 注意：此方法调用后立即返回，不会等待持久化完成。
        */
        entityService.saveEntity(guild);
    }
}
```

### 复杂实体查询

```java
public class EntityQueryExample {
    public void queryEntities(LegacyEntityDataService entityService) {
        // 1. 按ID查询单个实体
        UUID entityId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        LegacyEntityData entity = entityService.getEntityData(entityId);

        // 2. 按类型查询所有实体
        List<LegacyEntityData> allGuilds = entityService.findEntitiesByType("guild");

        // 3. 按属性查询实体
        List<LegacyEntityData> level5Guilds = entityService.findEntitiesByAttribute("level", "5");

        // 4. 按关系查询实体
        // 例如：查找某玩家作为成员所属的所有公会
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440005");
        List<LegacyEntityData> playerGuilds = entityService.findEntitiesByRelationship("member", playerId);

        // 5. 组合查询（需自行实现）
        // 例如：查找某玩家是领导者且等级大于3的公会
        List<LegacyEntityData> leaderGuilds = entityService.findEntitiesByRelationship("leader", playerId);
        leaderGuilds.removeIf(guild -> {
            String level = guild.getAttribute("level");
            return level == null || Integer.parseInt(level) <= 3;
        });
    }
}
```

### 实体数据关系示例

以下是一些使用实体关系系统的具体场景：

```java
public class EntityRelationshipExamples {

    // 示例：创建游戏公会系统
    public void createGuildSystem(LegacyEntityDataService entityService) {
        // 创建公会
        LegacyEntityData guild = LegacyEntityData.of(UUID.randomUUID(), "guild");
        guild.addAttribute("name", "Dragon Knights");

        // 创建公会大厅
        LegacyEntityData guildHall = LegacyEntityData.of(UUID.randomUUID(), "building");
        guildHall.addAttribute("name", "Dragon's Lair");
        guildHall.addAttribute("type", "guild_hall");

        // 建立双向关系
        guild.addRelationship("owns", guildHall.getUuid());
        guildHall.addRelationship("owned_by", guild.getUuid());

        // 保存两个实体
        entityService.saveEntity(guild);
        entityService.saveEntity(guildHall);
    }

    // 示例：创建物品库存系统
    public void createInventorySystem(LegacyEntityDataService entityService, UUID playerId) {
        // 玩家实体（可能已经存在）
        LegacyEntityData playerEntity = entityService.getEntityData(playerId);
        if (playerEntity == null) {
            playerEntity = LegacyEntityData.of(playerId, "player");
            playerEntity.addAttribute("name", "GameMaster");
        }

        // 创建背包实体
        LegacyEntityData backpack = LegacyEntityData.of(UUID.randomUUID(), "container");
        backpack.addAttribute("type", "backpack");
        backpack.addAttribute("slots", "20");

        // 创建物品
        LegacyEntityData sword = LegacyEntityData.of(UUID.randomUUID(), "item");
        sword.addAttribute("name", "Excalibur");
        sword.addAttribute("damage", "50");

        // 建立关系链
        playerEntity.addRelationship("owns", backpack.getUuid());
        backpack.addRelationship("contains", sword.getUuid());

        // 保存所有实体
        entityService.saveEntity(playerEntity);
        entityService.saveEntity(backpack);
        entityService.saveEntity(sword);
    }
}
```

### 手动 L1 到 L2 缓存同步

```java
public class CacheSyncExample {
    public void syncCaches(LegacyPlayerDataService service) {
        // 1. 同步单个玩家的缓存（直接方法）
        UUID playerUuid = player.getUniqueId();

        // 直接在当前服务器同步特定玩家
        L1ToL2PlayerDataSyncTask.of(playerUuid, service).start();

        // 2. 通过 Redis Stream 同步（跨服务器）

        // 按玩家名称同步
        service.pubRStreamTask(
                L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PlayerName",  // 玩家名称
                        Duration.ofSeconds(10)  // 任务过期时间
                )
        );

        // 按 UUID 同步
        service.pubRStreamTask(
                L1ToL2PlayerDataSyncByUuidRStreamAccepter.createRStreamTask(
                        playerUuid,  // 玩家 UUID
                        Duration.ofSeconds(10)  // 任务过期时间
                )
        );

        // 3. 批量同步所有在线玩家
        service.getL1Cache().getResource().asMap().forEach((uuid, data) -> {
            L1ToL2PlayerDataSyncTask.of(uuid, service).start();
        });
    }
}
```

### 手动数据持久化

```java
public class PersistenceExample {
    public void persistData(LegacyPlayerDataService playerService,
                            LegacyEntityDataService entityService) {
        // 1. 创建锁设置
        LockSettings lockSettings = LockSettings.of(
                100,  // 等待锁的最大时间
                200,  // 持有锁的最大时间
                TimeUnit.MILLISECONDS
        );

        // 2. 触发玩家数据持久化
        PlayerDataPersistenceTask.of(
                lockSettings,
                playerService
        ).start();

        // 3. 限制单次持久化的最大数据量
        PlayerDataPersistenceTask.of(
                lockSettings,
                playerService,
                500  // 最多处理 500 条数据
        ).start();

        // 4. 触发实体数据持久化
        EntityDataPersistenceTask.of(
                lockSettings,
                entityService
        ).start();
    }
}
```

### 数据 TTL (Time-To-Live) 管理

Redis 缓存中的数据默认设置了 TTL，以防止内存无限增长。以下是如何管理实体和玩家数据 TTL 的方法：

```java
public class TTLManagementExample {
    public void manageTTL(LegacyEntityDataService entityService, LegacyPlayerDataService playerService) {
        // 1. 实体数据 TTL 管理
        UUID entityId = UUID.randomUUID();

        // 为单个实体设置默认 TTL (30分钟)
        boolean success = entityService.setEntityDefaultTTL(entityId);

        // 为单个实体设置自定义 TTL
        boolean customSuccess = entityService.setEntityTTL(entityId, Duration.ofHours(2));

        // 为所有没有 TTL 的实体设置默认 TTL
        int entitiesUpdated = entityService.setDefaultTTLForAllEntities();

        // 2. 玩家数据 TTL 管理
        UUID playerId = UUID.randomUUID();

        // 为单个玩家设置默认 TTL (1 天)
        boolean playerSuccess = playerService.setPlayerDefaultTTL(playerId);

        // 为单个玩家设置自定义 TTL
        boolean playerCustomSuccess = playerService.setPlayerTTL(playerId, Duration.ofHours(1));

        // 为所有没有 TTL 的玩家设置默认 TTL
        int playersUpdated = playerService.setDefaultTTLForAllPlayers();
    }
}
```

### 实体批量保存操作

```java
public class BatchEntityOperationsExample {
    public void batchSaveEntities(LegacyEntityDataService entityService) {
        // 创建多个实体
        List<LegacyEntityData> entities = new ArrayList<>();

        // 创建第一个实体
        LegacyEntityData entity1 = LegacyEntityData.of(UUID.randomUUID(), "item");
        entity1.addAttribute("name", "龙之刃");
        entity1.addAttribute("rarity", "legendary");
        entities.add(entity1);

        // 创建第二个实体
        LegacyEntityData entity2 = LegacyEntityData.of(UUID.randomUUID(), "item");
        entity2.addAttribute("name", "暗影披风");
        entity2.addAttribute("rarity", "epic");
        entities.add(entity2);

        /* 
          批量保存实体：将列表中的所有实体立即存入 L1 缓存，并调度一个后台任务将它们统一持久化到 L2 (Redis) 和数据库。
            - 这样做比对每个实体单独调用 saveEntity 更高效，因为它合并了持久化操作。
            - 调用的意义和不调用的后果同单个 saveEntity。
        */
        entityService.saveEntities(entities);
    }
}
```

### N 向关系管理

```java
public class NDirectionalRelationshipExample {
    public void createComplexRelationships(LegacyEntityDataService entityService) {
        // 创建一个团队
        UUID teamId = UUID.randomUUID();
        LegacyEntityData team = LegacyEntityData.of(teamId, "team");
        team.addAttribute("name", "精英战队");

        // 创建多个成员
        UUID leaderId = UUID.randomUUID();
        UUID memberId1 = UUID.randomUUID();
        UUID memberId2 = UUID.randomUUID();

        // 创建关系映射，一次性建立多向关系
        Map<UUID, Map<String, Set<UUID>>> relationshipMap = new HashMap<>();

        // 团队与成员的关系
        Map<String, Set<UUID>> teamRelations = new HashMap<>();
        teamRelations.put("leader", Set.of(leaderId));
        teamRelations.put("member", Set.of(memberId1, memberId2));
        relationshipMap.put(teamId, teamRelations);

        // 成员与团队的关系
        Map<String, Set<UUID>> leaderRelations = new HashMap<>();
        leaderRelations.put("leads", Set.of(teamId));
        relationshipMap.put(leaderId, leaderRelations);

        Map<String, Set<UUID>> member1Relations = new HashMap<>();
        member1Relations.put("belongs_to", Set.of(teamId));
        relationshipMap.put(memberId1, member1Relations);

        Map<String, Set<UUID>> member2Relations = new HashMap<>();
        member2Relations.put("belongs_to", Set.of(teamId));
        relationshipMap.put(memberId2, member2Relations);

        // 一次性创建 N 向关系网络，该方法不需要手动 save
        entityService.createNDirectionalRelationships(relationshipMap);
    }
}
```

### 双向关系创建

```java
public class BidirectionalRelationshipExample {
    public void createBidirectionalRelationships(LegacyEntityDataService entityService) {
        // 创建两个实体
        UUID entity1Id = UUID.randomUUID();
        UUID entity2Id = UUID.randomUUID();

        // 创建双向关系（例如：朋友关系）
        boolean success = entityService.createBidirectionalRelationship(
                entity1Id,          // 第一个实体 ID
                entity2Id,          // 第二个实体 ID 
                "friend_with",      // 第一个实体到第二个实体的关系
                "friend_with"       // 第二个实体到第一个实体的关系（可以相同或不同）
        );

        // 另一个例子：创建父子关系
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        boolean familySuccess = entityService.createBidirectionalRelationship(
                parentId,          // 父实体 ID
                childId,           // 子实体 ID
                "child",           // 父->子：这是我的孩子
                "parent"           // 子->父：这是我的父母
        );
    }
}
```

### 复杂关系查询

```java
public class ComplexRelationshipQueryExample {
    public void performComplexQueries(LegacyEntityDataService entityService) {
        // 创建查询条件
        List<RelationshipCriteria> criteria = new ArrayList<>();

        // 创建第一个条件：实体是团队成员
        RelationshipCriteria memberCriterion = RelationshipCriteria.of("member", UUID.fromString("team-uuid-here"));

        // 创建第二个条件：实体不是管理员
        RelationshipCriteria notAdminCriterion = RelationshipCriteria.ofNegated("admin", UUID.fromString("team-uuid-here"));

        // 添加条件到列表
        criteria.add(memberCriterion);
        criteria.add(notAdminCriterion);

        // 执行 AND 查询：找出是团队成员但不是管理员的实体
        List<LegacyEntityData> regularMembers = entityService.findEntitiesByMultipleRelationships(
                criteria,
                RelationshipQueryType.AND
        );

        // 执行 OR 查询：找出是团队成员或是管理员的实体
        List<LegacyEntityData> allTeamUsers = entityService.findEntitiesByMultipleRelationships(
                List.of(
                        RelationshipCriteria.of("member", UUID.fromString("team-uuid-here")),
                        RelationshipCriteria.of("admin", UUID.fromString("team-uuid-here"))
                ),
                RelationshipQueryType.OR
        );
    }
}
```

### 关系事务管理

通过 `RelationshipTransaction` 可以在单个原子操作中执行多个关系更改：

```java
public class RelationshipTransactionExample {
    public void executeTransactions(LegacyEntityDataService entityService) {
        // 执行关系事务
        entityService.executeRelationshipTransaction(transaction -> {
            // 团队和成员 ID
            UUID teamId = UUID.fromString("team-uuid-here");
            UUID memberId = UUID.fromString("member-uuid-here");
            UUID oldTeamId = UUID.fromString("old-team-uuid-here");

            // 在一个事务中执行多个关系操作
            transaction
                    // 从旧团队移除成员
                    .removeRelationship(oldTeamId, "member", memberId)
                    // 将成员添加到新团队
                    .addRelationship(teamId, "member", memberId)
                    // 更新成员所属团队
                    .removeRelationship(memberId, "belongs_to", oldTeamId)
                    .addRelationship(memberId, "belongs_to", teamId);

        }); // 完成后自动调度持久化
    }
}
```

### 数据保存与持久化策略

在处理玩家数据和实体数据时，正确的保存策略对系统性能至关重要：

```java
public class PersistenceStrategyExample {
    public void demonstratePersistenceStrategies(
            LegacyPlayerDataService playerService,
            LegacyEntityDataService entityService) {

        // 1. 玩家数据保存
        UUID playerId = UUID.randomUUID();
        LegacyPlayerData playerData = playerService.getLegacyPlayerData(playerId);
        playerData.addData("lastAction", String.valueOf(System.currentTimeMillis()));

        // 调用此方法会：
        // - 将数据保存到 L2 缓存并设置 TTL（1 天）
        // - 安排数据库持久化任务
        playerService.saveLegacyPlayerData(playerData);

        // 2. 实体数据保存
        UUID entityId = UUID.randomUUID();
        LegacyEntityData entityData = entityService.getEntityData(entityId);
        entityData.addAttribute("lastModified", String.valueOf(System.currentTimeMillis()));

        /* 
          调用 saveEntity 方法的作用与效果：
            1. 立即更新 L1 缓存：将实体数据放入当前服务的内存缓存，使得后续在本服务内的 getEntityData 调用能立刻获取到最新状态。
            2. 调度后台持久化：启动一个异步任务，负责将数据写入 L2 (Redis) 并最终保存到数据库 (MongoDB)。
             
          意义：保证数据的最终持久性，防止服务关闭或缓存淘汰导致数据丢失，并使数据对其他服务可见（通过 L2 或 DB）。
            - 如果不调用 saveEntity：对实体属性或关系的修改仅停留在 L1 缓存，不会被保存到 Redis 或数据库。
            - 注意：此方法不保证数据何时完成持久化，仅保证任务已被安排。
        */
        entityService.saveEntity(entityData);
    }
}
```