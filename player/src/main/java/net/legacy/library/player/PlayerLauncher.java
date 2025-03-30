package net.legacy.library.player;

import com.github.benmanes.caffeine.cache.Cache;
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
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.model.RelationshipCriteria;
import net.legacy.library.player.model.RelationshipQueryType;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.task.redis.impl.L1ToL2PlayerDataSyncByNameRStreamAccepter;
import net.legacy.library.player.task.redis.impl.PlayerDataUpdateByNameRStreamAccepter;
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

            // Set TTL for entity data in Redis (30 minutes)
            RedissonClient redissonClient = entityDataService.getL2Cache().getResource();
            String testEntityKey = net.legacy.library.player.util.EntityRKeyUtil.getEntityKey(testEntityId, entityDataService);
            String relatedEntityKey = net.legacy.library.player.util.EntityRKeyUtil.getEntityKey(relatedEntityId, entityDataService);

            // Get Redis objects and set expiration time
            RBucket<Object> testEntityBucket = redissonClient.getBucket(testEntityKey);
            RBucket<Object> relatedEntityBucket = redissonClient.getBucket(relatedEntityKey);
            testEntityBucket.expire(Duration.ofMinutes(30));
            relatedEntityBucket.expire(Duration.ofMinutes(30));

            try {
                // Wait 3 seconds for services to initialize
                Thread.sleep(3000);

                // ===== Player data testing =====
                // Get LegacyPlayerDataService
                LegacyPlayerDataService legacyPlayerDataService1 = playerDataService;

                // Create test player data
                UUID fakePlayerUuid = UUID.randomUUID();
                LegacyPlayerData fakePlayerData = new LegacyPlayerData(fakePlayerUuid);
                fakePlayerData.addData("testKey", "testValue");

                // Use saveLegacyPlayerData to save test player data
                Log.info("Saving player data...");
                legacyPlayerDataService1.saveLegacyPlayerData(fakePlayerData);
                Log.info("Player data saved to L1 cache and scheduled for async persistence");

                // Test player data L1 L2 sync through rstream
                RStreamTask rStreamTask1 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ", Duration.ofSeconds(5)
                );
                RStreamTask rStreamTask2 = L1ToL2PlayerDataSyncByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ2", Duration.ofSeconds(5)
                );
                legacyPlayerDataService1.pubRStreamTask(rStreamTask1);
                legacyPlayerDataService1.pubRStreamTask(rStreamTask2);

                // Test player data update through rstream
                Map<String, String> testData = new HashMap<>();
                testData.put("time", String.valueOf(System.currentTimeMillis()));
                RStreamTask rStreamTask3 = PlayerDataUpdateByNameRStreamAccepter.createRStreamTask(
                        "PsycheQwQ", testData, Duration.ofSeconds(5)
                );
                legacyPlayerDataService1.pubRStreamTask(rStreamTask3);

                // The task has just been published, player data has not been updated yet
                LegacyPlayerData playerData = legacyPlayerDataService1.getLegacyPlayerData(fakePlayerUuid);
                Log.info("Initial player data: " + playerData.getData());

                // Delay 1 second
                Thread.sleep(1000);

                // Try again
                Log.info("Player data after 1 second: " + playerData.getData());

                // ===== Entity data testing =====
                // Retrieve entities from cache or database
                LegacyEntityData retrievedEntity = entityDataService.getEntityData(testEntityId);
                LegacyEntityData retrievedRelatedEntity = entityDataService.getEntityData(relatedEntityId);

                if (retrievedEntity != null) {
                    Log.info("====== Retrieved Test Entity ======");
                    Log.info("Entity ID: " + retrievedEntity.getUuid());
                    Log.info("Entity Type: " + retrievedEntity.getEntityType());
                    Log.info("Entity Name: " + retrievedEntity.getAttribute("name"));
                    Log.info("Entity Description: " + retrievedEntity.getAttribute("description"));
                    Log.info("Creation Time: " + retrievedEntity.getAttribute("created"));

                    // Check relationships
                    boolean hasRelationship = retrievedEntity.hasRelationship("parent", relatedEntityId);
                    Log.info("Has parent relationship: " + hasRelationship);

                    // Check all relationships
                    Log.info("Relationship list: " + retrievedEntity.getRelationships());

                    // Check TTL
                    RBucket<Object> bucket = redissonClient.getBucket(testEntityKey);
                    long ttl = bucket.remainTimeToLive();
                    Log.info("Entity TTL remaining time (ms): " + ttl);
                } else {
                    Log.warn("Test entity not found");
                }

                if (retrievedRelatedEntity != null) {
                    Log.info("====== Retrieved Related Entity ======");
                    Log.info("Entity ID: " + retrievedRelatedEntity.getUuid());
                    Log.info("Entity Name: " + retrievedRelatedEntity.getAttribute("name"));

                    // Check relationships
                    boolean hasRelationship = retrievedRelatedEntity.hasRelationship("child", testEntityId);
                    Log.info("Has child relationship: " + hasRelationship);

                    // Check TTL
                    RBucket<Object> bucket = redissonClient.getBucket(relatedEntityKey);
                    long ttl = bucket.remainTimeToLive();
                    Log.info("Related entity TTL remaining time (ms): " + ttl);
                } else {
                    Log.warn("Related entity not found");
                }

                // Test finding entities by type
                List<LegacyEntityData> testTypeEntities = entityDataService.findEntitiesByType("test-entity");
                Log.info("Number of entities with type 'test-entity': " + testTypeEntities.size());

                // Test finding entities by relationship
                List<LegacyEntityData> childEntities = entityDataService.findEntitiesByRelationship("child", testEntityId);
                Log.info("Number of entities with 'child' relationship to test entity: " + childEntities.size());

                // Test finding entities by attribute
                List<LegacyEntityData> entitiesWithName = entityDataService.findEntitiesByAttribute("name", "Test Entity");
                Log.info("Number of entities with name 'Test Entity': " + entitiesWithName.size());

                // ===== Test new features =====
                Log.info("====== Testing N-way Relationships ======");

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
                entityDataService.saveEntities(entitiesToSave, true);
                Log.info("Batch save test: Successfully saved " + entitiesToSave.size() + " entities");

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
                boolean success = entityDataService.createNDirectionalRelationships(relationshipMap, true);
                Log.info("N-directional relationship test: " + (success ? "Successful" : "Failed"));

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

                Log.info("Complex query test: TeamMembers=" + teamMembers.size()
                        + ", Admins=" + admins.size()
                        + ", NonAdmins=" + nonAdmins.size());

                // Test relationship transaction
                UUID member4Id = UUID.randomUUID();
                LegacyEntityData member4 = LegacyEntityData.of(member4Id, "user");
                member4.addAttribute("name", "Member 4");
                member4.addAttribute("role", "moderator");
                entityDataService.saveEntity(member4, true);

                // Execute relationship transaction
                boolean transactionSuccess = entityDataService.executeRelationshipTransaction(transaction -> {
                    transaction.addRelationship(teamId, "moderator", member4Id)
                            .addRelationship(member4Id, "team", teamId)
                            .addRelationship(member4Id, "moderator-of", teamId)
                            .createBidirectionalRelationship(member1Id, "friend", member4Id, "friend");
                }, true);

                // Verify the transaction results
                LegacyEntityData updatedTeam = entityDataService.getEntityData(teamId);
                LegacyEntityData updatedMember4 = entityDataService.getEntityData(member4Id);

                boolean teamHasModerator = updatedTeam.hasRelationship("moderator", member4Id);
                boolean member4HasTeam = updatedMember4.hasRelationship("team", teamId);
                boolean member4IsModerator = updatedMember4.hasRelationship("moderator-of", teamId);
                boolean allRelationsCreated = teamHasModerator && member4HasTeam && member4IsModerator;

                Log.info("Relationship transaction test: " + (transactionSuccess && allRelationsCreated ? "Successful" : "Failed"));

                // Test saveEntity with schedulePersistence parameter
                member4.addAttribute("status", "active");
                entityDataService.saveEntity(member4, false);
                entityDataService.saveEntity(member4, true);
                Log.info("Persistence parameter test: Entity saved to L1 cache and scheduled for persistence");

                // Give some time for persistence
                Thread.sleep(1000);

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
                Log.error("Failed to shutdown LegacyPlayerDataService: " + value.getName(), exception);
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
                Log.error("Failed to shutdown LegacyEntityDataService: " + value.getName(), exception);
            }
        });
    }
}
