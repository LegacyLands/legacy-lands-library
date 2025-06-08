package net.legacy.library.player;

import com.github.benmanes.caffeine.cache.Cache;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.FairyLaunch;
import io.fairyproject.container.Autowired;
import io.fairyproject.container.InjectableComponent;
import io.fairyproject.log.Log;
import io.fairyproject.plugin.Plugin;
import net.legacy.library.annotation.service.AnnotationProcessingServiceInterface;
import net.legacy.library.cache.service.CacheServiceInterface;
import net.legacy.library.configuration.ConfigurationLauncher;
import net.legacy.library.mongodb.factory.MongoDBConnectionConfigFactory;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.index.LegacyIndexManager;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.model.RelationshipCriteria;
import net.legacy.library.player.model.RelationshipQueryType;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.task.redis.impl.L1ToL2PlayerDataSyncByNameRStreamAccepter;
import net.legacy.library.player.task.redis.impl.PlayerDataUpdateByNameRStreamAccepter;
import net.legacy.library.player.util.EntityRKeyUtil;
import net.legacy.library.player.util.RKeyUtil;
import org.bson.UuidRepresentation;
import org.redisson.api.RBucket;
import org.redisson.api.RedissonClient;
import org.redisson.config.Config;

import java.time.Duration;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.TimeUnit;

/**
 * The type Player launcher.
 *
 * @author qwq-dev
 * @since 2025-1-3 14:12
 */
@FairyLaunch
@InjectableComponent
public class PlayerLauncher extends Plugin {
    // DEBUG
    public static final boolean DEBUG = false;

    @Autowired
    @SuppressWarnings("unused")
    private AnnotationProcessingServiceInterface annotationProcessingService;

