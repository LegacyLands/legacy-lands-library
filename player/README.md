### Player Module

An enterprise-grade distributed data management framework for building high-performance entity-relationship data layers
for game services.
Based on a three-tier caching architecture (Caffeine, Redis, MongoDB), it provides TTL management, N-directional
relationship mapping, transactional operations, and complex query capabilities.
Supports cross-server data synchronization and distributed locking, suitable for complex data management needs in
large-scale multi-server environments, capable of **seamlessly handling relationships and state synchronization among
thousands of entities**.

### Usage

```kotlin
// Dependencies
dependencies {
    // Core dependency modules
    compileOnly("net.legacy.library:annotation:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:configuration:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:cache:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:commons:1.0-SNAPSHOT")
    compileOnly("net.legacy.library:mongodb:1.0-SNAPSHOT")

    // player module
    compileOnly("net.legacy.library:player:1.0-SNAPSHOT")
}
```

### Multi-tier Cache Design

The Player module employs a three-tier caching architecture to optimize performance and scalability:

- **L1 Cache (Caffeine)**: Local memory cache storing data for all online players. Provides nanosecond-level read/write
  performance, supports automatic expiration and size-based eviction policies. Each server instance maintains its own L1
  cache.
- **L2 Cache (Redis)**: Distributed cache storing hot data and shared data. Provides millisecond-level read/write
  performance, enables cross-server data sharing, implements distributed locks and Redis Stream message queues.
- **Persistence Layer (MongoDB)**: Provides reliable persistent storage. Supports complex queries and indexing, handles
  large datasets, suitable for infrequently accessed historical data.

### Read Path

1. First attempt to read data from L1 cache (Caffeine)
2. If L1 cache misses, attempt to read from L2 cache (Redis)
3. If L2 cache misses, read from the database (MongoDB)
4. Once data is loaded, it automatically populates L1 and L2 caches

### Write Path

1. Data is first written to L1 cache (Caffeine)
2. Synchronized to L2 cache (Redis) via Redis Stream
3. Scheduled tasks or explicit calls persist data to the database (MongoDB)

### Performance Benchmarks

### Hardware Configuration

Note: These are test results based on a single machine in a home environment. Performance will be significantly improved
in production environments with enterprise-grade hardware configurations and multi-cluster deployments.

```
Processor: AMD Ryzen 9 7940H w/Radeon 780M Graphics (16 cores, average running frequency: 3.79GHz)
Memory: 30GB DDR5 RAM
Storage: NVMe SSD (953.9GB)
System: Ubuntu 24.04 LTS (Linux 6.11.0-26-generic)
Java: OpenJDK 21.0.6 LTS (Amazon Corretto)
```

### Player Data Service Performance

This QPS data is an **extremely conservative estimate** based on complete test cycles. The testing included non-core
business operations, and these overheads diluted the actual business processing performance.

**Why actual QPS will be higher:**

The test environment intentionally included performance limiting factors that don't exist in production environments:
forced 2-second wait times, complete distributed synchronization processes, strict lock contention scenarios, etc.
In actual deployment, L1 cache's nanosecond-level access speed can easily achieve 10,000+ QPS, while L2 cache's
optimized access patterns can achieve 3,000 - 8,000 QPS.
More importantly, this framework adopts a three-tier cache architecture, where most data access will hit L1 memory
cache, achieving performance close to theoretical limits.

**1. Player Data Batch Save Benchmark:**

```
Test Configuration: 8 concurrent threads, unified batch strategy
Thread Configuration: Each thread processes 250 players (2000/8)
Total Players: 2,000 players
Save QPS: 3,992.0 ops/s
Sync QPS: ∞ (instant completion)
Batch Latency: 501.00ms
Total Duration: 501ms
Success Rate: 100% (service health check passed)
```

**2. Entity Data Batch Save Benchmark:**

```
Test Configuration: 6 concurrent threads, unified batch strategy
Batch Configuration: Each batch processes 500 entities (3000/6)
Total Entities: 3,000 entities
Batch QPS: 1,113.2 ops/s
Retrieval QPS: 2,325.6 ops/s (100 sample tests)
Batch Latency: 695.00ms
Total Duration: 2,695ms
Success Rate: 100% (service health check passed)
```

**3. Mixed Workload Performance Test:**

```
Test Configuration: 4 concurrent threads, mixed player+entity operations
Thread Configuration: Each thread 300 players + 120 entities
Player Data: 1,200 players (4x300)
Entity Data: 480 entities (4x120, 20 entities per 50 players)
Mixed QPS: 4,127.8 ops/s
Player Sync Throughput: ∞ ops/s (instant)
Total Operations: 1,680 operations
Duration: 407ms
```

**4. Lock Contention Impact Test:**

```
Test Configuration: 4 concurrent threads, comparing individual vs batch strategies
Thread Configuration: Each thread processes 12-13 entities (50/4)
Individual Save Strategy (high lock contention): 1,600.0 QPS
Batch Save Strategy (zero lock contention): 3,846.2 QPS
Performance Improvement: 2.4x
Lock Contention Performance Loss: 58.4%
Duration Improvement: 2.3x
```

### Best Practices

**Core Principle**: Most of the time we operate **only on memory**. Unless there are strong consistency requirements, it
is not recommended to **manually save directly** to different levels of cache or database after changing data, which
will greatly reduce performance.

**Recommended Performance Optimization Strategies:**

1. **Unified Batch Saving**: Use `saveLegacyPlayersData(List)` and `saveEntities(List)` instead of calling individual
   save in loops
2. **Prepare-Aggregate-Execute Pattern**: Multi-threaded data preparation → Collect all data → Single batch save
3. **Avoid Distributed Lock Contention**: Reduce concurrent save operations from N times to 1 time, achieving 2.4x
   performance improvement
4. **Memory-First Strategy**: Pure memory operations for hot data, asynchronous batch persistence, achieving
   nanosecond-level response times

Based on actual test data, batch saving strategy can improve QPS from 1,600 to 3,846 compared to individual saving,
reducing 58.4% of lock contention performance loss.

### Basic Service Configuration

