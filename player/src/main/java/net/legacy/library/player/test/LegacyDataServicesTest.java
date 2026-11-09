package net.legacy.library.player.test;

import dev.morphia.query.filters.Filters;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.PlayerLauncher;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.LegacyDataServiceSettings;
import net.legacy.library.player.service.LegacyDataServices;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;

import java.time.Duration;
import java.util.List;
import java.util.UUID;

/**
 * Integration test class for {@link LegacyDataServices}, validating the start, the cleanup of a failed start, and a
 * shutdown that persists L1 in order.
 *
 * <p>Requires Redis (localhost:6379) and MongoDB (localhost:27017), like the other player module tests.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "legacy-data-services-test",
        description = "Tests LegacyDataServices start, failed-start cleanup and ordered shutdown",
        tags = {"player", "service", "lifecycle", "shutdown", "integration"},
        priority = 2,
        timeout = 120000,
        isolated = true,
        expectedResult = "SUCCESS"
)
public class LegacyDataServicesTest {

    private static final String MODULE_NAME = "player";

    /**
     * Tests that both services start under the expected names, that data changed only in memory reaches MongoDB on
     * shutdown, and that shutdown removes the services and runs once.
     */
    public static boolean testServiceLifecycleShutdownPersistsMemory() {
        try {
            String name = "test-lifecycle-" + System.nanoTime();
            LegacyDataServices services = LegacyDataServices.start(LegacyDataServiceSettings.builder()
                    .name(name)
                    .mongoDBConnectionConfig(TestConnectionResource.getMongoConfig())
                    .redisConfig(TestConnectionResource.getRedisConfig())
                    .autoSaveInterval(Duration.ofHours(1))
                    .build());

            boolean started = LegacyPlayerDataService.getLegacyPlayerDataService(name + "-player-data-service").isPresent()
                    && LegacyEntityDataService.getLegacyEntityDataService(name + "-entity-data-service").isPresent();

            UUID playerUuid = UUID.randomUUID();
            services.getPlayerDataService().getLegacyPlayerData(playerUuid).addData("lifecycle", "player");
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "lifecycle");
            services.getEntityDataService().saveEntity(entity);
            // Changed in memory only, after the save: only the shutdown's final persistence can write it
            entity.addAttribute("lifecycle", "entity");

            services.shutdown();
            services.shutdown();

            MongoDBConnectionConfig mongo = TestConnectionResource.getMongoConfig();
            LegacyPlayerData storedPlayer = mongo.getDatastore().find(LegacyPlayerData.class)
                    .filter(Filters.eq("_id", playerUuid)).first();
            LegacyEntityData storedEntity = mongo.getDatastore().find(LegacyEntityData.class)
                    .filter(Filters.eq("_id", entityUuid)).first();

            boolean playerPersisted = storedPlayer != null && "player".equals(storedPlayer.getData("lifecycle"));
            boolean entityPersisted = storedEntity != null && "entity".equals(storedEntity.getAttribute("lifecycle"));
            boolean persisted = playerPersisted && entityPersisted;
            boolean removed = LegacyPlayerDataService.getLegacyPlayerDataService(name + "-player-data-service").isEmpty()
                    && LegacyEntityDataService.getLegacyEntityDataService(name + "-entity-data-service").isEmpty();

            TestLogger.logValidation(MODULE_NAME, "DataServicesLifecycle", started && persisted && removed,
                    "started: %s, player persisted: %s, entity persisted: %s, removed: %s",
                    started, playerPersisted, entityPersisted, removed);
            return started && persisted && removed && services.isShutdown();
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Data services lifecycle test failed: %s", exception.getMessage());
            return false;
        }
    }

    /**
     * Tests that a start failing at the entity service shuts the already started player service down again.
     */
    public static boolean testServiceFailedStartCleansUp() {
        String name = "test-failed-start-" + System.nanoTime();
        LegacyEntityDataService blocker = null;
        try {
            // An entity service of the same name makes the second half of the start fail
            blocker = LegacyEntityDataService.of(name + "-entity-data-service",
                    TestConnectionResource.getMongoConfig(), TestConnectionResource.getRedisConfig(),
                    List.of("net.legacy.library.player"),
                    List.of(PlayerLauncher.class.getClassLoader()));

            boolean failed;
            try {
                LegacyDataServices.start(LegacyDataServiceSettings.builder()
                        .name(name)
                        .mongoDBConnectionConfig(TestConnectionResource.getMongoConfig())
                        .redisConfig(TestConnectionResource.getRedisConfig())
                        .build());
                failed = false;
            } catch (IllegalStateException expected) {
                failed = true;
            }

            boolean cleanedUp = LegacyPlayerDataService.getLegacyPlayerDataService(name + "-player-data-service").isEmpty();
            TestLogger.logValidation(MODULE_NAME, "DataServicesFailedStart", failed && cleanedUp,
                    "failed: %s, player service cleaned up: %s", failed, cleanedUp);
            return failed && cleanedUp;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Data services failed start test failed: %s", exception.getMessage());
            return false;
        } finally {
            if (blocker != null) {
                try {
                    blocker.shutdown();
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                }
            }
        }
    }

    /**
     * Tests that an unreachable MongoDB fails the start before any service exists, and that settings without a name
     * are refused.
     */
    public static boolean testServiceUnreachableMongoFailsStart() {
        String name = "test-unreachable-" + System.nanoTime();
        try {
            MongoDBConnectionConfig unreachable = new MongoDBConnectionConfig("legacy_lands_test_unreachable",
                    "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=500&connectTimeoutMS=500");

            boolean refused;
            try {
                LegacyDataServices.start(LegacyDataServiceSettings.builder()
                        .name(name)
                        .mongoDBConnectionConfig(unreachable)
                        .redisConfig(TestConnectionResource.getRedisConfig())
                        .closeMongoDBConnection(true)
                        .build());
                refused = false;
            } catch (IllegalStateException expected) {
                refused = true;
            }

            boolean blankRefused;
            try {
                LegacyDataServices.start(LegacyDataServiceSettings.builder()
                        .name(" ")
                        .mongoDBConnectionConfig(TestConnectionResource.getMongoConfig())
                        .redisConfig(TestConnectionResource.getRedisConfig())
                        .build());
                blankRefused = false;
            } catch (IllegalArgumentException expected) {
                blankRefused = true;
            }

            boolean nothingStarted = LegacyPlayerDataService.getLegacyPlayerDataService(name + "-player-data-service").isEmpty()
                    && LegacyEntityDataService.getLegacyEntityDataService(name + "-entity-data-service").isEmpty();
            TestLogger.logValidation(MODULE_NAME, "DataServicesUnreachable", refused && blankRefused && nothingStarted,
                    "refused: %s, blank refused: %s, nothing started: %s", refused, blankRefused, nothingStarted);
            return refused && blankRefused && nothingStarted;
        } catch (Exception exception) {
            TestLogger.logFailure(MODULE_NAME, "Data services unreachable test failed: %s", exception.getMessage());
            return false;
        }
    }

}