    @Override
    public void onPluginEnable() {
        List<String> basePackages = List.of(
                "net.legacy.library.player",
                "net.legacy.library.configuration.serialize.annotation"
        );

        annotationProcessingService.processAnnotations(
                basePackages, false,
                this.getClassLoader(), ConfigurationLauncher.class.getClassLoader()
        );

        // DEBUG
        if (DEBUG) {
            MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                    "example", "mongodb://localhost:27017/", UuidRepresentation.STANDARD
            );

            Config config = new Config();
            config.useSingleServer().setAddress("redis://127.0.0.1:6379");

            List<ClassLoader> classLoader = List.of(
                    PlayerLauncher.class.getClassLoader()
            );

            // Create PlayerDataService
            LegacyPlayerDataService playerDataService = LegacyPlayerDataService.of(
                    "player-data-service", mongoConfig, config,
                    Duration.ofMinutes(1), basePackages, classLoader, Duration.ofSeconds(1)
            );

            // Create EntityDataService
            LegacyEntityDataService entityDataService = LegacyEntityDataService.of(
                    "entity-data-service", mongoConfig, config,
                    basePackages, classLoader
            );

            // Test Index Management
            try {
                Log.info("----------");
                Log.info("Test: Database Index Management");
                LegacyIndexManager indexManager = LegacyIndexManager.of(mongoConfig);

                // Ensure common indexes
                indexManager.ensureEntityTypeIndex(); // For LegacyEntityData
                indexManager.ensureAttributeIndex("name", false); // For LegacyEntityData
                indexManager.ensureRelationshipIndex("member"); // For LegacyEntityData
                indexManager.ensureRelationshipIndex("team"); // For LegacyEntityData
                indexManager.ensurePlayerDataIndex("lastLogin", true); // For LegacyPlayerData

                Log.info("Index creation process completed");
                Log.info("----------");
            } catch (Exception exception) {
                Log.error("Error during index management test", exception);
                Log.info("----------");
            }

            // Create test entities
            UUID testEntityId = UUID.randomUUID();
            UUID relatedEntityId = UUID.randomUUID();

            // First test entity
            LegacyEntityData testEntity = LegacyEntityData.of(testEntityId, "test-entity");
            testEntity.addAttribute("name", "Test Entity");
            testEntity.addAttribute("description", "This is a test entity");
            testEntity.addAttribute("created", String.valueOf(System.currentTimeMillis()));

            // Second test entity (related entity)
            LegacyEntityData relatedEntity = LegacyEntityData.of(relatedEntityId, "related-entity");
            relatedEntity.addAttribute("name", "Related Entity");
            relatedEntity.addAttribute("description", "This is an entity related to the test entity");

            // Establish relationships
            testEntity.addRelationship("parent", relatedEntityId);
            relatedEntity.addRelationship("child", testEntityId);

            // Save entities
            entityDataService.saveEntity(testEntity);
            entityDataService.saveEntity(relatedEntity);

            // Give sufficient time for L1 to L2 persistence
            try {
                Thread.sleep(5000); // Increased persistence delay time
            } catch (InterruptedException exception) {
                Log.warn("Sleep interrupted during persistence delay", exception);
                Thread.currentThread().interrupt();
            }

            // Set TTL for entity data in Redis (30 minutes)
            RedissonClient redissonClient = entityDataService.getL2Cache().getResource();
            String testEntityKey = net.legacy.library.player.util.EntityRKeyUtil.getEntityKey(testEntityId, entityDataService);
            String relatedEntityKey = net.legacy.library.player.util.EntityRKeyUtil.getEntityKey(relatedEntityId, entityDataService);

            // Get Redis objects and set expiration time
            RBucket<Object> testEntityBucket = redissonClient.getBucket(testEntityKey);
            RBucket<Object> relatedEntityBucket = redissonClient.getBucket(relatedEntityKey);

            // Using set with TTL is more reliable than expire
            Object testEntityValue = testEntityBucket.get();
            Object relatedEntityValue = relatedEntityBucket.get();

            if (testEntityValue != null) {
                testEntityBucket.set(testEntityValue, Duration.ofMinutes(30).toMillis(), TimeUnit.MILLISECONDS);
            } else {
                // Fallback to expire method if value cannot be retrieved
                testEntityBucket.expire(Duration.ofMinutes(30));
            }

            if (relatedEntityValue != null) {
                relatedEntityBucket.set(relatedEntityValue, Duration.ofMinutes(30).toMillis(), TimeUnit.MILLISECONDS);
            } else {
                // Fallback to expire method if value cannot be retrieved
                relatedEntityBucket.expire(Duration.ofMinutes(30));
            }

            try {
                // Wait 3 seconds for services to initialize
                Thread.sleep(3000);

                // ===== Player data testing =====
                Log.info("----------");
                Log.info("Test: Player Data Testing");

                // Create test player data
                UUID fakePlayerUuid = UUID.randomUUID();
                LegacyPlayerData fakePlayerData = new LegacyPlayerData(fakePlayerUuid);
                fakePlayerData.addData("testKey", "testValue");

                // Use saveLegacyPlayerData to save test player data
                Log.info("Saving player data...");
                playerDataService.saveLegacyPlayerData(fakePlayerData);
                Log.info("Player data saved to L1 cache and scheduled for async persistence");

                // Test player data L1 L2 sync through rstream
                RStreamTask rStreamTask1 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ", Duration.ofSeconds(5)
                );
                RStreamTask rStreamTask2 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ2", Duration.ofSeconds(5)
                );
                playerDataService.pubRStreamTask(rStreamTask1);
                playerDataService.pubRStreamTask(rStreamTask2);