```java
public class ServiceInitExample {
    public static void main(String[] args) {
        // 1. Create MongoDB connection config
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                "playerdb",  // Database name
                "mongodb://localhost:27017/",  // MongoDB connection URI
                UuidRepresentation.STANDARD  // UUID representation
        );

        // 2. Create Redis config
        Config redisConfig = new Config();
        redisConfig.useSingleServer()
                .setAddress("redis://127.0.0.1:6379")
                .setDatabase(0)
                .setConnectionMinimumIdleSize(5)
                .setConnectionPoolSize(10);

        // 3. Create player data service (using default configuration)
        LegacyPlayerDataService service = LegacyPlayerDataService.of(
                "player-data-service",  // Service name, used to distinguish multiple service instances
                mongoConfig,  // MongoDB configuration
                redisConfig,  // Redis configuration
                // List of packages to scan, for auto-discovering RStreamAccepter implementations
                List.of("your.package", "net.legacy.library.player"),
                // List of ClassLoaders for scanning annotations
                List.of(PlayerLauncher.class.getClassLoader())
        );
    }
}
```

### Database Index Management (Optional but Recommended)

To optimize query performance, especially for `findEntitiesByType`, `findEntitiesByAttribute`, and
`findEntitiesByRelationship`, it's crucial to create appropriate indexes in MongoDB. The `LegacyIndexManager` class
helps manage these indexes.

It's recommended to call the necessary `ensure...` methods during your application's startup sequence, after
initializing the `MongoDBConnectionConfig` and related services.

```java
public class IndexInitializationExample {
    public void initializeIndexes(MongoDBConnectionConfig mongoConfig) {
        // Check if config is valid
        if (mongoConfig == null || mongoConfig.getDatastore() == null) {
            System.err.println("Cannot initialize indexes: MongoDB connection not configured.");
            return;
        }

        try {
            // Create the index manager
            LegacyIndexManager indexManager = LegacyIndexManager.of(mongoConfig);

            // === Ensure indexes for LegacyEntityData ===

            // Index for querying by entityType (Highly recommended)
            indexManager.ensureEntityTypeIndex();

            // Example: Index a commonly queried attribute (e.g., "status"). Use sparse=true for optional attributes.
            indexManager.ensureAttributeIndex("status", true);

            // Example: Index a commonly queried relationship type (e.g., "member").
            indexManager.ensureRelationshipIndex("member");
            indexManager.ensureRelationshipIndex("owner"); // Add for other types as needed

            // === Ensure indexes for LegacyPlayerData ===

            // Example: Index a commonly queried player data field (e.g., "guildId").
            indexManager.ensurePlayerDataIndex("guildId", true);
        } catch (Exception exception) {
            exception.printStackTrace();
        }
    }
}
```

**Note:** The `ensure...` methods are idempotent. Calling them repeatedly on startup is safe and ensures the indexes
exist without causing errors if they are already present.

### Advanced Service Configuration

```java
public class AdvancedServiceInitExample {
    public static void main(String[] args) {
        // Create service with custom configuration
        LegacyPlayerDataService customService = LegacyPlayerDataService.of(
                "custom-player-service",
                mongoConfig,
                redisConfig,
                Duration.ofMinutes(15),  // Auto-save interval (default 2 hours)
                List.of("your.package", "net.legacy.library.player"),
                List.of(PlayerLauncher.class.getClassLoader()),
                Duration.ofSeconds(1)  // Redis Stream polling interval (default 2 seconds)
        );
    }
}
```

### Basic Data CRUD Operations

```java
public class PlayerDataExample {
    public void basicOperations(LegacyPlayerDataService service, UUID playerUuid) {
        // 1. Get player data
        LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

        // 2. Read single attribute
        String coins = playerData.getData("coins");  // Returns null if doesn't exist

        // 3. Read with type conversion
        Integer coinsValue = playerData.getData("coins", Integer::parseInt);
        Long lastLoginTime = playerData.getData("lastLogin", Long::parseLong);
        Boolean isPremium = playerData.getData("premium", Boolean::parseBoolean);

        // 4. Add/update single attribute
        playerData.addData("level", "5");  // All values are stored as strings

        // 5. Add/update multiple attributes
        Map<String, String> playerStats = new HashMap<>();
        playerStats.put("strength", "10");
        playerStats.put("agility", "15");
        playerStats.put("intelligence", "20");
        playerStats.put("lastUpdated", String.valueOf(System.currentTimeMillis()));
        playerData.addData(playerStats);  // Batch update

        // 6. Remove attribute
        playerData.removeData("temporaryBuff");  // Remove single attribute
    }
}
```

### Single-Server Hot Data

