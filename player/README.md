### Player Module

An enterprise-grade, powerful, scalable, and reliable system designed to handle the complex player data needs of
large-scale, multi-server environments. It supports multi-tier caching, built on `Caffeine`, `Redis`, and `MongoDB` to
achieve optimal performance and scalability for **handling thousands of players' data**.

### Usage

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

### Cache

This module uses `Caffeine` to store data for all *online* players and `Redis` to store hot data.

### Service Configuration

```java
public class Example {
    public static void main(String[] args) {
        // MongoDB
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                "playerdb",
                "mongodb://localhost:27017/",
                UuidRepresentation.STANDARD
        );

        // Redis
        Config redisConfig = new Config();
        redisConfig.useSingleServer().setAddress("redis://127.0.0.1:6379");

        // Create the service
        LegacyPlayerDataService service = LegacyPlayerDataService.of(
                "player-data-service",
                mongoConfig,
                redisConfig,
                basePackages, // List of packages to scan, e.g., List.of("com.example.player")
                classLoaders  // List of ClassLoaders, e.g., List.of(getClassLoader())
        );
    }
}
```

### Data Operations

Only suitable for data updates in single-instance scenarios.

```java
public class Example {
    public static void main(String[] args) {
        // Get player data
        LegacyPlayerData playerData = service.getLegacyPlayerData(player.getUniqueId());

        // Update data
        playerData.addData("coins", "1000").addData("rank", "VIP");
    }
}
```

When cross-server data consistency is required, we use `Redis Stream` to publish tasks to facilitate cross-server
communication and ensure cache consistency between servers.

```java
public class Example {
    public static void main(String[] args) {
        // Data to be updated
        Map<String, String> updates = new HashMap<>();
        updates.put("lastLogin", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "ONLINE");

        // Publish the RStream task
        service.pubRStreamTask(
                // Create a PlayerDataUpdateByName task via an Accepter
                PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        playerName,
                        updates,
                        Duration.ofMinutes(5)
                )
        );
    }
}
```

### Custom RStream Task Accepter (for Cross-Server Use)

```java
// Annotation to register the receiver
@RStreamAccepterRegister
public class CustomDataAccepter implements RStreamAccepterInterface {
    @Override
    public void accept(RStream<Object, Object> stream,
                       StreamMessageId id,
                       LegacyPlayerDataService service,
                       String data) {
        // Process incoming data
    }
}
```

### Batch Operations

```java
public class Example {
    public static void main(String[] args) {
        /*
          Get data for all online players from the L1 cache.
          Then, use L1ToL2PlayerDataSyncTask to synchronize all L1 (Caffeine) cache data to L2 (Redis).
         */
        service.getL1Cache().getCache().asMap().forEach((uuid, data) -> {
            L1ToL2PlayerDataSyncTask.of(uuid, service).start();
        });
    }
}
```
