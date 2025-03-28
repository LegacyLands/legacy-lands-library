### 玩家 (Player) 模块

一个企业级的强大、可扩展且可靠的系统，旨在处理大规模、多服务器环境的复杂玩家数据需求。
支持多级缓存，基于 `Caffeine`、`Redis` 和 `MongoDB` 构建，以实现 **处理数千名玩家数据** 的最佳性能和可扩展性。

### 用法

```kotlin
// Dependencies
dependencies {
    // all others module
    compileOnly("net.legacy.library:annotation:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:configuration:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:cache:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:commons:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:mongodb:1.0-SNAPSHOT")

    // player module
    compileOnly("net.legacy.library:player:1.0-SNAPSHOT")
}
```

### 缓存

该模块使用 `Caffeine` 来存储所有 `在线` 玩家的数据，并使用 `Redis` 存储热数据。

### 服务配置

```java
public class Example {
    public static void main(String[] args) {
        // mongodb
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                "playerdb",
                "mongodb://localhost:27017/",
                UuidRepresentation.STANDARD
        );

        // redis
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:6379");

        // 创建服务
        LegacyPlayerDataService service = LegacyPlayerDataService.of(
                "player-data-service",
                mongoConfig,
                redisConfig,
                // 扫描的包列表, 必须含有 "net.legacy.library.player"
                List.of("your.package", "net.legacy.library.player"),
                // 类加载器列表, 必须含有 PlayerLauncher.class.getClassLoader()
                List.of(PlayerLauncher.class.getClassLoader())
        );
    }
}
```

### 数据操作

仅适合用于单端情况下的数据更新。

```java
public class Example {
    public static void main(String[] args) {
        // 获取玩家数据
        LegacyPlayerData playerData = service.getLegacyPlayerData(player.getUniqueId());

        // 更新数据
        playerData.addData("coins", "1000").addData("rank", "VIP");
    }
}
```

当需要进行跨服涉及数据一致性时，我们使用 `Redis Stream` 发布任务来完成跨服务器通信，并确保服务器之间的缓存一致性。

```java
public class Example {
    public static void main(String[] args) {
        // 需要更新的数据
        Map<String, String> updates = new HashMap<>();
        updates.put("lastLogin", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "ONLINE");

        // 发布 RStream 任务
        service.pubRStreamTask(
                // 通过 Accepter (接受器) 来创建 PlayerDataUpdateByName 任务
                PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        playerName,
                        updates,
                        Duration.ofMinutes(5)
                )
        );
    }
}
```

### 自定义 RStream 任务接受器 (跨服使用)

```java
// 注册接收器的注解
@RStreamAccepterRegister
public class CustomDataAccepter implements RStreamAccepterInterface {
    @Override
    public void accept(RStream<Object, Object> stream,
                       StreamMessageId id,
                       LegacyPlayerDataService service,
                       String data) {
        // 处理传入的数据
    }
}
```

### 批处理操作

```java
public class Example {
    public static void main(String[] args) {
        /*
          从 L1 缓存获取所有在线玩家的数据
          随后使用 L1ToL2PlayerDataSyncTask 将 L1 (Caffeine) 缓存数据全部同步至 L2 (Redis)
         */
        service.getL1Cache().getCache().asMap().forEach((uuid, data) -> {
            L1ToL2PlayerDataSyncTask.of(uuid, service).start();
        });
    }
}
```