```java
public class EasyPlayerRawCacheExample {
    public void singleServerHotDataUsage() {
        // 1. Create an EasyPlayerRawCacheDataService instance
        EasyPlayerRawCacheDataService cacheService = new EasyPlayerRawCacheDataService("game-session-cache");

        // 2. Get the player's temporary data cache (the boolean parameter in get method means create if not exists)
        UUID playerUuid = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        EasyPlayerRawCacheData playerCache = cacheService.get(playerUuid, true).orElseThrow();

        // 3. Store temporary session data (this data only exists in the current server's memory, not persisted)
        playerCache.getRawCache().getResource().put("lastClickTime", String.valueOf(System.currentTimeMillis()));
        playerCache.getRawCache().getResource().put("currentZone", "pvp_arena_5");
        playerCache.getRawCache().getResource().put("temporaryBuffs", "speed:30,strength:15");

        // 4. Read temporary session data
        String lastClickTime = playerCache.getRawCache().getResource().getIfPresent("lastClickTime");
        String currentZone = playerCache.getRawCache().getResource().getIfPresent("currentZone");

        // 5. Create custom cache for specific functionality (e.g., player's current session kill records)
        CacheServiceInterface<Cache<UUID, Integer>, Integer> killCountCache =
                CacheServiceFactory.createCaffeineCache();
        playerCache.addCustomRawCache("killCount", killCountCache);

        // Use custom cache
        Cache<UUID, Integer> killCounts = playerCache.getCustomRawCache("killCount").getResource();

        // Record player kill
        UUID victimId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");
        killCounts.put(victimId, 1);

        // Check if player has specific kill record
        boolean hasKilled = killCounts.getIfPresent(victimId) != null;

        // Increase kill count
        Integer currentKills = killCounts.getIfPresent(victimId);
        if (currentKills != null) {
            killCounts.put(victimId, currentKills + 1);
        }

        // 6. Check if player has a specific type of custom cache
        boolean hasInventoryCache = playerCache.hasCustomRawCache("tempInventory");

        // 7. Get the same cache service from another service
        EasyPlayerRawCacheDataService sameService =
                EasyPlayerRawCacheDataService.EASY_PLAYER_RAW_CACHE_DATA_SERVICES
                        .getResource()
                        .getIfPresent("game-session-cache");

        // 8. Usage scenario: Store temporary leaderboard data for player's current session
        CacheServiceInterface<Cache<String, Long>, Long> scoreCache =
                CacheServiceFactory.createCaffeineCache();
        playerCache.addCustomRawCache("sessionScores", scoreCache);

        Cache<String, Long> scores = playerCache.getCustomRawCache("sessionScores").getResource();
        scores.put("kills", 15L);
        scores.put("deaths", 3L);
        scores.put("assists", 7L);
        scores.put("flagCaptures", 2L);

        // Calculate temporary KDA ratio (only calculates for current game session, doesn't affect persistent data)
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

### Handling Non-existent Data

```java
public class PlayerDataHandlingExample {
    public void handleNonexistentData(LegacyPlayerDataService service, UUID playerUuid) {
        // Get player data - returns a new empty object even if it doesn't exist in the database
        LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

        // Check if specific attribute exists
        String rank = playerData.getData("rank");
        if (rank == null) {
            // Attribute doesn't exist, set default value
            playerData.addData("rank", "DEFAULT");
        }

        // Safely read numerical value with default
        int coins = Optional.ofNullable(playerData.getData("coins"))
                .map(Integer::parseInt)
                .orElse(0);  // If coins attribute doesn't exist or parsing fails, return 0

        // Can also use Java 8 Stream to process batch data
        boolean hasAllRequiredFields = Stream.of("name", "rank", "level")
                .allMatch(field -> playerData.getData(field) != null);
    }
}
```

## Cross-Server Data Synchronization

When you need to synchronize data changes across multiple servers, the Redis Stream mechanism ensures data consistency.

### Update Data by Player Name

Note: This method is asynchronous, returning immediately after publishing. Data updates will be executed asynchronously
on all subscribed servers.

```java
public class CrossServerUpdateExample {
    public void updatePlayerByName(LegacyPlayerDataService service, String playerName) {
        // 1. Prepare data to update
        Map<String, String> updates = new HashMap<>();
        updates.put("lastLogin", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "ONLINE");
        updates.put("currentServer", "lobby-1");

        // 2. Create and publish update task
        // This will send a message to Redis Stream, which all subscribed servers will receive and process
        service.pubRStreamTask(
                PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        playerName,  // Player name
                        updates,  // Data to update
                        Duration.ofMinutes(5)  // Task lifetime in Stream
                )
        );
    }
}
```

### Update Data by UUID

```java
public class CrossServerUpdateByUuidExample {
    public void updatePlayerByUuid(LegacyPlayerDataService service, UUID playerUuid) {
        // Prepare data to update
        Map<String, String> updates = new HashMap<>();
        updates.put("lastSeen", String.valueOf(System.currentTimeMillis()));
        updates.put("status", "OFFLINE");

        // Create and publish UUID update task
        service.pubRStreamTask(
                PlayerDataUpdateByUuidRStreamAccepter.createRStreamTask(
                        playerUuid,  // Player UUID
                        updates,  // Data to update
                        Duration.ofMinutes(5)  // Task timeout
                )
        );
    }
}
```

### Custom Data Synchronization

You can create custom Redis Stream processors to handle specific data synchronization needs:

```java

@RStreamAccepterRegister  // This annotation allows the system to automatically discover and register this accepter
public class PlayerAchievementUpdateAccepter implements RStreamAccepterInterface {
    // Optionally use GsonUtil from commons
    private static final Gson GSON = new Gson();

    // Define the name of this accepter
    @Override
    public String getActionName() {
        return "player-achievement-update";
    }

    // Whether the same task should be processed multiple times on a single server
    @Override
    public boolean isRecordLimit() {
        return true;
    }

    // Process received data
    @Override
    public void accept(RStream<Object, Object> stream,
                       StreamMessageId id,
                       LegacyPlayerDataService service,
                       String data) {
        try {
            // Parse task data
            AchievementData achievementData = GSON.fromJson(
                    data, new TypeToken<AchievementData>() {
                    }.getType()
            );

            // Get player data
            UUID playerUuid = UUID.fromString(achievementData.getPlayerId());
            LegacyPlayerData playerData = service.getLegacyPlayerData(playerUuid);

            // Update achievement data
            String existingAchievements = playerData.getData("achievements");
            // Process achievement logic...

            // Update player data
            playerData.addData("achievements", updatedAchievements);
            playerData.addData("lastAchievementTime", String.valueOf(System.currentTimeMillis()));

            // Acknowledge task completion after successful processing (delete message)
            ack(stream, id);
        } catch (Exception exception) {
            // Handle failure, don't acknowledge task, allow retry or processing by other servers
            log.error("Failed to process achievement update", exception);
        }
    }

    // Create achievement update task
    public static RStreamTask createTask(UUID playerId, String achievementId, Duration expiry) {
        AchievementData data = new AchievementData(playerId.toString(), achievementId);
        return RStreamTask.of(
                "player-achievement-update",
                GSON.toJson(data),
                expiry
        );
    }