                // Test player data update through rstream
                Map<String, String> testData = new HashMap<>();
                testData.put("time", String.valueOf(System.currentTimeMillis()));
                RStreamTask rStreamTask3 = PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ", testData, Duration.ofSeconds(5)
                );
                playerDataService.pubRStreamTask(rStreamTask3);

                // The task has just been published, player data has not been updated yet
                LegacyPlayerData playerData = playerDataService.getLegacyPlayerData(fakePlayerUuid);
                Log.info("Initial player data: %s", playerData.getData());

                // Delay 1 second
                Thread.sleep(1000);

                // Try again
                Log.info("Player data after 1 second: %s", playerData.getData());
                Log.info("----------");

                // ===== Entity data testing =====
                Log.info("----------");
                Log.info("Test: Entity Data Testing");
                // Retrieve entities from cache or database
                LegacyEntityData retrievedEntity = entityDataService.getEntityData(testEntityId);
                LegacyEntityData retrievedRelatedEntity = entityDataService.getEntityData(relatedEntityId);

                if (retrievedEntity != null) {
                    Log.info("Test entity information:");
                    Log.info("Entity ID: %s", retrievedEntity.getUuid());
                    Log.info("Entity Type: %s", retrievedEntity.getEntityType());
                    Log.info("Entity Name: %s", retrievedEntity.getAttribute("name"));
                    Log.info("Entity Description: %s", retrievedEntity.getAttribute("description"));
                    Log.info("Creation Time: %s", retrievedEntity.getAttribute("created"));

                    // Check relationships
                    boolean hasRelationship = retrievedEntity.hasRelationship("parent", relatedEntityId);
                    Log.info("Has parent relationship: %s", hasRelationship);

                    // Check all relationships
                    Log.info("Relationship list: %s", retrievedEntity.getRelationships());

                    // Check TTL
                    RBucket<Object> bucket = redissonClient.getBucket(testEntityKey);
                    long ttl = bucket.remainTimeToLive();
                    Log.info("Entity TTL remaining time (ms): %s", ttl);
                } else {
                    Log.warn("Test entity not found");
                }

                if (retrievedRelatedEntity != null) {
                    Log.info("Related entity information:");
                    Log.info("Entity ID: %s", retrievedRelatedEntity.getUuid());
                    Log.info("Entity Name: %s", retrievedRelatedEntity.getAttribute("name"));

                    // Check relationships
                    boolean hasRelationship = retrievedRelatedEntity.hasRelationship("child", testEntityId);
                    Log.info("Has child relationship: %s", hasRelationship);

                    // Check TTL
                    RBucket<Object> bucket = redissonClient.getBucket(relatedEntityKey);
                    long ttl = bucket.remainTimeToLive();
                    Log.info("Related entity TTL remaining time (ms): %s", ttl);
                } else {
                    Log.warn("Related entity not found");
                }

                // Test finding entities by type
                List<LegacyEntityData> testTypeEntities = entityDataService.findEntitiesByType("test-entity");
                Log.info("Number of entities with type 'test-entity': %s", testTypeEntities.size());

                // Test finding entities by relationship
                List<LegacyEntityData> childEntities = entityDataService.findEntitiesByRelationship("child", testEntityId);
                Log.info("Number of entities with 'child' relationship to test entity: %s", childEntities.size());

                // Test finding entities by attribute
                List<LegacyEntityData> entitiesWithName = entityDataService.findEntitiesByAttribute("name", "Test Entity");
                Log.info("Number of entities with name 'Test Entity': %s", entitiesWithName.size());
                Log.info("----------");

                // ===== Test new features =====
                Log.info("----------");
                Log.info("Test: N-way Relationships");

                // Create multiple test entities for complex relationship testing
                UUID teamId = UUID.randomUUID();
                UUID member1Id = UUID.randomUUID();
                UUID member2Id = UUID.randomUUID();
                UUID member3Id = UUID.randomUUID();

                // Create team entity
                LegacyEntityData teamEntity = LegacyEntityData.of(teamId, "team");
                teamEntity.addAttribute("name", "Test Team");

                // Create member entities
                LegacyEntityData member1 = LegacyEntityData.of(member1Id, "user");
                member1.addAttribute("name", "Member 1");
                member1.addAttribute("role", "admin");

                LegacyEntityData member2 = LegacyEntityData.of(member2Id, "user");
                member2.addAttribute("name", "Member 2");
                member2.addAttribute("role", "member");

                LegacyEntityData member3 = LegacyEntityData.of(member3Id, "user");
                member3.addAttribute("name", "Member 3");
                member3.addAttribute("role", "guest");

                // Test batch saving with saveEntities method
                List<LegacyEntityData> entitiesToSave = List.of(teamEntity, member1, member2, member3);
                entityDataService.saveEntities(entitiesToSave);
                Log.info("Batch save test: Successfully saved %s entities", entitiesToSave.size());

                // Test N-directional relationships
                Map<UUID, Map<String, Set<UUID>>> relationshipMap = new HashMap<>();

                // Team has members
                Map<String, Set<UUID>> teamRelationships = new HashMap<>();
                teamRelationships.put("member", new HashSet<>(List.of(member1Id, member2Id, member3Id)));
                relationshipMap.put(teamId, teamRelationships);

                // Members belong to team
                Map<String, Set<UUID>> member1Relationships = new HashMap<>();
                member1Relationships.put("team", new HashSet<>(List.of(teamId)));
                member1Relationships.put("admin-of", new HashSet<>(List.of(teamId)));
                relationshipMap.put(member1Id, member1Relationships);

                Map<String, Set<UUID>> member2Relationships = new HashMap<>();
                member2Relationships.put("team", new HashSet<>(List.of(teamId)));
                relationshipMap.put(member2Id, member2Relationships);

                Map<String, Set<UUID>> member3Relationships = new HashMap<>();
                member3Relationships.put("team", new HashSet<>(List.of(teamId)));
                relationshipMap.put(member3Id, member3Relationships);

                // Create all relationships at once
                boolean success = entityDataService.createNDirectionalRelationships(relationshipMap);
                Log.info("N-directional relationship test: %s", (success ? "Successful" : "Failed"));

                // Test multiple relationship criteria queries
                // Find all members of the team
                List<RelationshipCriteria> teamMemberCriteria = List.of(
                        RelationshipCriteria.has("team", teamId)
                );
                List<LegacyEntityData> teamMembers = entityDataService.findEntitiesByMultipleRelationships(
                        teamMemberCriteria, RelationshipQueryType.AND);

                // Find admins of the team
                List<RelationshipCriteria> adminCriteria = List.of(
                        RelationshipCriteria.has("admin-of", teamId)
                );
                List<LegacyEntityData> admins = entityDataService.findEntitiesByMultipleRelationships(
                        adminCriteria, RelationshipQueryType.AND);

                // Find members who are NOT admins (AND_NOT)
                List<RelationshipCriteria> nonAdminCriteria = List.of(
                        RelationshipCriteria.has("team", teamId),
                        RelationshipCriteria.has("admin-of", teamId)
                );
                List<LegacyEntityData> nonAdmins = entityDataService.findEntitiesByMultipleRelationships(
                        nonAdminCriteria, RelationshipQueryType.AND_NOT);

                Log.info("Complex query test: TeamMembers=%s, Admins=%s, NonAdmins=%s", 
                        teamMembers.size(), admins.size(), nonAdmins.size());
                Log.info("----------");

                // Test relationship transaction
                Log.info("----------");
                Log.info("Test: Relationship Transaction");
                UUID member4Id = UUID.randomUUID();
                LegacyEntityData member4 = LegacyEntityData.of(member4Id, "user");
                member4.addAttribute("name", "Member 4");
                member4.addAttribute("role", "moderator");
                entityDataService.saveEntity(member4);

                // Execute relationship transaction
                boolean transactionSuccess = entityDataService.executeRelationshipTransaction(transaction -> transaction.addRelationship(teamId, "moderator", member4Id)
                        .addRelationship(member4Id, "team", teamId)
                        .addRelationship(member4Id, "moderator-of", teamId)
                        .createBidirectionalRelationship(member1Id, "friend", member4Id, "friend"));

                // Verify the transaction results
                LegacyEntityData updatedTeam = entityDataService.getEntityData(teamId);
                LegacyEntityData updatedMember4 = entityDataService.getEntityData(member4Id);

                boolean teamHasModerator = updatedTeam.hasRelationship("moderator", member4Id);
                boolean member4HasTeam = updatedMember4.hasRelationship("team", teamId);
                boolean member4IsModerator = updatedMember4.hasRelationship("moderator-of", teamId);
                boolean allRelationsCreated = teamHasModerator && member4HasTeam && member4IsModerator;

                Log.info("Relationship transaction test: %s", (transactionSuccess && allRelationsCreated ? "Successful" : "Failed"));
                Log.info("----------");

                // Test saveEntity with schedulePersistence parameter
                Log.info("----------");
                Log.info("Test: Persistence Parameter");
                member4.addAttribute("status", "active");
                entityDataService.saveEntity(member4);
                entityDataService.saveEntity(member4);
                Log.info("Entity saved to L1 cache and scheduled for persistence");
                Log.info("----------");

                // Test entity TTL functionality
                Log.info("----------");
                Log.info("Test: Entity TTL Functionality");
                int fixedEntities = entityDataService.setDefaultTTLForAllEntities();
                Log.info("Set TTL for %s entities that had no expiration", fixedEntities);

                // Test specific entity TTL methods
                UUID ttlTestEntityId = UUID.randomUUID();
                LegacyEntityData ttlTestEntity = LegacyEntityData.of(ttlTestEntityId, "ttl-test-entity");
                ttlTestEntity.addAttribute("name", "TTL Test Entity");
                ttlTestEntity.addAttribute("description", "This entity is used for testing TTL functionality");

                // Save the entity to L1 cache, which will trigger async persistence to L2 cache
                entityDataService.saveEntity(ttlTestEntity);

                // Wait for the async persistence task to complete (increased from 500ms to 5000ms)
                Log.info("Waiting for async persistence to complete...");
                try {
                    Thread.sleep(5000); // 5 seconds should be enough for the async task
                } catch (InterruptedException exception) {
                    Log.warn("Sleep interrupted during persistence delay", exception);
                    Thread.currentThread().interrupt();
                }

                // Get the entity key and verify if entity exists in Redis
                String entityKey = EntityRKeyUtil.getEntityKey(ttlTestEntityId, entityDataService);
                RBucket<Object> entityBucket = redissonClient.getBucket(entityKey);
                boolean entityExists = entityBucket.isExists();
                Log.info("Entity exists in Redis after async persistence: %s", entityExists);

                // Now test setEntityTTL with custom duration (10 minutes)
                boolean ttlSetSuccess = entityDataService.setEntityTTL(ttlTestEntityId, Duration.ofMinutes(10));
                long entityTtl = entityBucket.remainTimeToLive();
                Log.info("setEntityTTL test (10 minutes): Success=%s, TTL=%s ms", ttlSetSuccess, entityTtl);

                // Test setEntityDefaultTTL method
                boolean defaultTtlSetSuccess = entityDataService.setEntityDefaultTTL(ttlTestEntityId);
                long defaultEntityTtl = entityBucket.remainTimeToLive();
                Log.info("setEntityDefaultTTL test: Success=%s, TTL=%s ms", defaultTtlSetSuccess, defaultEntityTtl);

                // Check if the default TTL is close to the expected value (30 minutes)
                long expectedDefaultTtl = LegacyEntityDataService.DEFAULT_TTL_DURATION.toMillis();
                boolean ttlInRange = Math.abs(defaultEntityTtl - expectedDefaultTtl) < 5000; // 5 second tolerance
                Log.info("Default TTL verification: Expected=%s, Actual=%s, In expected range=%s", expectedDefaultTtl, defaultEntityTtl, ttlInRange);

                // Check the entity key and pattern
                String randomEntityKey = EntityRKeyUtil.getEntityKey(ttlTestEntityId, entityDataService);
                String entityPattern = EntityRKeyUtil.getEntityKeyPattern(entityDataService);
                Log.info("Entity key: %s", randomEntityKey);
                Log.info("Entity pattern: %s", entityPattern);

                // Direct check in Redis
                RedissonClient client = entityDataService.getL2Cache().getResource();
                RBucket<Object> bucket = client.getBucket(randomEntityKey);
                boolean exists = bucket.isExists();
                long initialTtl = bucket.remainTimeToLive();

                Log.info("Entity exists in Redis: %s, initial TTL: %s ms", exists, initialTtl);

                // Try different approaches to set TTL
                if (exists && initialTtl < 0) {
                    // Try direct Redis command
                    boolean expireSuccess = bucket.expire(Duration.ofMinutes(30));
                    long afterDirectTtl = bucket.remainTimeToLive();
                    Log.info("Direct Redis expire result: %s, TTL after: %s ms", expireSuccess, afterDirectTtl);
                }

                // Also check using a Redis SET command with TTL parameter
                Log.info("Trying SET with expiration option");
                String serialized = SimplixSerializer.serialize(ttlTestEntity).toString();
                bucket.set(serialized, 30, TimeUnit.MINUTES);
                long afterSetTtl = bucket.remainTimeToLive();
                Log.info("After explicit SET with TTL, value is: %s ms", afterSetTtl);

                // Try another entity with different approach
                UUID finalTestId = UUID.randomUUID();
                LegacyEntityData finalTestEntity = LegacyEntityData.of(finalTestId, "final-ttl-test");
                finalTestEntity.addAttribute("name", "Final TTL Test");

                Log.info("Checking manual persistence with direct TTL control");

                // Save to L1 cache but don't schedule persistence
                entityDataService.saveEntity(finalTestEntity);

                // Manually write to Redis with TTL
                String finalEntityKey = EntityRKeyUtil.getEntityKey(finalTestId, entityDataService);
                String finalSerialized = SimplixSerializer.serialize(finalTestEntity).toString();

                client.getBucket(finalEntityKey).set(finalSerialized, 30, TimeUnit.MINUTES);
                long finalTtl = client.getBucket(finalEntityKey).remainTimeToLive();

                Log.info("Final entity TTL after direct set: %s ms", finalTtl);
                Log.info("----------");

                // Test LegacyPlayerDataService TTL functionality
                Log.info("----------");
                Log.info("Test: Player Data TTL");
                UUID playerTestId = UUID.randomUUID();
                LegacyPlayerData testPlayerData = new LegacyPlayerData(playerTestId);
                testPlayerData.addData("testKey", "testValue");
                testPlayerData.addData("ttlTest", "true");

                // Save the player data
                playerDataService.saveLegacyPlayerData(testPlayerData);
                Thread.sleep(500); // Give time for persistence

                // Get the Redis key for player data
                String playerKey = RKeyUtil.getRLPDSKey(playerTestId, playerDataService);
                Log.info("Player key: %s", playerKey);

                // Check if the key exists and its TTL
                RBucket<Object> playerBucket = client.getBucket(playerKey);
                boolean playerExists = playerBucket.isExists();
                long playerInitialTtl = playerBucket.remainTimeToLive();
                Log.info("Player data exists: %s, TTL: %s ms", playerExists, playerInitialTtl);

                // Try to set TTL on player data
                if (playerExists) {
                    boolean playerTtlSuccess = playerDataService.setPlayerDefaultTTL(playerTestId);
                    long playerTtlAfter = playerBucket.remainTimeToLive();
                    Log.info("TTL set success: %s, new TTL: %s ms", playerTtlSuccess, playerTtlAfter);

                    // Verify the default TTL is close to expected (1 day)
                    long expectedPlayerDefaultTtl = LegacyPlayerDataService.DEFAULT_TTL_DURATION.toMillis();
                    boolean playerTtlInRange = Math.abs(playerTtlAfter - expectedPlayerDefaultTtl) < 5000; // 5 second tolerance
                    Log.info("Player default TTL verification: Expected=%s, Actual=%s, In expected range=%s", expectedPlayerDefaultTtl, playerTtlAfter, playerTtlInRange);

                    // Try manual TTL setting with a different duration (15 minutes)
                    playerTtlSuccess = playerDataService.setPlayerTTL(playerTestId, Duration.ofMinutes(15));
                    playerTtlAfter = playerBucket.remainTimeToLive();
                    Log.info("Manual TTL set success: %s, final TTL: %s ms", playerTtlSuccess, playerTtlAfter);
                }

                // Test setting TTL for all players without TTL
                int fixedPlayers = playerDataService.setDefaultTTLForAllPlayers();
                Log.info("Set TTL for %s player data that had no expiration", fixedPlayers);
                Log.info("----------");

                // Give some time for persistence
                Thread.sleep(1000);

                // ===== Optimistic Locking Test =====
                Log.info("----------");
                Log.info("Test: Optimistic Locking Mechanism");

                // Create a test entity
                UUID optimisticLockEntityId = UUID.randomUUID();
                LegacyEntityData optimisticLockEntity = LegacyEntityData.of(optimisticLockEntityId, "lock-test");
                optimisticLockEntity.addAttribute("name", "Optimistic Lock Test Entity");
                optimisticLockEntity.addAttribute("counter", "0");

                // Save entity and ensure it's persisted to L2 cache
                entityDataService.saveEntity(optimisticLockEntity);
                Log.info("Created optimistic lock test entity, initial version: %s", optimisticLockEntity.getVersion());
                Thread.sleep(500); // Allow time for persistence

                // Simulate first server reading the entity (from L2 cache/database)
                LegacyEntityData server1Entity = entityDataService.getEntityData(optimisticLockEntityId);
                Log.info("Server 1 read entity, version: %s", server1Entity.getVersion());

                // Simulate second server reading the same entity
                LegacyEntityData server2Entity = new LegacyEntityData(optimisticLockEntityId);
                server2Entity.addAttributes(server1Entity.getAttributes());
                server2Entity.setVersion(server1Entity.getVersion());
                Log.info("Server 2 read entity, version: %s", server2Entity.getVersion());

                // Server 1 modifies the entity
                server1Entity.addAttribute("counter", "1");
                server1Entity.addAttribute("updatedBy", "server1");
                entityDataService.saveEntity(server1Entity);
                Log.info("Server 1 updated entity, new version: %s", server1Entity.getVersion());
                Thread.sleep(500); // Allow time for persistence

                // Server 2 modifies the entity (with an older version)
                server2Entity.addAttribute("counter", "2");
                server2Entity.addAttribute("updatedBy", "server2");
                entityDataService.saveEntity(server2Entity);
                Log.info("Server 2 updated entity with older version: %s", server2Entity.getVersion());
                Thread.sleep(500); // Allow time for persistence

                // Read the entity again to see the merged result
                LegacyEntityData resolvedEntity = entityDataService.getEntityData(optimisticLockEntityId);
                Log.info("Final entity state after conflict resolution:");
                Log.info("Version: %s", resolvedEntity.getVersion());
                Log.info("Counter value: %s", resolvedEntity.getAttribute("counter"));
                Log.info("Last updated by: %s", resolvedEntity.getAttribute("updatedBy"));

                // Now test concurrent updates where the second update has a higher version number
                // (simulating a scenario where server 2 got a newer version from elsewhere)
                UUID optimisticLockEntityId2 = UUID.randomUUID();
                LegacyEntityData optimisticLockEntity2 = LegacyEntityData.of(optimisticLockEntityId2, "lock-test-2");
                optimisticLockEntity2.addAttribute("name", "Optimistic Lock Test Entity 2");
                optimisticLockEntity2.addAttribute("status", "new");
                entityDataService.saveEntity(optimisticLockEntity2);
                Thread.sleep(500);

                // Server 1 reads and updates
                LegacyEntityData server1Entity2 = entityDataService.getEntityData(optimisticLockEntityId2);
                server1Entity2.addAttribute("status", "processing");
                server1Entity2.addAttribute("processor", "server1");

                // Server 2 reads, increases version manually, and updates with higher version
                LegacyEntityData server2Entity2 = new LegacyEntityData(optimisticLockEntityId2);
                server2Entity2.addAttributes(optimisticLockEntity2.getAttributes());
                server2Entity2.setVersion(optimisticLockEntity2.getVersion() + 10); // Simulate much higher version
                server2Entity2.addAttribute("status", "completed");
                server2Entity2.addAttribute("processor", "server2");
                entityDataService.saveEntity(server2Entity2);
                Log.info("Server 2 updated entity2 with higher version: %s", server2Entity2.getVersion());
                Thread.sleep(500);

                // Now server 1 tries to save its update (should be handled as an older version)
                entityDataService.saveEntity(server1Entity2);
                Log.info("Server 1 tries to update entity2 with lower version: %s", server1Entity2.getVersion());
                Thread.sleep(500);

                // Check final state
                LegacyEntityData resolvedEntity2 = entityDataService.getEntityData(optimisticLockEntityId2);
                Log.info("Final entity2 state after conflict resolution:");
                Log.info("Version: %s", resolvedEntity2.getVersion());
                Log.info("Status: %s", resolvedEntity2.getAttribute("status"));
                Log.info("Processor: %s", resolvedEntity2.getAttribute("processor"));

                // Test merging behavior when entity attributes are removed
                UUID optimisticLockEntityId3 = UUID.randomUUID();
                LegacyEntityData optimisticLockEntity3 = LegacyEntityData.of(optimisticLockEntityId3, "lock-test-3");
                optimisticLockEntity3.addAttribute("field1", "value1");
                optimisticLockEntity3.addAttribute("field2", "value2");
                optimisticLockEntity3.addAttribute("field3", "value3");
                entityDataService.saveEntity(optimisticLockEntity3);
                Thread.sleep(500);

                // Server 1 reads and removes field2, adds field4
                LegacyEntityData server1Entity3 = entityDataService.getEntityData(optimisticLockEntityId3);
                server1Entity3.removeAttribute("field2");
                server1Entity3.addAttribute("field4", "value4");
                // Simulate higher version for server 1
                server1Entity3.setVersion(server1Entity3.getVersion() + 5);
                entityDataService.saveEntity(server1Entity3);
                Thread.sleep(500);

                // Server 2 reads original, removes field3, adds field5
                LegacyEntityData server2Entity3 = new LegacyEntityData(optimisticLockEntityId3);
                server2Entity3.addAttributes(optimisticLockEntity3.getAttributes());
                server2Entity3.setVersion(optimisticLockEntity3.getVersion());
                server2Entity3.removeAttribute("field3");
                server2Entity3.addAttribute("field5", "value5");
                entityDataService.saveEntity(server2Entity3);
                Thread.sleep(500);

                // Check final result of merge
                LegacyEntityData resolvedEntity3 = entityDataService.getEntityData(optimisticLockEntityId3);
                Log.info("Final entity3 state after complex merge:");
                Log.info("Version: %s", resolvedEntity3.getVersion());
                Log.info("field1 exists: %s", (resolvedEntity3.getAttribute("field1") != null));
                Log.info("field2 exists: %s", (resolvedEntity3.getAttribute("field2") != null));
                Log.info("field3 exists: %s", (resolvedEntity3.getAttribute("field3") != null));
                Log.info("field4 exists: %s", (resolvedEntity3.getAttribute("field4") != null));
                Log.info("field5 exists: %s", (resolvedEntity3.getAttribute("field5") != null));
                Log.info("----------");

            } catch (InterruptedException exception) {
                Log.warn("Test interrupted", exception);
                Thread.currentThread().interrupt();
            }
        }
    }

    @Override
    public void onPluginDisable() {
        CacheServiceInterface<Cache<String, LegacyPlayerDataService>, LegacyPlayerDataService> legacyPlayerDataServices =
                LegacyPlayerDataService.LEGACY_PLAYER_DATA_SERVICES;
        ConcurrentMap<String, LegacyPlayerDataService> map = legacyPlayerDataServices.getResource().asMap();

        // Shut down all cache services
        map.forEach((key, value) -> {
            try {
                value.shutdown();
            } catch (InterruptedException exception) {
                Log.error("Failed to shutdown LegacyPlayerDataService: %s", value.getName(), exception);
            }
        });

        // Shut down entity data services (unconditionally)
        CacheServiceInterface<Cache<String, LegacyEntityDataService>, LegacyEntityDataService> legacyEntityDataServices =
                LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES;
        ConcurrentMap<String, LegacyEntityDataService> entityMap = legacyEntityDataServices.getResource().asMap();

        entityMap.forEach((key, value) -> {
            try {
                value.shutdown();
            } catch (InterruptedException exception) {
                Log.error("Failed to shutdown LegacyEntityDataService: %s", value.getName(), exception);
            }
        });
    }
}