    // Internal data class
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

The above example is for `LegacyPlayerData`.
If you want to create it for `LegacyEntityData`, you only need to use `EntityRStreamAccepterRegister` and
`EntityRStreamAccepterInterface`.

## Stream Accepter Resilience Framework

For handling failures in Redis Stream accepter operations, traditional manual exception handling lacks standardization
and makes it difficult to ensure system consistency. To address this, we provide a comprehensive resilience framework
with configurable retry policies, exception type recognition, compensation mechanisms, and monitoring support while
maintaining full backward compatibility.

It also provides an enterprise-grade hybrid retry counter system for distributed stream operations, providing precise
retry control across multiple servers with both local and distributed counting strategies.

### Basic Usage

```java
public class ResilienceExample {
    public void basicResilience(LegacyPlayerDataService playerService) {
        // Enable resilience for all discovered stream accepters
        List<String> basePackages = List.of("your.package", "net.legacy.library.player");
        List<ClassLoader> classLoaders = List.of(PlayerLauncher.class.getClassLoader());

        // Create resilient task with default settings (3 retries, exponential backoff)
        ResilientRStreamAccepterInvokeTask resilientTask =
                ResilientRStreamAccepterInvokeTask.ofResilient(
                        playerService, basePackages, classLoaders, Duration.ofSeconds(5)
                );
        resilientTask.start();
    }
}
```

### Custom Resilience Strategies

```java
public class CustomResilienceExample {
    public void customStrategies() {
        // Strategy 1: Retry only network errors
        ResilientRStreamAccepter networkResilient = ResilienceFactory.createForNetworkErrors(originalAccepter);

        // Strategy 2: Fast retry (more attempts, shorter delays)
        ResilientRStreamAccepter fastRetry = ResilienceFactory.createFastRetry(originalAccepter);

        // Strategy 3: Conservative retry (fewer attempts, longer delays)
        ResilientRStreamAccepter conservativeRetry = ResilienceFactory.createConservativeRetry(originalAccepter);

        // Strategy 4: Custom policy with specific compensation
        RetryPolicy customPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofSeconds(2))
                .exponentialBackoff(true)
                .retryCondition(ex -> ex instanceof IOException)
                .build();

        CompensationAction customCompensation = context -> {
            // Custom cleanup logic
            Log.error("Failed after %s attempts: %s",
                    context.getAttemptNumber(), context.getException().getMessage());
            context.getStream().remove(context.getMessageId());
        };

        ResilientRStreamAccepter customResilient = ResilienceFactory.createCustom(
                originalAccepter, customPolicy, customCompensation
        );
    }
}
```

### Monitoring and Management

```java
public class ResilienceMonitoringExample {
    public void monitorResilience(ResilientRStreamAccepter resilientAccepter) {
        // Monitor retry statistics
        StreamMessageId messageId = new StreamMessageId(System.currentTimeMillis(), 0);
        int retryCount = resilientAccepter.getRetryCount(messageId);
        int totalTracked = resilientAccepter.getTrackedMessageCount();

        Log.info("Message %s has been retried %s times", messageId, retryCount);
        Log.info("Currently tracking %s messages for retries", totalTracked);

        // Clear retry tracking for completed messages
        resilientAccepter.clearRetryTracking(messageId);
    }
}
```

### Failure Handling Patterns

```java
public class FailureHandlingPatternsExample {
    public void handleNetworkErrors(RStreamAccepterInterface accepter) {
        // Retry network errors with exponential backoff
        ResilientRStreamAccepter networkResilient = ResilienceFactory.createForNetworkErrors(accepter);
    }

    public void handleDataValidationErrors(RStreamAccepterInterface accepter) {
        // Don't retry validation errors, just log and remove
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
        // Conservative retry with longer delays
        ResilientRStreamAccepter conservativeResilient = ResilienceFactory.createConservativeRetry(accepter);
    }
}
```

### Basic Retry Counter Usage

```java
public class RetryCounterExample {
    public void basicRetryCounters(RedissonClient redissonClient) {
        // 1. Local retry counter (in-memory, high performance)
        RetryCounter localCounter = LocalRetryCounter.create();

        // 2. Distributed retry counter (Redis-based, globally consistent)
        RetryCounter distributedCounter = DistributedRetryCounter.create(redissonClient);

        // 3. Hybrid retry counter (intelligent selection)
        RetryCounter hybridCounter = HybridRetryCounter.create(
                redissonClient,
                key -> key.startsWith("critical:") // Use distributed for critical operations
        );

        // Basic operations (all methods return CompletableFuture)
        CompletableFuture<Integer> count = localCounter.increment("operation:123");
        CompletableFuture<Integer> countWithTTL = localCounter.increment("operation:123", Duration.ofMinutes(5));
        CompletableFuture<Integer> currentCount = localCounter.get("operation:123");
        CompletableFuture<Boolean> exists = localCounter.exists("operation:123");
        CompletableFuture<Void> reset = localCounter.reset("operation:123");
    }
}
```

### Resilience Framework Configuration

```java
public class ResilienceConfigurationExample {
    public void configureResilience(LegacyPlayerDataService playerService) {
        // 1. Basic resilient stream accepter with default settings
        List<String> basePackages = List.of("your.package", "net.legacy.library.player");
        List<ClassLoader> classLoaders = List.of(PlayerLauncher.class.getClassLoader());

        ResilientRStreamAccepterInvokeTask resilientTask =
                ResilientRStreamAccepterInvokeTask.ofResilient(
                        playerService, basePackages, classLoaders, Duration.ofSeconds(5)
                );
        resilientTask.start();

        // 2. Custom retry policy with hybrid counting
        RetryPolicy hybridPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofSeconds(1))
                .exponentialBackoff(true)
                .retryCounterType(RetryCounterType.HYBRID)
                .distributedKeyPattern("critical:.*")
                .retryCondition(ex -> ex instanceof IOException || ex instanceof ConnectException)
                .build();

        // 3. Custom failure handler with compensation
        FailureHandler customHandler = FailureHandler.withPolicy(
                hybridPolicy,
                CompensationAction.composite(
                        CompensationAction.LOG_FAILURE,
                        CompensationAction.REMOVE_MESSAGE
                )
        );

        // 4. Create resilient accepter with custom configuration
        RStreamAccepterInterface originalAccepter = new YourCustomAccepter();
        ResilientRStreamAccepter resilientAccepter = new ResilientRStreamAccepter(
                originalAccepter, customHandler, redissonClient
        );
    }
}
```

### Retry Policy Patterns

```java
public class RetryPolicyPatternsExample {
    public void demonstratePolicyPatterns() {
        // 1. Network error retry (exponential backoff for IO operations)
        RetryPolicy networkPolicy = RetryPolicy.forExceptionTypes(
                IOException.class, ConnectException.class, SocketTimeoutException.class
        );

        // 2. Conservative retry (fewer attempts, longer delays)
        RetryPolicy conservativePolicy = RetryPolicy.builder()
                .maxAttempts(2)
                .baseDelay(Duration.ofSeconds(5))
                .exponentialBackoff(true)
                .maxDelay(Duration.ofMinutes(2))
                .build();

        // 3. Fast retry (more attempts, shorter delays)
        RetryPolicy fastPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofMillis(500))
                .exponentialBackoff(true)
                .maxDelay(Duration.ofSeconds(10))
                .build();

        // 4. Hybrid counting with distributed coordination
        RetryPolicy hybridPolicy = RetryPolicy.withHybridCounting("payment:.*|inventory:.*");

        // 5. Distributed counting for all operations
        RetryPolicy distributedPolicy = RetryPolicy.withDistributedCounting();

        // 6. No retry policy (fail fast)
        RetryPolicy noRetryPolicy = RetryPolicy.noRetry();
    }
}
```

### Advanced Resilience Factory Usage

```java
public class AdvancedResilienceExample {
    public void advancedResiliencePatterns(RStreamAccepterInterface originalAccepter) {
        // 1. Pre-configured resilience strategies
        ResilientRStreamAccepter networkResilient =
                ResilienceFactory.createForNetworkErrors(originalAccepter);

        ResilientRStreamAccepter fastRetry =
                ResilienceFactory.createFastRetry(originalAccepter);

        ResilientRStreamAccepter conservativeRetry =
                ResilienceFactory.createConservativeRetry(originalAccepter);

        // 2. Custom resilience with specific requirements
        RetryPolicy customPolicy = RetryPolicy.builder()
                .maxAttempts(3)
                .baseDelay(Duration.ofSeconds(2))
                .retryCounterType(RetryCounterType.DISTRIBUTED)
                .retryCondition(exception -> {
                    // Custom retry logic
                    if (exception instanceof ValidationException) {
                        return false; // Don't retry validation errors
                    }
                    if (exception instanceof TransientException) {
                        return true; // Always retry transient errors
                    }
                    return exception.getMessage().contains("timeout");
                })
                .build();

        CompensationAction customCompensation = context -> {
            // Custom compensation logic
            Log.error("Operation failed after {} attempts: {}",
                    context.getAttemptNumber(), context.getException().getMessage());

            // Custom cleanup
            if (context.getException() instanceof DataCorruptionException) {
                // Perform data recovery
                performDataRecovery(context);
            }

            // Remove message from stream
            context.getStream().remove(context.getMessageId());
        };

        ResilientRStreamAccepter customResilient = ResilienceFactory.createCustom(
                originalAccepter, customPolicy, customCompensation
        );

        // 3. Monitoring retry statistics
        StreamMessageId messageId = new StreamMessageId(System.currentTimeMillis(), 0);
        int retryCount = customResilient.getRetryCount(messageId);
        int totalTracked = customResilient.getTrackedMessageCount();

        Log.info("Message {} has been retried {} times", messageId, retryCount);
        Log.info("Currently tracking {} messages for retries", totalTracked);

        // Clean up completed message tracking
        customResilient.clearRetryTracking(messageId);
    }

    private void performDataRecovery(FailureContext context) {
        // Implementation for data recovery
    }
}
```

### Virtual Thread Integration

```java
public class VirtualThreadExample {
    public void virtualThreadIntegration(RedissonClient redissonClient) {
        // 1. Create TaskInterface for virtual thread execution
        TaskInterface<?> taskInterface = new TaskInterface<Object>() {
        };

        // 2. Create distributed retry counter with virtual threads
        DistributedRetryCounter virtualThreadCounter = DistributedRetryCounter.create(
                redissonClient,
                "vthread:counter:",
                taskInterface
        );

        // 3. All operations now use virtual threads for I/O operations
        CompletableFuture<Integer> asyncIncrement = virtualThreadCounter.increment("key:123");
        CompletableFuture<Integer> asyncIncrementWithTTL = virtualThreadCounter.increment(
                "key:123", Duration.ofMinutes(10)
        );

        // 4. Chain virtual thread operations
        virtualThreadCounter.increment("operation:abc")
                .thenCompose(count -> {
                    if (count > 3) {
                        return virtualThreadCounter.reset("operation:abc");
                    }
                    return CompletableFuture.completedFuture(null);
                })
                .thenRun(() -> Log.info("Operation completed"))
                .exceptionally(throwable -> {
                    Log.error("Operation failed", throwable);
                    return null;
                });

        // 5. Batch operations with virtual threads
        List<CompletableFuture<Integer>> futures = IntStream.range(0, 100)
                .mapToObj(i -> virtualThreadCounter.increment("batch:operation:" + i))
                .collect(Collectors.toList());

        CompletableFuture.allOf(futures.toArray(new CompletableFuture[0]))
                .thenRun(() -> Log.info("All batch operations completed"));
    }
}
```

### Complete Integration Example

```java
public class CompleteIntegrationExample {
    public void completeStreamResilienceSetup(
            LegacyPlayerDataService playerService,
            RedissonClient redissonClient) {

        // 1. Configure retry policy with hybrid counting
        RetryPolicy hybridPolicy = RetryPolicy.builder()
                .maxAttempts(5)
                .baseDelay(Duration.ofSeconds(1))
                .exponentialBackoff(true)
                .retryCounterType(RetryCounterType.HYBRID)
                .distributedKeyPattern("critical:.*|payment:.*|inventory:.*")
                .useDistributedPredicate(key -> {
                    // Custom logic for distributed vs local counting
                    return key.contains("critical") ||
                            key.contains("payment") ||
                            key.contains("transaction");
                })
                .retryCondition(exception -> {
                    // Comprehensive retry condition
                    return exception instanceof IOException ||
                            exception instanceof ConnectException ||
                            exception instanceof RedisCommandTimeoutException ||
                            (exception instanceof RuntimeException &&
                                    exception.getCause() instanceof SocketTimeoutException);
                })
                .build();

        // 2. Create failure handler with monitoring
        FailureHandler monitoringHandler = context -> {
            RetryPolicy policy = RetryPolicy.defaultPolicy();

            if (context.getAttemptNumber() <= policy.getMaxAttempts() &&
                    policy.shouldRetry(context.getException())) {

                // Log retry attempt
                Log.warn("Retrying operation (attempt {}/{}): {}",
                        context.getAttemptNumber(),
                        policy.getMaxAttempts(),
                        context.getException().getMessage());

                return FailureHandlingResult.retry(
                        policy.calculateDelay(context.getAttemptNumber())
                );
            }

            // Log final failure and compensate
            Log.error("Operation failed permanently after {} attempts",
                    context.getAttemptNumber(), context.getException());

            return FailureHandlingResult.giveUp(
                    CompensationAction.composite(
                            CompensationAction.LOG_FAILURE,
                            CompensationAction.REMOVE_MESSAGE,
                            customNotificationAction(context)
                    )
            );
        };

        // 3. Create resilient task with virtual threads
        TaskInterface<?> virtualThreadInterface = new TaskInterface<Object>() {
        };

        ResilientRStreamAccepterInvokeTask resilientTask =
                ResilientRStreamAccepterInvokeTask.create(
                        playerService,
                        List.of("your.package", "net.legacy.library.player"),
                        List.of(PlayerLauncher.class.getClassLoader()),
                        Duration.ofSeconds(2),
                        monitoringHandler,
                        virtualThreadInterface
                );

        // 4. Start the resilient processing
        resilientTask.start();

        // 5. Monitor the system
        CompletableFuture.runAsync(() -> {
            while (true) {
                try {
                    Thread.sleep(30000); // Check every 30 seconds

                    // Monitor retry counters if needed
                    Log.info("Resilient stream processing is running");

                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        });
    }

    private CompensationAction customNotificationAction(FailureContext context) {
        return ctx -> {
            // Send notification to monitoring system
            notifyOperationFailure(
                    ctx.getMessageId().toString(),
                    ctx.getException().getClass().getSimpleName(),
                    ctx.getAttemptNumber()
            );
        };
    }

    private void notifyOperationFailure(String messageId, String errorType, int attempts) {
        // Implementation for failure notification
    }
}
```

### Entity Data Management System

In addition to player data, we also provide a flexible entity data management system suitable for any game objects that
need persistent storage, such as guilds.

### Entity Service Initialization

```java
public class EntityServiceExample {
    public void initEntityService() {
        // Create entity data service
        LegacyEntityDataService entityService = LegacyEntityDataService.of(
                "game-entity-service",  // Service name
                mongoConfig,  // MongoDB configuration
                redisConfig,  // Redis configuration
                List.of("your.package", "net.legacy.library.player"),  // Package scan list
                List.of(PlayerLauncher.class.getClassLoader())  // ClassLoader list
        );
    }
}
```

### Creating and Managing Entities

```java
public class EntityManagementExample {
    public void manageEntities(LegacyEntityDataService entityService) {
        // 1. Create guild entity
        UUID guildId = UUID.randomUUID();
        LegacyEntityData guild = LegacyEntityData.of(guildId, "guild");

        // 2. Set entity attributes
        guild.addAttribute("name", "Shadow Warriors");
        guild.addAttribute("level", "5");
        guild.addAttribute("founded", String.valueOf(System.currentTimeMillis()));
        guild.addAttribute("description", "Elite PvP guild for hardcore players");

        // 3. Add attributes in batch
        Map<String, String> guildStats = new HashMap<>();
        guildStats.put("members", "15");
        guildStats.put("maxMembers", "20");
        guildStats.put("reputation", "850");
        guild.addAttributes(guildStats);

        // 4. Establish entity relationships
        UUID leaderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        UUID viceLeaderId = UUID.fromString("550e8400-e29b-41d4-a716-446655440001");

        // Add member relationships
        guild.addRelationship("leader", leaderId);
        guild.addRelationship("officer", viceLeaderId);

        // Add multiple regular members
        for (UUID memberId : memberIds) {
            guild.addRelationship("member", memberId);
        }

        // 6. Check relationships
        boolean isLeader = guild.hasRelationship("leader", leaderId);  // true
        int memberCount = guild.countRelationships("member");  // Returns number of members

        // 7. Get all entity IDs with a specific relationship
        Set<UUID> allMembers = guild.getRelatedEntities("member");

        // 8. Remove relationship
        guild.removeRelationship("member", leavingMemberId);

        // 9. Modify attribute
        guild.addAttribute("reputation", "900");  // Update reputation value

        /* 
          10. Save Entity: Immediately puts the entity data into the L1 cache (local memory) and schedules an asynchronous task
              to persist it to the L2 cache (Redis) and the database (MongoDB).
            - Why call this? To ensure data is eventually saved, can be recovered after a server restart, and is accessible
              to other services via Redis or the database.
            - What if not called? Changes made to the entity (like adding/modifying attributes, adding/removing relationships)
              exist only in the current server's memory (L1). If the service shuts down or the entity is evicted from L1,
              unsaved changes will be lost.
            - Note: This method returns immediately and does not wait for persistence to complete.
        */
        entityService.saveEntity(guild);
    }
}
```

### Complex Entity Queries

```java
public class EntityQueryExample {
    public void queryEntities(LegacyEntityDataService entityService) {
        // 1. Query single entity by ID
        UUID entityId = UUID.fromString("550e8400-e29b-41d4-a716-446655440000");
        LegacyEntityData entity = entityService.getEntityData(entityId);

        // 2. Query all entities by type
        List<LegacyEntityData> allGuilds = entityService.findEntitiesByType("guild");

        // 3. Query entities by attribute
        List<LegacyEntityData> level5Guilds = entityService.findEntitiesByAttribute("level", "5");

        // 4. Query entities by relationship
        // Example: Find all guilds that a player is a member of
        UUID playerId = UUID.fromString("550e8400-e29b-41d4-a716-446655440005");
        List<LegacyEntityData> playerGuilds = entityService.findEntitiesByRelationship("member", playerId);

        // 5. Combined queries (custom implementation)
        // Example: Find guilds where player is leader and level is greater than 3
        List<LegacyEntityData> leaderGuilds = entityService.findEntitiesByRelationship("leader", playerId);
        leaderGuilds.removeIf(guild -> {
            String level = guild.getAttribute("level");
            return level == null || Integer.parseInt(level) <= 3;
        });
    }
}
```

### Entity Data Relationship Examples

Here are some specific scenarios using the entity relationship system:

```java
public class EntityRelationshipExamples {

    // Example: Create game guild system
    public void createGuildSystem(LegacyEntityDataService entityService) {
        // Create guild
        LegacyEntityData guild = LegacyEntityData.of(UUID.randomUUID(), "guild");
        guild.addAttribute("name", "Dragon Knights");

        // Create guild hall
        LegacyEntityData guildHall = LegacyEntityData.of(UUID.randomUUID(), "building");
        guildHall.addAttribute("name", "Dragon's Lair");
        guildHall.addAttribute("type", "guild_hall");

        // Establish bidirectional relationship
        guild.addRelationship("owns", guildHall.getUuid());
        guildHall.addRelationship("owned_by", guild.getUuid());

        // Save both entities
        entityService.saveEntity(guild);
        entityService.saveEntity(guildHall);
    }

    // Example: Create item inventory system
    public void createInventorySystem(LegacyEntityDataService entityService, UUID playerId) {
        // Player entity (may already exist)
        LegacyEntityData playerEntity = entityService.getEntityData(playerId);
        if (playerEntity == null) {
            playerEntity = LegacyEntityData.of(playerId, "player");
            playerEntity.addAttribute("name", "GameMaster");
        }

        // Create backpack entity
        LegacyEntityData backpack = LegacyEntityData.of(UUID.randomUUID(), "container");
        backpack.addAttribute("type", "backpack");
        backpack.addAttribute("slots", "20");

        // Create item
        LegacyEntityData sword = LegacyEntityData.of(UUID.randomUUID(), "item");
        sword.addAttribute("name", "Excalibur");
        sword.addAttribute("damage", "50");

        // Establish relationship chain
        playerEntity.addRelationship("owns", backpack.getUuid());
        backpack.addRelationship("contains", sword.getUuid());

        // Save all entities
        entityService.saveEntity(playerEntity);
        entityService.saveEntity(backpack);
        entityService.saveEntity(sword);
    }
}
```

### Manual L1 to L2 Cache Synchronization

```java
public class CacheSyncExample {
    public void syncCaches(LegacyPlayerDataService service) {
        // 1. Synchronize cache for a single player (direct method)
        UUID playerUuid = player.getUniqueId();

        // Directly synchronize specific player on current server
        L1ToL2PlayerDataSyncTask.of(playerUuid, service).start();

        // 2. Synchronize via Redis Stream (cross-server)

        // Sync by player name
        service.pubRStreamTask(
                L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PlayerName",  // Player name
                        Duration.ofSeconds(10)  // Task expiry time
                )
        );

        // Sync by UUID
        service.pubRStreamTask(
                L1ToL2PlayerDataSyncByUuidRStreamAccepter.createRStreamTask(
                        playerUuid,  // Player UUID
                        Duration.ofSeconds(10)  // Task expiry time
                )
        );

        // 3. Bulk synchronize all online players
        service.getL1Cache().getResource().asMap().forEach((uuid, data) -> {
            L1ToL2PlayerDataSyncTask.of(uuid, service).start();
        });
    }
}
```

### Manual Data Persistence

```java
public class PersistenceExample {
    public void persistData(LegacyPlayerDataService playerService,
                            LegacyEntityDataService entityService) {
        // 1. Create lock settings
        LockSettings lockSettings = LockSettings.of(
                100,  // Maximum lock wait time
                200,  // Maximum lock hold time
                TimeUnit.MILLISECONDS
        );

        // 2. Trigger player data persistence
        PlayerDataPersistenceTask.of(
                lockSettings,
                playerService
        ).start();

        // 3. Limit maximum data volume per persistence
        PlayerDataPersistenceTask.of(
                lockSettings,
                playerService,
                500  // Process max 500 items
        ).start();

        // 4. Trigger entity data persistence
        EntityDataPersistenceTask.of(
                lockSettings,
                entityService
        ).start();
    }
}
```

### Data TTL (Time-To-Live) Management

Redis cache data has a default TTL to prevent unlimited memory growth. Here's how to manage TTL for entities and player
data:

```java
public class TTLManagementExample {
    public void manageTTL(LegacyEntityDataService entityService, LegacyPlayerDataService playerService) {
        // 1. Entity data TTL management
        UUID entityId = UUID.randomUUID();

        // Set default TTL for a single entity (30 minutes)
        boolean success = entityService.setEntityDefaultTTL(entityId);

        // Set custom TTL for a single entity
        boolean customSuccess = entityService.setEntityTTL(entityId, Duration.ofHours(2));

        // Set default TTL for all entities without TTL
        int entitiesUpdated = entityService.setDefaultTTLForAllEntities();

        // 2. Player data TTL management
        UUID playerId = UUID.randomUUID();

        // Set default TTL for a single player (1 day)
        boolean playerSuccess = playerService.setPlayerDefaultTTL(playerId);

        // Set custom TTL for a single player
        boolean playerCustomSuccess = playerService.setPlayerTTL(playerId, Duration.ofHours(1));

        // Set default TTL for all players without TTL
        int playersUpdated = playerService.setDefaultTTLForAllPlayers();
    }
}
```

### TTL Utility Integration

```java
public class TTLUtilityExample {
    public void atomicTTLOperations(RedissonClient redissonClient) {
        // 1. Atomic increment with TTL (replaces unsafe get->set operations)
        Long newCount = TTLUtil.incrementWithTTL(redissonClient, "counter:key", 300); // 5 minutes TTL

        // 2. Atomic TTL setting for existing keys
        boolean ttlSet = TTLUtil.setTTLIfExistsAtomic(redissonClient, "existing:key", 600); // 10 minutes

        // 3. Safe TTL setting only if missing
        boolean ttlSetIfMissing = TTLUtil.setTTLIfMissing(redissonClient, "some:key", 900); // 15 minutes

        // 4. Bulk TTL processing with atomic operations
        boolean processed = TTLUtil.processBucketTTL(redissonClient, "bulk:key", 1200); // 20 minutes

        // 5. Reliable TTL setting with fallback
        boolean reliable = TTLUtil.setReliableTTL(redissonClient, "important:key", 1800); // 30 minutes
    }
}
```

### Entity Batch Save Operations

```java
public class BatchEntityOperationsExample {
    public void batchSaveEntities(LegacyEntityDataService entityService) {
        // Create multiple entities
        List<LegacyEntityData> entities = new ArrayList<>();

        // Create first entity
        LegacyEntityData entity1 = LegacyEntityData.of(UUID.randomUUID(), "item");
        entity1.addAttribute("name", "Dragon Blade");
        entity1.addAttribute("rarity", "legendary");
        entities.add(entity1);

        // Create second entity
        LegacyEntityData entity2 = LegacyEntityData.of(UUID.randomUUID(), "item");
        entity2.addAttribute("name", "Shadow Cloak");
        entity2.addAttribute("rarity", "epic");
        entities.add(entity2);

        /* 
          Batch Save Entities: Immediately puts all entities in the list into the L1 cache and schedules a single background task
              to persist them collectively to L2 (Redis) and the database.
            - This is more efficient than calling saveEntity individually for each entity as it batches the persistence operation.
            - The rationale for calling and the consequences of not calling are the same as for the single saveEntity method.
        */
        entityService.saveEntities(entities);
    }
}
```

### N-Directional Relationship Management

```java
public class NDirectionalRelationshipExample {
    public void createComplexRelationships(LegacyEntityDataService entityService) {
        // Create a team
        UUID teamId = UUID.randomUUID();
        LegacyEntityData team = LegacyEntityData.of(teamId, "team");
        team.addAttribute("name", "Elite Squad");

        // Create multiple members
        UUID leaderId = UUID.randomUUID();
        UUID memberId1 = UUID.randomUUID();
        UUID memberId2 = UUID.randomUUID();

        // Create relationship mapping, establish multiple relationships at once
        Map<UUID, Map<String, Set<UUID>>> relationshipMap = new HashMap<>();

        // Team to members relationships
        Map<String, Set<UUID>> teamRelations = new HashMap<>();
        teamRelations.put("leader", Set.of(leaderId));
        teamRelations.put("member", Set.of(memberId1, memberId2));
        relationshipMap.put(teamId, teamRelations);

        // Members to team relationships
        Map<String, Set<UUID>> leaderRelations = new HashMap<>();
        leaderRelations.put("leads", Set.of(teamId));
        relationshipMap.put(leaderId, leaderRelations);

        Map<String, Set<UUID>> member1Relations = new HashMap<>();
        member1Relations.put("belongs_to", Set.of(teamId));
        relationshipMap.put(memberId1, member1Relations);

        Map<String, Set<UUID>> member2Relations = new HashMap<>();
        member2Relations.put("belongs_to", Set.of(teamId));
        relationshipMap.put(memberId2, member2Relations);

        // Create N-directional relationship network in one operation
        entityService.createNDirectionalRelationships(relationshipMap);
    }
}
```

### Bidirectional Relationship Creation

```java
public class BidirectionalRelationshipExample {
    public void createBidirectionalRelationships(LegacyEntityDataService entityService) {
        // Create two entities
        UUID entity1Id = UUID.randomUUID();
        UUID entity2Id = UUID.randomUUID();

        // Create bidirectional relationship (e.g., friend relationship)
        boolean success = entityService.createBidirectionalRelationship(
                entity1Id,          // First entity ID
                entity2Id,          // Second entity ID 
                "friend_with",      // Relationship from first to second entity
                "friend_with"       // Relationship from second to first entity (can be same or different)
        );

        // Another example: Create parent-child relationship
        UUID parentId = UUID.randomUUID();
        UUID childId = UUID.randomUUID();

        boolean familySuccess = entityService.createBidirectionalRelationship(
                parentId,          // Parent entity ID
                childId,           // Child entity ID
                "child",           // Parent->Child: this is my child
                "parent"           // Child->Parent: this is my parent
        );
    }
}
```

### Complex Relationship Queries

```java
public class ComplexRelationshipQueryExample {
    public void performComplexQueries(LegacyEntityDataService entityService) {
        // Create query criteria
        List<RelationshipCriteria> criteria = new ArrayList<>();

        // Create first criterion: entity is a team member
        RelationshipCriteria memberCriterion = RelationshipCriteria.of("member", UUID.fromString("team-uuid-here"));

        // Create second criterion: entity is not an admin
        RelationshipCriteria notAdminCriterion = RelationshipCriteria.ofNegated("admin", UUID.fromString("team-uuid-here"));

        // Add criteria to list
        criteria.add(memberCriterion);
        criteria.add(notAdminCriterion);

        // Execute AND query: find entities that are team members but not admins
        List<LegacyEntityData> regularMembers = entityService.findEntitiesByMultipleRelationships(
                criteria,
                RelationshipQueryType.AND
        );

        // Execute OR query: find entities that are either team members or admins
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

### Relationship Transaction Management

Using `RelationshipTransaction` allows executing multiple relationship changes in a single atomic operation:

```java
public class RelationshipTransactionExample {
    public void executeTransactions(LegacyEntityDataService entityService) {
        // Execute relationship transaction
        entityService.executeRelationshipTransaction(transaction -> {
            // Team and member IDs
            UUID teamId = UUID.fromString("team-uuid-here");
            UUID memberId = UUID.fromString("member-uuid-here");
            UUID oldTeamId = UUID.fromString("old-team-uuid-here");

            // Execute multiple relationship operations in a single transaction
            transaction
                    // Remove member from old team
                    .removeRelationship(oldTeamId, "member", memberId)
                    // Add member to new team
                    .addRelationship(teamId, "member", memberId)
                    // Update member's team affiliation
                    .removeRelationship(memberId, "belongs_to", oldTeamId)
                    .addRelationship(memberId, "belongs_to", teamId);

        }); // Automatically schedules persistence on completion
    }
}
```

### Data Saving and Persistence Strategy

When dealing with player data and entity data, the right saving strategy is crucial for system performance:

```java
public class PersistenceStrategyExample {
    public void demonstratePersistenceStrategies(
            LegacyPlayerDataService playerService,
            LegacyEntityDataService entityService) {

        // 1. Player data saving
        UUID playerId = UUID.randomUUID();
        LegacyPlayerData playerData = playerService.getLegacyPlayerData(playerId);
        playerData.addData("lastAction", String.valueOf(System.currentTimeMillis()));

        // Calling this method will:
        // - Save data to L2 cache with TTL (1 day)
        // - Schedule database persistence task
        playerService.saveLegacyPlayerData(playerData);

        // 2. Entity data saving
        UUID entityId = UUID.randomUUID();
        LegacyEntityData entityData = entityService.getEntityData(entityId);
        entityData.addAttribute("lastModified", String.valueOf(System.currentTimeMillis()));

        /* 
          Effect of calling the saveEntity method:
            1. Immediate L1 Cache Update: Puts the entity data into the current service's memory cache, making the latest state
               immediately available for subsequent getEntityData calls within this service.
            2. Schedules Background Persistence: Starts an asynchronous task responsible for writing the data to L2 (Redis)
               and eventually saving it to the database (MongoDB).
             
          Significance: Ensures the eventual persistence of data, preventing data loss due to service shutdown or cache eviction,
               and makes the data visible to other services (via L2 or DB).
            - If saveEntity is not called: Modifications to entity attributes or relationships remain only in the L1 cache and
               will not be saved to Redis or the database.
            - Note: This method does not guarantee when persistence will complete, only that the task has been scheduled.
        */
        entityService.saveEntity(entityData);
    }
}
```