package net.legacy.library.player.test;

import com.google.common.reflect.TypeToken;
import com.google.gson.JsonObject;
import de.leonhard.storage.internal.serialize.SimplixSerializer;
import io.fairyproject.log.ILogger;
import io.fairyproject.log.Log;
import net.legacy.library.cache.model.LockSettings;
import net.legacy.library.commons.task.VirtualThreadScheduledFuture;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.mongodb.model.MongoDBConnectionConfig;
import net.legacy.library.player.PlayerLauncher;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.service.EntitySyncSettings;
import net.legacy.library.player.service.LegacyEntityDataService;
import net.legacy.library.player.service.LegacyPlayerDataService;
import net.legacy.library.player.task.CoalescedPersistence;
import net.legacy.library.player.task.EntityDataPersistenceTask;
import net.legacy.library.player.task.L1ToL2EntityDataSyncTask;
import net.legacy.library.player.task.L1ToL2PlayerDataSyncTask;
import net.legacy.library.player.task.PlayerDataPersistenceTask;
import net.legacy.library.player.task.redis.EntityRStreamAccepterInvokeTask;
import net.legacy.library.player.task.redis.EntityRStreamTask;
import net.legacy.library.player.task.redis.RStreamTask;
import net.legacy.library.player.task.redis.impl.EntityDataUpdateRStreamAccepter;
import net.legacy.library.player.task.redis.impl.EntityStateUpdateRStreamAccepter;
import net.legacy.library.player.task.redis.impl.RelationshipUpdateRStreamAccepter;
import net.legacy.library.player.util.EntityRKeyUtil;
import net.legacy.library.player.util.RKeyUtil;
import net.legacy.library.player.util.StreamRetentionUtil;
import org.apache.commons.lang3.tuple.Pair;
import org.apache.commons.lang3.tuple.Triple;
import org.redisson.api.RLock;
import org.redisson.api.RReadWriteLock;
import org.redisson.api.RStream;
import org.redisson.api.RedissonClient;
import org.redisson.api.StreamMessageId;
import org.redisson.api.stream.StreamAddArgs;
import org.redisson.api.stream.StreamReadArgs;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.stream.Collectors;
import java.util.stream.IntStream;

/**
 * Integration test class for entity data synchronization through the Redis stream, validating that updates are
 * published, that a service never applies its own updates back, that every server applies an update from another
 * exactly once, and that concurrent and stale updates settle on one state.
 *
 * <p>Several servers are simulated by several {@link LegacyEntityDataService} instances with the same name, which
 * share the stream, the L2 cache and the database like services on separate servers do. The registry only admits one
 * service per name, so each simulated server is registered after the previous one is removed from it.
 *
 * <p>The tests require active Redis and MongoDB connections as specified in {@link TestConnectionResource}.
 *
 * @author qwq-dev
 * @version 1.0
 * @since 2026-10-06 03:00
 */
@ModuleTest(
        testName = "entity-rstream-synchronization-test",
        description = "Integration tests for entity data synchronization through the Redis stream",
        tags = {"entity", "integration", "redis", "stream"},
        priority = 1,
        timeout = 600000,
        isolated = true,
        expectedResult = "SUCCESS",
        validateLifecycle = true
)
public class EntityRStreamSynchronizationTest {

    private static final String ACTION_NAME = EntityStateUpdateRStreamAccepter.ACTION_NAME;
    private static final Duration ACCEPT_INTERVAL = Duration.ofSeconds(1);
    private static final long SETTLE_MILLIS = 3500;

    /**
     * Test that saving an entity publishes its update to the stream, tagged with the publishing service.
     */
    public static boolean testSaveEntityPublishesToStream() {
        // The stream is never read during this test, so the published message stays in place
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-publish", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);

            boolean published = awaitCondition(() -> countMessages(service, service.getInstanceId().toString()) == 1, 5000);
            // Not "entity-data-update": servers on 1.2.6 handle that action and cannot read this format
            boolean taggedWithEntity = readMessages(service).values().stream()
                    .anyMatch(message -> "entity-state-update".equals(message.get("actionName"))
                            && String.valueOf(message.get("data")).contains(entityUuid.toString())
                            && String.valueOf(message.get("data")).contains("\"stamps\":{\"value\":\""));

            // A direct publication completes without an exception
            service.pubEntityRStreamTask(EntityStateUpdateRStreamAccepter.createRStreamTask(
                    entity, service.getInstanceId(), Duration.ofMinutes(5)
            )).get(5, TimeUnit.SECONDS);
            boolean directPublished = countMessages(service, service.getInstanceId().toString()) == 2;

            boolean success = published && taggedWithEntity && directPublished;

            TestLogger.logValidation("player", "SaveEntityPublishesToStream", success,
                    "Stream Publish - published: " + published + ", taggedWithEntity: " + taggedWithEntity +
                            ", directPublished: " + directPublished);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Save entity publish test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a service does not merge its own published snapshot back over a newer local change.
     */
    public static boolean testOwnMessageDoesNotRollBackNewerChange() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-own-rollback", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);

            String publisher = service.getInstanceId().toString();
            boolean published = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            // A newer local change that is not published; the snapshot in the stream is now older
            service.getEntityData(entityUuid).addAttribute("value", "newer");
            long versionAfterChange = service.getEntityData(entityUuid).getVersion();

            // Let the accept task read the stream several times
            Thread.sleep(SETTLE_MILLIS);

            LegacyEntityData current = service.getEntityData(entityUuid);
            boolean changeKept = "newer".equals(current.getAttribute("value"));
            boolean versionKept = current.getVersion() == versionAfterChange;
            boolean notRepublished = countMessages(service, publisher) == 1;

            boolean success = published && changeKept && versionKept && notRepublished;

            TestLogger.logValidation("player", "OwnMessageDoesNotRollBackNewerChange", success,
                    "Own Message - published: " + published + ", changeKept: " + changeKept +
                            ", versionKept: " + versionKept + ", notRepublished: " + notRepublished);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Own message rollback test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that reading its own unchanged update neither bumps the version nor republishes it.
     */
    public static boolean testOwnMessageIsNotRepublished() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-own-republish", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);

            String publisher = service.getInstanceId().toString();
            boolean published = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);
            long versionAfterSave = service.getEntityData(entityUuid).getVersion();

            Thread.sleep(SETTLE_MILLIS);

            boolean versionKept = service.getEntityData(entityUuid).getVersion() == versionAfterSave;
            boolean notRepublished = countMessages(service, publisher) == 1;

            boolean success = published && versionKept && notRepublished;

            TestLogger.logValidation("player", "OwnMessageIsNotRepublished", success,
                    "Own Message - published: " + published + ", versionKept: " + versionKept +
                            ", notRepublished: " + notRepublished);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Own message republish test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a newer update in the older triple format from another server is applied once, kept in the stream
     * for the other servers, and not published again.
     */
    public static boolean testRemoteUpdateIsAppliedOnce() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-remote-apply", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);

            String publisher = service.getInstanceId().toString();
            boolean published = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            long remoteVersion = service.getEntityData(entityUuid).getVersion() + 1;
            String remotePublisher = UUID.randomUUID().toString();
            addRemoteMessage(service, remotePublisher, EntityDataUpdateRStreamAccepter.createRStreamTask(
                    entityUuid, Map.of("value", "remote"), remoteVersion, Duration.ofMinutes(5)));

            boolean applied = awaitCondition(() -> "remote".equals(service.getEntityData(entityUuid).getAttribute("value")), 5000);
            boolean versionAdopted = service.getEntityData(entityUuid).getVersion() == remoteVersion;

            Thread.sleep(SETTLE_MILLIS);

            boolean keptForOthers = countMessages(service, remotePublisher) == 1;
            boolean notRepublished = countMessages(service, publisher) == 1;
            boolean versionStable = service.getEntityData(entityUuid).getVersion() == remoteVersion;

            boolean success = published && applied && versionAdopted && keptForOthers && notRepublished && versionStable;

            TestLogger.logValidation("player", "RemoteUpdateIsAppliedOnce", success,
                    "Remote Update - published: " + published + ", applied: " + applied +
                            ", versionAdopted: " + versionAdopted + ", keptForOthers: " + keptForOthers +
                            ", notRepublished: " + notRepublished + ", versionStable: " + versionStable);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Remote update test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that an update from another server with the same version and the same values changes nothing.
     */
    public static boolean testRemoteUpdateWithSameStateIsIgnored() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-remote-same", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);

            String publisher = service.getInstanceId().toString();
            boolean published = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            LegacyEntityData local = service.getEntityData(entityUuid);
            long localVersion = local.getVersion();
            long localModified = local.getLastModifiedTime();
            String remotePublisher = UUID.randomUUID().toString();
            addRemoteMessage(service, remotePublisher, EntityDataUpdateRStreamAccepter.createRStreamTask(
                    entityUuid, Map.of("value", "original"), localVersion, Duration.ofMinutes(5)));

            Thread.sleep(SETTLE_MILLIS);

            LegacyEntityData current = service.getEntityData(entityUuid);
            boolean versionKept = current.getVersion() == localVersion;
            boolean timestampKept = current.getLastModifiedTime() == localModified;
            boolean notRepublished = countMessages(service, publisher) == 1;

            boolean success = published && versionKept && timestampKept && notRepublished;

            TestLogger.logValidation("player", "RemoteUpdateWithSameStateIsIgnored", success,
                    "Same State - published: " + published + ", versionKept: " + versionKept +
                            ", timestampKept: " + timestampKept + ", notRepublished: " + notRepublished);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Same state update test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that changes written to an older copy while it is being saved over and over are never lost: every written
     * attribute and relationship reaches the newer version.
     */
    public static boolean testChangesWrittenDuringSavesAreKept() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "concurrent-change-records", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("seed", "1");
            service.saveEntity(entity);

            // The cached entity is far ahead, so the copy stays an older copy however much is written to it
            LegacyEntityData current = service.getEntityData(entityUuid);
            current.setVersion(1_000_000);
            LegacyEntityData olderCopy = LegacyEntityData.of(entityUuid, "TestEntity");
            olderCopy.setVersion(1);

            int writers = 4;
            int writesPerWriter = 250;
            AtomicBoolean writing = new AtomicBoolean(true);
            CompletableFuture<?>[] writes = IntStream.range(0, writers)
                    .mapToObj(writer -> CompletableFuture.runAsync(() -> IntStream.range(0, writesPerWriter).forEach(index -> {
                        olderCopy.addAttribute("key-" + writer + "-" + index, "value");
                        olderCopy.addRelationship("member", UUID.nameUUIDFromBytes(("member-" + writer + "-" + index).getBytes()));
                    })))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture<Void> saves = CompletableFuture.runAsync(() -> {
                while (writing.get()) {
                    service.saveEntity(olderCopy);
                }
            });

            CompletableFuture.allOf(writes).get(30, TimeUnit.SECONDS);
            writing.set(false);
            saves.get(30, TimeUnit.SECONDS);
            service.saveEntity(olderCopy);

            LegacyEntityData saved = service.getEntityData(entityUuid);
            long attributesKept = IntStream.range(0, writers).boxed()
                    .flatMap(writer -> IntStream.range(0, writesPerWriter).mapToObj(index -> "key-" + writer + "-" + index))
                    .filter(key -> "value".equals(saved.getAttribute(key)))
                    .count();
            long relationshipsKept = IntStream.range(0, writers).boxed()
                    .flatMap(writer -> IntStream.range(0, writesPerWriter).mapToObj(index -> "member-" + writer + "-" + index))
                    .filter(member -> saved.hasRelationship("member", UUID.nameUUIDFromBytes(member.getBytes())))
                    .count();
            long expected = (long) writers * writesPerWriter;
            boolean stillOlder = olderCopy.getVersion() < saved.getVersion();

            boolean success = attributesKept == expected && relationshipsKept == expected && stillOlder;

            TestLogger.logValidation("player", "ChangesWrittenDuringSavesAreKept", success,
                    "Concurrent Change Records - attributes: " + attributesKept + "/" + expected +
                            ", relationships: " + relationshipsKept + "/" + expected + ", stillOlder: " + stillOlder);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent change record test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that entity stream messages published without an expiry are trimmed once older than the retention, and
     * fresh ones are kept.
     */
    public static boolean testEntityStreamRetentionTrimsUntimedMessages() {
        LegacyEntityDataService service = LegacyEntityDataService.of(
                "test-entity-stream-retention-" + System.currentTimeMillis(),
                TestConnectionResource.getMongoConfig(), TestConnectionResource.getRedisConfig(),
                Duration.ofMinutes(30), List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()), ACCEPT_INTERVAL,
                LegacyEntityDataService.DEFAULT_TTL_DURATION,
                EntitySyncSettings.builder().streamRetention(Duration.ofSeconds(3)).build());

        try {
            service.pubEntityRStreamTask(EntityRStreamTask.of("probe-untimed", "old", Duration.ZERO)).get(5, TimeUnit.SECONDS);
            boolean untimed = readMessages(service).values().stream().noneMatch(message -> message.containsKey("timeout"));
            Thread.sleep(4500);
            service.pubEntityRStreamTask(EntityRStreamTask.of("probe-untimed", "fresh", Duration.ZERO)).get(5, TimeUnit.SECONDS);

            boolean oldTrimmed = awaitCondition(() -> readMessages(service).values().stream()
                    .noneMatch(message -> "old".equals(message.get("data"))), 5000);
            boolean freshKept = readMessages(service).values().stream().anyMatch(message -> "fresh".equals(message.get("data")));

            boolean success = untimed && oldTrimmed && freshKept;

            TestLogger.logValidation("player", "EntityStreamRetentionTrimsUntimedMessages", success,
                    "Entity Stream Retention - untimed: " + untimed + ", oldTrimmed: " + oldTrimmed + ", freshKept: " + freshKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Entity stream retention test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that expired messages are removed from both streams though every server reads a message only once, and
     * that messages still within their expiry stay.
     */
    public static boolean testExpiredMessagesAreRemoved() {
        LegacyEntityDataService entities = TestConnectionResource.createTestEntityService(
                "stream-expiry", Duration.ofMinutes(30), ACCEPT_INTERVAL);
        LegacyPlayerDataService players = TestConnectionResource.createTestService(
                "stream-expiry", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            entities.pubEntityRStreamTask(EntityRStreamTask.of("probe-expiry", "short", Duration.ofSeconds(2))).get(5, TimeUnit.SECONDS);
            entities.pubEntityRStreamTask(EntityRStreamTask.of("probe-expiry", "long", Duration.ofMinutes(5))).get(5, TimeUnit.SECONDS);
            players.pubRStreamTask(RStreamTask.of("probe-expiry", "short", Duration.ofSeconds(2))).get(5, TimeUnit.SECONDS);
            players.pubRStreamTask(RStreamTask.of("probe-expiry", "long", Duration.ofMinutes(5))).get(5, TimeUnit.SECONDS);
            RStream<Object, Object> playerStream = players.getL2Cache().getResource().getStream(RKeyUtil.getRStreamNameKey(players));

            boolean entityRemoved = awaitCondition(() -> readMessages(entities).values().stream()
                    .noneMatch(message -> "short".equals(message.get("data"))), 8000);
            boolean playerRemoved = awaitCondition(() -> playerStream.read(StreamReadArgs.greaterThan(StreamMessageId.ALL)).values().stream()
                    .noneMatch(message -> "short".equals(message.get("probe-expiry"))), 8000);
            boolean entityKept = readMessages(entities).values().stream().anyMatch(message -> "long".equals(message.get("data")));
            boolean playerKept = playerStream.read(StreamReadArgs.greaterThan(StreamMessageId.ALL)).values().stream()
                    .anyMatch(message -> "long".equals(message.get("probe-expiry")));

            boolean success = entityRemoved && playerRemoved && entityKept && playerKept;

            TestLogger.logValidation("player", "ExpiredMessagesAreRemoved", success,
                    "Expiry - entityRemoved: " + entityRemoved + ", playerRemoved: " + playerRemoved +
                            ", entityKept: " + entityKept + ", playerKept: " + playerKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Expired message test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(entities);
            players.getRedisStreamAcceptTask().cancel(false);
            players.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                players.shutdown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test service %s: %s", players.getName(), exception.getMessage());
            }
        }
    }

    /**
     * Test that player stream messages the accept task never removes itself are trimmed once older than the
     * retention, and fresh ones are kept.
     */
    public static boolean testPlayerStreamRetentionTrimsStuckMessages() {
        LegacyPlayerDataService service = LegacyPlayerDataService.of(
                "test-player-stream-retention-" + System.currentTimeMillis(),
                TestConnectionResource.getMongoConfig(), TestConnectionResource.getRedisConfig(),
                Duration.ofMinutes(30), List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()), ACCEPT_INTERVAL,
                LegacyPlayerDataService.DEFAULT_TTL_DURATION, Duration.ofSeconds(3));

        try {
            // More than one task and its expiry: the accept task rejects such a message and leaves it in place
            RStream<Object, Object> stream = service.getL2Cache().getResource().getStream(RKeyUtil.getRStreamNameKey(service));
            stream.add(StreamAddArgs.entries(Map.of("probe-a", "old", "probe-b", "old", "expiration-time", "0")));
            Thread.sleep(4500);
            stream.add(StreamAddArgs.entries(Map.of("probe-a", "fresh", "probe-b", "fresh", "expiration-time", "0")));

            boolean oldTrimmed = awaitCondition(() -> stream.read(StreamReadArgs.greaterThan(StreamMessageId.ALL)).values().stream()
                    .noneMatch(message -> "old".equals(message.get("probe-a"))), 5000);
            boolean freshKept = stream.read(StreamReadArgs.greaterThan(StreamMessageId.ALL)).values().stream()
                    .anyMatch(message -> "fresh".equals(message.get("probe-a")));

            boolean success = oldTrimmed && freshKept;

            TestLogger.logValidation("player", "PlayerStreamRetentionTrimsStuckMessages", success,
                    "Player Stream Retention - oldTrimmed: " + oldTrimmed + ", freshKept: " + freshKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Player stream retention test failed: " + exception.getMessage());
            return false;
        } finally {
            service.getRedisStreamAcceptTask().cancel(false);
            service.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                service.shutdown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test service %s: %s", service.getName(), exception.getMessage());
            }
        }
    }

    /**
     * Test that an update older than the local state does not undo newer local changes: the attribute it changed
     * that the local side changed again keeps the local value, and its untouched attributes change nothing.
     */
    public static boolean testStaleRemoteUpdateDoesNotUndoNewerChanges() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-remote-stale", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            entity.addAttribute("other", "kept");
            service.saveEntity(entity);

            String publisher = service.getInstanceId().toString();
            boolean published = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            // Another server read the entity at this version and changed "value"; this server changed it again later
            LegacyEntityData local = service.getEntityData(entityUuid);
            LegacyEntityData remote = copyOf(local);
            remote.addAttribute("value", "stale");
            Thread.sleep(20);
            local.addAttribute("value", "newer");
            local.addAttribute("third", "local");
            long localVersion = local.getVersion();

            String remotePublisher = UUID.randomUUID().toString();
            addRemoteMessage(service, remotePublisher, EntityStateUpdateRStreamAccepter.createRStreamTask(
                    remote, UUID.fromString(remotePublisher), Duration.ofMinutes(5)));

            Thread.sleep(SETTLE_MILLIS);

            LegacyEntityData current = service.getEntityData(entityUuid);
            boolean newerKept = "newer".equals(current.getAttribute("value"));
            boolean untouchedKept = "kept".equals(current.getAttribute("other")) && "local".equals(current.getAttribute("third"));
            boolean versionKept = current.getVersion() == localVersion;
            boolean notRepublished = countMessages(service, publisher) == 1;

            boolean success = published && newerKept && untouchedKept && versionKept && notRepublished;

            TestLogger.logValidation("player", "StaleRemoteUpdateDoesNotUndoNewerChanges", success,
                    "Stale Update - published: " + published + ", newerKept: " + newerKept +
                            ", untouchedKept: " + untouchedKept + ", versionKept: " + versionKept +
                            ", notRepublished: " + notRepublished + " " + current.getAttributes());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Stale update test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that saving an older copy of an entity carries only what that copy changed onto the newer state, instead
     * of overwriting the newer values with the copy's stale ones, removals included.
     */
    public static boolean testOlderCopyCarriesOnlyItsOwnChanges() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "older-copy-save", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("counter", "n1");
            entity.addAttribute("obsolete", "yes");
            service.saveEntity(entity);

            // A copy read at this version, as another holder of the entity would have it
            LegacyEntityData current = service.getEntityData(entityUuid);
            LegacyEntityData olderCopy = copyOf(current);

            // The entity moves on, then the copy changes something else and is saved
            current.addAttribute("counter", "n2");
            current.addAttribute("counter", "n3");
            current.addAttribute("level", "5");
            long newerVersion = current.getVersion();
            olderCopy.addAttribute("note", "fromCopy");
            olderCopy.removeAttribute("obsolete");
            boolean copyIsOlder = olderCopy.getVersion() < newerVersion;
            service.saveEntity(olderCopy);

            LegacyEntityData saved = service.getEntityData(entityUuid);
            boolean sameInstance = saved == current;
            boolean newerKept = "n3".equals(saved.getAttribute("counter")) && "5".equals(saved.getAttribute("level"));
            boolean copyChangeApplied = "fromCopy".equals(saved.getAttribute("note"));
            boolean copyRemovalApplied = saved.getAttribute("obsolete") == null;

            boolean success = copyIsOlder && sameInstance && newerKept && copyChangeApplied && copyRemovalApplied;

            TestLogger.logValidation("player", "OlderCopyCarriesOnlyItsOwnChanges", success,
                    "Older Copy - copyIsOlder: " + copyIsOlder + ", sameInstance: " + sameInstance +
                            ", newerKept: " + newerKept + ", copyChangeApplied: " + copyChangeApplied +
                            ", copyRemovalApplied: " + copyRemovalApplied + " " + saved.getAttributes());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Older copy save test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that an older copy saved into a newer version carries only the relationships it added or removed itself,
     * instead of bringing back relationships the newer version removed.
     */
    public static boolean testOlderCopyCarriesOnlyItsOwnRelationshipChanges() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "older-copy-relationships", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            UUID first = UUID.randomUUID();
            UUID second = UUID.randomUUID();
            UUID third = UUID.randomUUID();
            UUID fourth = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addRelationship("member", first);
            entity.addRelationship("member", fourth);
            service.saveEntity(entity);

            // A copy read at this version
            LegacyEntityData current = service.getEntityData(entityUuid);
            LegacyEntityData olderCopy = copyOf(current);

            // The entity moves on: the first member leaves, a second joins; the copy adds a third, drops the fourth
            current.removeRelationship("member", first);
            current.addRelationship("member", second);
            olderCopy.addRelationship("member", third);
            olderCopy.removeRelationship("member", fourth);
            service.saveEntity(olderCopy);

            LegacyEntityData saved = service.getEntityData(entityUuid);
            boolean removalKept = !saved.hasRelationship("member", first);
            boolean newerKept = saved.hasRelationship("member", second);
            boolean copyAdditionApplied = saved.hasRelationship("member", third);
            boolean copyRemovalApplied = !saved.hasRelationship("member", fourth);

            boolean success = removalKept && newerKept && copyAdditionApplied && copyRemovalApplied;

            TestLogger.logValidation("player", "OlderCopyCarriesOnlyItsOwnRelationshipChanges", success,
                    "Older Copy Relationships - removalKept: " + removalKept + ", newerKept: " + newerKept +
                            ", copyAdditionApplied: " + copyAdditionApplied + ", copyRemovalApplied: " + copyRemovalApplied);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Older copy relationship test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a removal made on one server reaches the others, though their copies still hold the attribute.
     */
    public static boolean testRemovalReachesEveryServer() {
        String serviceName = "test-entity-stream-removal-" + System.currentTimeMillis();
        LegacyEntityDataService first = createServer(serviceName);
        LegacyEntityDataService second = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "kept");
            entity.addAttribute("doomed", "yes");
            first.saveEntity(entity);

            second = createServer(serviceName);
            LegacyEntityDataService secondServer = second;
            boolean loaded = awaitCondition(() -> secondServer.getEntityData(entityUuid) != null
                    && "yes".equals(secondServer.getEntityData(entityUuid).getAttribute("doomed")), 5000);

            LegacyEntityData firstCopy = first.getEntityData(entityUuid);
            firstCopy.removeAttribute("doomed");
            first.saveEntity(firstCopy);

            boolean removedThere = awaitCondition(() -> secondServer.getEntityData(entityUuid).getAttribute("doomed") == null, 8000);
            boolean restKept = "kept".equals(second.getEntityData(entityUuid).getAttribute("value"));

            boolean success = loaded && removedThere && restKept;

            TestLogger.logValidation("player", "RemovalReachesEveryServer", success,
                    "Removal - loaded: " + loaded + ", removedThere: " + removedThere + ", restKept: " + restKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Removal test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(first);
            if (second != null) {
                shutdownQuietly(second);
            }
        }
    }

    /**
     * Test that two conflicting updates of the same version arriving together settle the same way: the attributes
     * only one changed are both kept, and the one both changed takes the later change.
     */
    public static boolean testSimultaneousUpdatesSettleTheSameWay() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-simultaneous", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "base");
            service.saveEntity(entity);
            LegacyEntityData base = service.getEntityData(entityUuid);

            // Two servers each made two edits from the base; the later one changed "value" last
            LegacyEntityData earlier = copyOf(base);
            earlier.addAttribute("extra", "onlyOnEarlier");
            earlier.addAttribute("value", "earlier");
            Thread.sleep(20);
            LegacyEntityData later = copyOf(base);
            later.addAttribute("other", "onlyOnLater");
            later.addAttribute("value", "later");

            UUID earlierPublisher = UUID.randomUUID();
            UUID laterPublisher = UUID.randomUUID();
            addRemoteMessage(service, laterPublisher.toString(),
                    EntityStateUpdateRStreamAccepter.createRStreamTask(later, laterPublisher, Duration.ofMinutes(5)));
            addRemoteMessage(service, earlierPublisher.toString(),
                    EntityStateUpdateRStreamAccepter.createRStreamTask(earlier, earlierPublisher, Duration.ofMinutes(5)));

            Map<String, String> expected = Map.of("value", "later", "extra", "onlyOnEarlier", "other", "onlyOnLater");
            boolean settled = awaitCondition(() -> expected.equals(service.getEntityData(entityUuid).getAttributes()), 5000);
            Thread.sleep(SETTLE_MILLIS);
            boolean stillSettled = expected.equals(service.getEntityData(entityUuid).getAttributes());

            // Merging the two in the other order gives the same state
            LegacyEntityData reversed = copyOf(base);
            reversed.mergeChangesFrom(earlier);
            reversed.mergeChangesFrom(later);
            LegacyEntityData forward = copyOf(base);
            forward.mergeChangesFrom(later);
            forward.mergeChangesFrom(earlier);
            boolean orderFree = expected.equals(reversed.getAttributes()) && expected.equals(forward.getAttributes());

            boolean success = settled && stillSettled && orderFree;

            TestLogger.logValidation("player", "SimultaneousUpdatesSettleTheSameWay", success,
                    "Simultaneous Updates - settled: " + settled + ", stillSettled: " + stillSettled +
                            ", orderFree: " + orderFree + " " + service.getEntityData(entityUuid).getAttributes());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Simultaneous update test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that two servers editing the same version at once keep both their changes, settle on the same value
     * where both changed the same attribute, and publish nothing further.
     */
    public static boolean testConcurrentEditsKeepBothSides() {
        String serviceName = "test-entity-stream-converge-" + System.currentTimeMillis();
        LegacyEntityDataService first = createServer(serviceName);
        LegacyEntityDataService second = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            first.saveEntity(entity);

            second = createServer(serviceName);
            LegacyEntityDataService secondServer = second;
            boolean secondLoaded = awaitCondition(() -> secondServer.getEntityData(entityUuid) != null, 5000);

            // Both servers edit the same version: each its own attribute, and both the shared one
            LegacyEntityData firstCopy = first.getEntityData(entityUuid);
            LegacyEntityData secondCopy = second.getEntityData(entityUuid);
            firstCopy.addAttribute("fromFirst", "yes");
            firstCopy.addAttribute("value", "first");
            secondCopy.addAttribute("fromSecond", "yes");
            secondCopy.addAttribute("value", "second");
            first.saveEntity(firstCopy);
            second.saveEntity(secondCopy);

            boolean converged = awaitCondition(() -> first.getEntityData(entityUuid).getAttributes()
                    .equals(secondServer.getEntityData(entityUuid).getAttributes()), 8000);
            int publishedAfterEdits = readMessages(first).size();

            Thread.sleep(SETTLE_MILLIS);

            Map<String, String> firstState = first.getEntityData(entityUuid).getAttributes();
            boolean bothKept = "yes".equals(firstState.get("fromFirst")) && "yes".equals(firstState.get("fromSecond"));
            boolean stillConverged = firstState.equals(second.getEntityData(entityUuid).getAttributes())
                    && ("first".equals(firstState.get("value")) || "second".equals(firstState.get("value")));
            boolean sameVersion = first.getEntityData(entityUuid).getVersion() == second.getEntityData(entityUuid).getVersion();
            boolean noFurtherPublishes = readMessages(first).size() == publishedAfterEdits;

            boolean success = secondLoaded && converged && bothKept && stillConverged && sameVersion && noFurtherPublishes;

            TestLogger.logValidation("player", "ConcurrentEditsKeepBothSides", success,
                    "Concurrent Edits - converged: " + converged + ", bothKept: " + bothKept +
                            ", stillConverged: " + stillConverged + ", sameVersion: " + sameVersion +
                            ", noFurtherPublishes: " + noFurtherPublishes + " " + firstState);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent edit test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(first);
            if (second != null) {
                shutdownQuietly(second);
            }
        }
    }

    /**
     * Test the case that lost a write on real servers: one server writes a run of changes while another, which has
     * not yet read the last of them, edits another attribute. Repeated with varied timing, no write may be lost.
     */
    public static boolean testEditFromOlderStateKeepsNewerWrites() {
        String serviceName = "test-entity-stream-older-base-" + System.currentTimeMillis();
        LegacyEntityDataService writer = createServer(serviceName);
        LegacyEntityDataService editor = null;

        try {
            editor = createServer(serviceName);
            LegacyEntityDataService editorServer = editor;
            int rounds = 12;
            int lost = 0;
            StringBuilder details = new StringBuilder();

            for (int round = 0; round < rounds; round++) {
                UUID entityUuid = UUID.randomUUID();
                LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
                entity.addAttribute("counter", "n0");
                writer.saveEntity(entity);
                awaitCondition(() -> editorServer.getEntityData(entityUuid) != null, 5000);

                // The writer runs twenty edits; the editor edits right after, at a different moment every round
                for (int index = 1; index <= 20; index++) {
                    LegacyEntityData written = writer.getEntityData(entityUuid);
                    written.addAttribute("counter", "n" + index);
                    writer.saveEntity(written);
                    Thread.sleep(5);
                }
                Thread.sleep(round * 40L);
                LegacyEntityData edited = editor.getEntityData(entityUuid);
                edited.addAttribute("edit", "round" + round);
                editor.saveEntity(edited);

                String expected = "n20/round" + round;
                boolean settled = awaitCondition(() -> expected.equals(state(writer, entityUuid)) && expected.equals(state(editorServer, entityUuid)), 8000);
                if (!settled) {
                    lost++;
                    details.append(" round ").append(round).append(": ").append(state(writer, entityUuid)).append(" | ").append(state(editorServer, entityUuid));
                }
            }

            boolean success = lost == 0;

            TestLogger.logValidation("player", "EditFromOlderStateKeepsNewerWrites", success,
                    "Older State Edits - rounds: " + rounds + ", lost: " + lost + details);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Older state edit test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(writer);
            if (editor != null) {
                shutdownQuietly(editor);
            }
        }
    }

    /**
     * Test that a save takes in the newer state another server already persisted to L2, though this server never
     * read that server's updates: its own change is added to that state, not saved over it.
     */
    public static boolean testSaveTakesInNewerStateFromL2() {
        String serviceName = "test-entity-l2-merge-" + System.currentTimeMillis();
        LegacyEntityDataService writer = createServer(serviceName);
        LegacyEntityDataService editor = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("counter", "n0");
            writer.saveEntity(entity);

            // The editor reads the stream only every half hour, so it learns nothing from it during the test
            LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES.getResource().invalidate(serviceName);
            editor = LegacyEntityDataService.of(serviceName, TestConnectionResource.getMongoConfig(),
                    TestConnectionResource.getRedisConfig(), Duration.ofMinutes(30),
                    List.of("net.legacy.library.player.task.redis.impl"), List.of(PlayerLauncher.class.getClassLoader()),
                    Duration.ofMinutes(30));
            LegacyEntityDataService editorServer = editor;
            boolean loaded = awaitCondition(() -> editorServer.getEntityData(entityUuid) != null, 5000);

            // The writer moves on and its state reaches L2
            LegacyEntityData written = writer.getEntityData(entityUuid);
            written.addAttribute("counter", "n1");
            written.addAttribute("counter", "n2");
            writer.saveEntity(written);
            boolean persisted = awaitCondition(() -> writer.getFromL2Cache(entityUuid)
                    .map(cached -> "n2".equals(cached.getAttribute("counter"))).orElse(false), 5000);

            LegacyEntityData edited = editor.getEntityData(entityUuid);
            boolean editorWasBehind = "n0".equals(edited.getAttribute("counter"));
            edited.addAttribute("edit", "yes");
            editor.saveEntity(edited);

            LegacyEntityData editorState = editor.getEntityData(entityUuid);
            boolean newerTakenIn = "n2".equals(editorState.getAttribute("counter"));
            boolean ownKept = "yes".equals(editorState.getAttribute("edit"));

            boolean success = loaded && persisted && editorWasBehind && newerTakenIn && ownKept;

            TestLogger.logValidation("player", "SaveTakesInNewerStateFromL2", success,
                    "L2 Merge - loaded: " + loaded + ", persisted: " + persisted + ", editorWasBehind: " + editorWasBehind +
                            ", newerTakenIn: " + newerTakenIn + ", ownKept: " + ownKept + " " + editorState.getAttributes());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "L2 merge test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(writer);
            if (editor != null) {
                shutdownQuietly(editor);
            }
        }
    }

    /**
     * Test that entities and player data read back from L2 keep thread-safe collections, so changing them while
     * another thread reads them neither fails nor loses changes.
     */
    public static boolean testLoadedDataStaysThreadSafe() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "loaded-thread-safety", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("seed", "1");
            entity.addRelationship("member", UUID.randomUUID());
            LegacyEntityData loaded = copyOf(entity);
            boolean entityConcurrent = loaded.getAttributes() instanceof ConcurrentHashMap
                    && loaded.getAttributeStamps() instanceof ConcurrentHashMap
                    && loaded.getRelationships() instanceof ConcurrentHashMap
                    && loaded.getRelationships().get("member").getClass().getName().contains("Concurrent");

            LegacyPlayerData player = LegacyPlayerData.of(UUID.randomUUID());
            player.addData("seed", "1");
            LegacyPlayerData loadedPlayer = SimplixSerializer.deserialize(SimplixSerializer.serialize(player).toString(), LegacyPlayerData.class);
            boolean playerConcurrent = loadedPlayer.getData() instanceof ConcurrentHashMap;

            // Writers change the loaded entity while a reader keeps copying its attributes
            AtomicBoolean writing = new AtomicBoolean(true);
            CompletableFuture<Void> reader = CompletableFuture.runAsync(() -> {
                while (writing.get()) {
                    new HashMap<>(loaded.getAttributes()).size();
                }
            });
            CompletableFuture<?>[] writers = IntStream.range(0, 4)
                    .mapToObj(writer -> CompletableFuture.runAsync(() -> IntStream.range(0, 2000)
                            .forEach(index -> loaded.addAttribute("key-" + writer + "-" + index, "value"))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(writers).get(30, TimeUnit.SECONDS);
            writing.set(false);
            reader.get(10, TimeUnit.SECONDS);
            boolean allKept = loaded.getAttributes().size() == 8001;

            boolean success = entityConcurrent && playerConcurrent && allKept;

            TestLogger.logValidation("player", "LoadedDataStaysThreadSafe", success,
                    "Loaded Thread Safety - entityConcurrent: " + entityConcurrent + ", playerConcurrent: " + playerConcurrent +
                            ", allKept: " + allKept + " (" + loaded.getAttributes().size() + ")");

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Loaded thread safety test failed: " + exception);
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that an update published by one server reaches every other server, not only the first one to read it.
     */
    public static boolean testBroadcastReachesEveryServer() {
        String serviceName = "test-entity-stream-broadcast-" + System.currentTimeMillis();
        LegacyEntityDataService publisher = createServer(serviceName);
        LegacyEntityDataService second = null;
        LegacyEntityDataService third = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            publisher.saveEntity(entity);

            second = createServer(serviceName);
            third = createServer(serviceName);
            LegacyEntityDataService secondServer = second;
            LegacyEntityDataService thirdServer = third;
            boolean loaded = awaitCondition(() -> secondServer.getEntityData(entityUuid) != null
                    && thirdServer.getEntityData(entityUuid) != null, 5000);

            LegacyEntityData publisherCopy = publisher.getEntityData(entityUuid);
            publisherCopy.addAttribute("value", "broadcast");
            publisher.saveEntity(publisherCopy);
            long publishedVersion = publisherCopy.getVersion();

            boolean secondApplied = awaitCondition(() -> "broadcast".equals(secondServer.getEntityData(entityUuid).getAttribute("value")), 8000);
            boolean thirdApplied = awaitCondition(() -> "broadcast".equals(thirdServer.getEntityData(entityUuid).getAttribute("value")), 8000);
            boolean versionsMatch = second.getEntityData(entityUuid).getVersion() == publishedVersion
                    && third.getEntityData(entityUuid).getVersion() == publishedVersion;
            boolean messageKept = readMessages(publisher).size() == 2;

            boolean success = loaded && secondApplied && thirdApplied && versionsMatch && messageKept;

            TestLogger.logValidation("player", "BroadcastReachesEveryServer", success,
                    "Broadcast - loaded: " + loaded + ", secondApplied: " + secondApplied +
                            ", thirdApplied: " + thirdApplied + ", versionsMatch: " + versionsMatch +
                            ", messageKept: " + messageKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Broadcast test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(publisher);
            if (second != null) {
                shutdownQuietly(second);
            }
            if (third != null) {
                shutdownQuietly(third);
            }
        }
    }

    /**
     * Test that concurrent entity stream publications each produce a message holding only their own entries.
     */
    public static boolean testConcurrentEntityPublishesDoNotMix() {
        // The stream is never read during this test, so every message stays in place
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-concurrent-publish", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            int publications = 50;
            CompletableFuture<?>[] futures = IntStream.range(0, publications)
                    .mapToObj(index -> service.pubEntityRStreamTask(EntityRStreamTask.of(
                            "probe-" + index, "data-" + index, Duration.ofMinutes(5))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            Collection<Map<Object, Object>> messages = readMessages(service).values();
            boolean allPublished = messages.size() == publications;
            boolean entriesMatch = messages.stream().allMatch(message -> {
                String actionName = String.valueOf(message.get("actionName"));
                String index = actionName.substring("probe-".length());
                return ("data-" + index).equals(message.get("data"))
                        && message.keySet().equals(Set.of("actionName", "data", "publisher", "timeout", "expiration-time", "uuid"));
            });
            boolean allDistinct = messages.stream().map(message -> message.get("actionName")).distinct().count() == publications;

            boolean success = allPublished && entriesMatch && allDistinct;

            TestLogger.logValidation("player", "ConcurrentEntityPublishesDoNotMix", success,
                    "Concurrent Entity Publish - allPublished: " + allPublished + " (" + messages.size() + ")" +
                            ", entriesMatch: " + entriesMatch + ", allDistinct: " + allDistinct);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent entity publish test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that concurrent player stream publications each produce a message holding exactly one task and its
     * expiration time, where a message with any other entry would be rejected by the accept task, and leave no
     * temporary map behind in Redis: the message is built in memory and added in one command.
     */
    public static boolean testConcurrentPlayerPublishesAreSelfContained() {
        // The stream is never read during this test, so every message stays in place
        LegacyPlayerDataService service = TestConnectionResource.createTestService(
                "stream-concurrent-publish", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            int publications = 50;
            CompletableFuture<?>[] futures = IntStream.range(0, publications)
                    .mapToObj(index -> service.pubRStreamTask(RStreamTask.of(
                            "probe-" + index, "data-" + index, Duration.ofMinutes(5))))
                    .toArray(CompletableFuture[]::new);
            CompletableFuture.allOf(futures).get(10, TimeUnit.SECONDS);

            RStream<Object, Object> stream = service.getL2Cache().getResource().getStream(RKeyUtil.getRStreamNameKey(service));
            Collection<Map<Object, Object>> messages = stream.read(StreamReadArgs.greaterThan(StreamMessageId.ALL)).values();
            boolean allPublished = messages.size() == publications;
            boolean onePairEach = messages.stream().allMatch(message -> {
                Set<String> taskKeys = message.keySet().stream()
                        .map(String::valueOf)
                        .filter(key -> !"expiration-time".equals(key))
                        .collect(Collectors.toSet());
                if (message.size() != 2 || taskKeys.size() != 1) {
                    return false;
                }
                String actionName = taskKeys.iterator().next();
                return ("data-" + actionName.substring("probe-".length())).equals(message.get(actionName));
            });

            // The temporary maps the publication used to create were named like this
            String temporaryPattern = "legacy:player:" + service.getName() + ":map-cache:*";
            long temporaryMaps = service.getL2Cache().getResource().getKeys().getKeysStreamByPattern(temporaryPattern).count();
            boolean noTemporaryMaps = temporaryMaps == 0;

            boolean success = allPublished && onePairEach && noTemporaryMaps;

            TestLogger.logValidation("player", "ConcurrentPlayerPublishesAreSelfContained", success,
                    "Concurrent Player Publish - allPublished: " + allPublished + " (" + messages.size() + ")" +
                            ", onePairEach: " + onePairEach + ", temporaryMaps: " + temporaryMaps);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Concurrent player publish test failed: " + exception.getMessage());
            return false;
        } finally {
            service.getRedisStreamAcceptTask().cancel(false);
            service.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                service.shutdown();
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test service %s: %s", service.getName(), exception.getMessage());
            }
        }
    }

    /**
     * Test that the tuple adapters return the element types the caller declares, and keep raw tuples untouched.
     */
    @SuppressWarnings("UnstableApiUsage")
    public static boolean testTupleAdaptersKeepDeclaredTypes() {
        try {
            String tripleJson = GsonUtil.getGson().toJson(Triple.of("left", Map.of("key", "value"), 3L));
            Triple<String, Map<String, String>, Long> triple = GsonUtil.getGson().fromJson(tripleJson,
                    new TypeToken<Triple<String, Map<String, String>, Long>>() {
                    }.getType());
            boolean tripleTyped = triple.getRight() instanceof Long
                    && triple.getRight() == 3L
                    && "value".equals(triple.getMiddle().get("key"));

            String pairJson = GsonUtil.getGson().toJson(Pair.of("left", 7L));
            Pair<String, Long> pair = GsonUtil.getGson().fromJson(pairJson,
                    new TypeToken<Pair<String, Long>>() {
                    }.getType());
            boolean pairTyped = pair.getRight() instanceof Long && pair.getRight() == 7L;

            // A raw tuple still reads every element as Object, where Gson turns numbers into Double
            Triple<?, ?, ?> raw = GsonUtil.getGson().fromJson(tripleJson, Triple.class);
            boolean rawUntouched = raw.getRight() instanceof Double;

            boolean success = tripleTyped && pairTyped && rawUntouched;

            TestLogger.logValidation("player", "TupleAdaptersKeepDeclaredTypes", success,
                    "Tuple Adapters - tripleTyped: " + tripleTyped + ", pairTyped: " + pairTyped +
                            ", rawUntouched: " + rawUntouched);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Tuple adapter test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that an update of an entity this server does not hold leaves it unloaded, in either format.
     */
    public static boolean testUpdateOfUnheldEntityDoesNotLoadIt() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-unheld", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "original");
            service.saveEntity(entity);
            boolean inL2 = awaitCondition(() -> service.getFromL2Cache(entityUuid).isPresent(), 5000);

            // Held in L2 only, as on a server that never needed it
            service.getL1Cache().getResource().invalidate(entityUuid);

            LegacyEntityData remote = copyOf(entity);
            remote.addAttribute("value", "remote");
            String remotePublisher = UUID.randomUUID().toString();
            addRemoteMessage(service, remotePublisher, EntityStateUpdateRStreamAccepter.createRStreamTask(
                    remote, UUID.fromString(remotePublisher), Duration.ofMinutes(5)));
            addRemoteMessage(service, remotePublisher, EntityDataUpdateRStreamAccepter.createRStreamTask(
                    entityUuid, Map.of("value", "triple"), remote.getVersion() + 1, Duration.ofMinutes(5)));

            Thread.sleep(SETTLE_MILLIS);

            boolean stillUnheld = service.getFromL1Cache(entityUuid).isEmpty();

            boolean success = inL2 && stillUnheld;

            TestLogger.logValidation("player", "UpdateOfUnheldEntityDoesNotLoadIt", success,
                    "Unheld Entity - inL2: " + inL2 + ", stillUnheld: " + stillUnheld);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Unheld entity test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that an attribute a stamped update carries without its stamp cannot beat a stamped local change, however
     * high the update's version.
     */
    public static boolean testUnstampedKeyOfStampedUpdateLosesToLocalChange() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-unstamped", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "local");
            service.saveEntity(entity);

            // Far ahead in version: an untouched stale value next to one real, stamped change
            long remoteVersion = service.getEntityData(entityUuid).getVersion() + 50;
            String remoteStamp = new LegacyEntityData.Stamp(remoteVersion, System.currentTimeMillis(), "remote").encode();
            LegacyEntityData.Changes changes = new LegacyEntityData.Changes(
                    Map.of("value", "stale", "other", "remote"), Map.of("other", remoteStamp), Set.of(), Map.of(),
                    remoteVersion, System.currentTimeMillis(), 0);
            UUID remotePublisher = UUID.randomUUID();
            addRemoteMessage(service, remotePublisher.toString(), EntityStateUpdateRStreamAccepter.createRStreamTask(
                    entityUuid, changes, remotePublisher, Duration.ofMinutes(5)));

            boolean stampedApplied = awaitCondition(() -> "remote".equals(service.getEntityData(entityUuid).getAttribute("other")), 5000);
            boolean localKept = "local".equals(service.getEntityData(entityUuid).getAttribute("value"));

            boolean success = stampedApplied && localKept;

            TestLogger.logValidation("player", "UnstampedKeyOfStampedUpdateLosesToLocalChange", success,
                    "Unstamped Key - stampedApplied: " + stampedApplied + ", localKept: " + localKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Unstamped key test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that reading the stream records nothing per message: the cursor alone keeps each message from being
     * delivered twice.
     */
    public static boolean testDeliveredMessagesAreNotRecorded() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-not-recorded", Duration.ofMinutes(30), Duration.ofMinutes(30));
        VirtualThreadScheduledFuture reading = null;

        try {
            EntityRStreamAccepterInvokeTask task = EntityRStreamAccepterInvokeTask.of(service,
                    new ArrayList<>(List.of("net.legacy.library.player.task.redis.impl")),
                    new ArrayList<>(List.of(PlayerLauncher.class.getClassLoader())), ACCEPT_INTERVAL);
            reading = task.start();

            String remotePublisher = UUID.randomUUID().toString();
            for (int index = 0; index < 50; index++) {
                addRemoteMessage(service, remotePublisher, EntityDataUpdateRStreamAccepter.createRStreamTask(
                        UUID.randomUUID(), Map.of("value", String.valueOf(index)), 1, Duration.ofMinutes(5)));
            }
            StreamMessageId last = readMessages(service).keySet().stream().reduce((first, second) -> second).orElseThrow();

            boolean allRead = awaitCondition(() -> last.equals(task.getReadCursor()), 8000);
            boolean nothingRecorded = task.getAcceptedId().isEmpty() && task.getRedeliveredId().isEmpty();

            boolean success = allRead && nothingRecorded;

            TestLogger.logValidation("player", "DeliveredMessagesAreNotRecorded", success,
                    "Not Recorded - allRead: " + allRead + ", acceptedId: " + task.getAcceptedId().size() +
                            ", redeliveredId: " + task.getRedeliveredId().size());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Delivered messages test failed: " + exception.getMessage());
            return false;
        } finally {
            if (reading != null) {
                reading.cancel(false);
            }
            shutdownQuietly(service);
        }
    }

    /**
     * Test that the stamps of removals and clearings are forgotten once older than the tombstone retention, and only
     * those: the stamps of what is present stay.
     */
    public static boolean testTombstonesArePrunedAfterRetention() {
        LegacyEntityDataService service = null;

        try {
            UUID target = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            entity.addAttribute("kept", "yes");
            entity.addAttribute("removed", "yes");
            entity.removeAttribute("removed");
            entity.addRelationship("friend", target);
            entity.addRelationship("member", target);
            entity.removeRelationship("member", target);
            entity.addRelationship("guild", UUID.randomUUID());
            entity.clearRelationships("guild");

            int prunedEarly = entity.pruneTombstones(System.currentTimeMillis() - Duration.ofHours(1).toMillis());
            int prunedLate = entity.pruneTombstones(System.currentTimeMillis() + 1000);

            boolean presentKept = entity.getAttributeStamps().containsKey("kept")
                    && entity.getRelationshipStamps().containsKey(LegacyEntityData.relationshipKey("friend", target));
            boolean tombstonesGone = !entity.getAttributeStamps().containsKey("removed")
                    && !entity.getRelationshipStamps().containsKey(LegacyEntityData.relationshipKey("member", target))
                    && !entity.getRelationshipStamps().containsKey(LegacyEntityData.relationshipClearKey("guild"));

            // The service prunes what it caches by its settings, as the scheduled persistence does
            String serviceName = "test-entity-stream-prune-" + System.currentTimeMillis();
            service = createServer(serviceName, EntitySyncSettings.builder().tombstoneRetention(Duration.ZERO).build(),
                    "net.legacy.library.player.task.redis.impl");
            LegacyEntityData cached = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            cached.addAttribute("removed", "yes");
            cached.removeAttribute("removed");
            service.saveEntity(cached);
            Thread.sleep(5);
            int prunedByService = service.pruneTombstones();

            // The removal of "removed" and of "member", the clearing of "guild", and the addition it cleared
            boolean success = prunedEarly == 0 && prunedLate == 4 && presentKept && tombstonesGone && prunedByService >= 1;

            TestLogger.logValidation("player", "TombstonesArePrunedAfterRetention", success,
                    "Tombstones - prunedEarly: " + prunedEarly + ", prunedLate: " + prunedLate +
                            ", presentKept: " + presentKept + ", tombstonesGone: " + tombstonesGone +
                            ", prunedByService: " + prunedByService);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Tombstone pruning test failed: " + exception.getMessage());
            return false;
        } finally {
            if (service != null) {
                shutdownQuietly(service);
            }
        }
    }

    /**
     * Test that a restarted server does not replay the relationship commands the stream still holds, which would
     * bring back a relationship removed since.
     */
    public static boolean testRestartDoesNotReplayRelationshipCommands() {
        String serviceName = "test-entity-stream-restart-" + System.currentTimeMillis();
        LegacyEntityDataService first = createServer(serviceName);
        LegacyEntityDataService second = null;
        boolean firstStopped = false;

        try {
            UUID entityUuid = UUID.randomUUID();
            UUID friend = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "base");
            first.saveEntity(entity);
            boolean loaded = awaitCondition(() -> first.getFromL2Cache(entityUuid).isPresent(), 5000);

            addRemoteMessage(first, UUID.randomUUID().toString(), RelationshipUpdateRStreamAccepter.createRStreamTask(
                    entityUuid, friend, "friend", false, Duration.ofMinutes(5)));
            boolean added = awaitCondition(() -> first.getEntityData(entityUuid).hasRelationship("friend", friend), 5000);

            // Removed since, and persisted
            LegacyEntityData cached = first.getEntityData(entityUuid);
            cached.removeRelationship("friend", friend);
            first.saveEntity(cached);
            boolean removedInL2 = awaitCondition(() -> first.getFromL2Cache(entityUuid)
                    .map(stored -> !stored.hasRelationship("friend", friend)).orElse(false), 5000);
            shutdownQuietly(first);
            firstStopped = true;

            second = createServer(serviceName);
            LegacyEntityData restarted = second.getEntityData(entityUuid);
            Thread.sleep(SETTLE_MILLIS);

            boolean commandStillThere = readMessages(second).values().stream()
                    .anyMatch(message -> "relationship-update".equals(message.get("actionName")));
            boolean stillRemoved = !restarted.hasRelationship("friend", friend)
                    && !second.getEntityData(entityUuid).hasRelationship("friend", friend);

            boolean success = loaded && added && removedInL2 && commandStillThere && stillRemoved;

            TestLogger.logValidation("player", "RestartDoesNotReplayRelationshipCommands", success,
                    "Restart Replay - loaded: " + loaded + ", added: " + added + ", removedInL2: " + removedInL2 +
                            ", commandStillThere: " + commandStillThere + ", stillRemoved: " + stillRemoved);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Restart replay test failed: " + exception.getMessage());
            return false;
        } finally {
            if (!firstStopped) {
                shutdownQuietly(first);
            }
            if (second != null) {
                shutdownQuietly(second);
            }
        }
    }

    /**
     * Test that a relationship command already in place, such as one read twice, is not stamped anew.
     */
    public static boolean testRelationshipCommandInPlaceIsNotStampedAgain() {
        try {
            UUID target = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            entity.applyRelationshipChange("friend", target, false);
            String addStamp = entity.getRelationshipStamps().get(LegacyEntityData.relationshipKey("friend", target));
            long addVersion = entity.getVersion();

            entity.applyRelationshipChange("friend", target, false);
            boolean addUnchanged = addStamp.equals(entity.getRelationshipStamps().get(LegacyEntityData.relationshipKey("friend", target)))
                    && entity.getVersion() == addVersion;

            entity.applyRelationshipChange("friend", target, true);
            String removeStamp = entity.getRelationshipStamps().get(LegacyEntityData.relationshipKey("friend", target));
            long removeVersion = entity.getVersion();
            entity.applyRelationshipChange("friend", target, true);
            boolean removeUnchanged = removeStamp.equals(entity.getRelationshipStamps().get(LegacyEntityData.relationshipKey("friend", target)))
                    && entity.getVersion() == removeVersion && !entity.hasRelationship("friend", target);

            boolean success = addUnchanged && removeUnchanged;

            TestLogger.logValidation("player", "RelationshipCommandInPlaceIsNotStampedAgain", success,
                    "Idempotent Relationship - addUnchanged: " + addUnchanged + ", removeUnchanged: " + removeUnchanged);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Idempotent relationship test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that loading an entity into L1 while a save of it lands keeps the save: the load merges into the instance
     * the save cached instead of replacing it.
     */
    public static boolean testLoadMergesIntoInstanceSavedMeanwhile() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-load-race", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "base");
            service.saveEntity(entity);
            boolean inL2 = awaitCondition(() -> service.getFromL2Cache(entityUuid).isPresent(), 5000);
            LegacyEntityData stored = service.getFromL2Cache(entityUuid).orElseThrow();

            int rounds = 300;
            int lost = 0;
            for (int round = 0; round < rounds; round++) {
                service.getL1Cache().getResource().invalidate(entityUuid);
                LegacyEntityData edit = copyOf(stored);
                edit.addAttribute("k" + round, "saved");

                CountDownLatch start = new CountDownLatch(1);
                CompletableFuture<?> load = CompletableFuture.runAsync(() -> {
                    awaitQuietly(start);
                    service.getEntityData(entityUuid);
                });
                CompletableFuture<?> save = CompletableFuture.runAsync(() -> {
                    awaitQuietly(start);
                    service.saveEntityWithoutRepublish(edit);
                });
                start.countDown();
                CompletableFuture.allOf(load, save).get(10, TimeUnit.SECONDS);

                String key = "k" + round;
                if (service.getFromL1Cache(entityUuid).map(cached -> cached.getAttribute(key)).isEmpty()) {
                    lost++;
                }
            }

            boolean success = inL2 && lost == 0;

            TestLogger.logValidation("player", "LoadMergesIntoInstanceSavedMeanwhile", success,
                    "Load Race - inL2: " + inL2 + ", saves lost: " + lost + " of " + rounds);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Load race test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that shutting down while another holder keeps the persistence lock still persists what was saved: the
     * final persistence waits for the lock instead of giving up after half a second.
     */
    public static boolean testShutdownPersistsWhileLockIsHeld() {
        String serviceName = "test-entity-stream-shutdown-" + System.currentTimeMillis();
        LegacyEntityDataService service = createServer(serviceName);
        LegacyEntityDataService checker = null;
        boolean stopped = false;

        try {
            CountDownLatch held = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().start(() -> {
                RLock lock = service.getL2Cache().getResource()
                        .getLock(EntityRKeyUtil.getEntityLockKey(service, "persistence-lock"));
                lock.lock(30, TimeUnit.SECONDS);
                held.countDown();
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.unlock();
                }
            });
            boolean lockHeld = held.await(5, TimeUnit.SECONDS);

            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "saved before shutdown");
            service.saveEntity(entity);
            Thread.sleep(1000);
            boolean notYetInDatabase = service.getFromDatabase(entityUuid) == null;

            shutdownQuietly(service);
            stopped = true;
            holder.join(10000);

            checker = createServer(serviceName);
            LegacyEntityData persisted = checker.getFromDatabase(entityUuid);
            boolean inDatabase = persisted != null && "saved before shutdown".equals(persisted.getAttribute("value"));

            boolean success = lockHeld && notYetInDatabase && inDatabase;

            TestLogger.logValidation("player", "ShutdownPersistsWhileLockIsHeld", success,
                    "Shutdown - lockHeld: " + lockHeld + ", notYetInDatabase: " + notYetInDatabase +
                            ", inDatabase: " + inDatabase);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Shutdown persistence test failed: " + exception.getMessage());
            return false;
        } finally {
            if (!stopped) {
                shutdownQuietly(service);
            }
            if (checker != null) {
                shutdownQuietly(checker);
            }
        }
    }

    /**
     * Test the acceptOwnMessages contract: an accepter returning {@code false} is not called for its own service's
     * messages but is for others', and one keeping the default {@code true} is called for both.
     */
    public static boolean testAcceptOwnMessagesContract() {
        String serviceName = "test-entity-stream-own-contract-" + System.currentTimeMillis();
        LegacyEntityDataService service = createServer(serviceName, EntitySyncSettings.defaults(), "net.legacy.library.player.test");

        try {
            String own = "own-" + UUID.randomUUID();
            String remote = "remote-" + UUID.randomUUID();
            for (String action : List.of(SkippingOwnMessagesTestAccepter.ACTION_NAME, DefaultOwnMessagesTestAccepter.ACTION_NAME)) {
                service.pubEntityRStreamTask(EntityRStreamTask.of(action, own, Duration.ofMinutes(1))).get(5, TimeUnit.SECONDS);
                addRemoteMessage(service, UUID.randomUUID().toString(), EntityRStreamTask.of(action, remote, Duration.ofMinutes(1)));
            }

            boolean defaultGotBoth = awaitCondition(() -> DefaultOwnMessagesTestAccepter.RECEIVED.contains(own)
                    && DefaultOwnMessagesTestAccepter.RECEIVED.contains(remote), 8000);
            boolean skippingGotRemote = awaitCondition(() -> SkippingOwnMessagesTestAccepter.RECEIVED.contains(remote), 8000);
            boolean skippingMissedOwn = !SkippingOwnMessagesTestAccepter.RECEIVED.contains(own);

            boolean success = defaultGotBoth && skippingGotRemote && skippingMissedOwn;

            TestLogger.logValidation("player", "AcceptOwnMessagesContract", success,
                    "Own Messages - defaultGotBoth: " + defaultGotBoth + ", skippingGotRemote: " + skippingGotRemote +
                            ", skippingMissedOwn: " + skippingMissedOwn);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Own messages contract test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a publication carries only what changed since the previous one, removals included.
     */
    public static boolean testOnlyChangesSincePublicationArePublished() {
        // The stream is never read during this test, so the published messages stay in place
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-delta", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("first", "1");
            entity.addAttribute("second", "2");
            service.saveEntity(entity);
            String publisher = service.getInstanceId().toString();
            boolean firstPublished = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            LegacyEntityData cached = service.getEntityData(entityUuid);
            cached.addAttribute("second", "3");
            service.saveEntity(cached);
            boolean secondPublished = awaitCondition(() -> countMessages(service, publisher) == 2, 5000);

            cached.removeAttribute("first");
            service.saveEntity(cached);
            boolean thirdPublished = awaitCondition(() -> countMessages(service, publisher) == 3, 5000);

            List<JsonObject> published = readMessages(service).values().stream()
                    .map(message -> GsonUtil.getGson().fromJson(String.valueOf(message.get("data")), JsonObject.class))
                    .toList();
            boolean firstCarriesBoth = published.get(0).getAsJsonObject("stamps").keySet().equals(Set.of("first", "second"));
            boolean secondCarriesOne = published.get(1).getAsJsonObject("stamps").keySet().equals(Set.of("second"))
                    && published.get(1).getAsJsonObject("attributes").keySet().equals(Set.of("second"));
            boolean thirdCarriesRemoval = published.get(2).getAsJsonObject("stamps").keySet().equals(Set.of("first"))
                    && published.get(2).getAsJsonObject("attributes").keySet().isEmpty();

            boolean success = firstPublished && secondPublished && thirdPublished && firstCarriesBoth
                    && secondCarriesOne && thirdCarriesRemoval;

            TestLogger.logValidation("player", "OnlyChangesSincePublicationArePublished", success,
                    "Delta - published: " + firstPublished + "/" + secondPublished + "/" + thirdPublished +
                            ", firstCarriesBoth: " + firstCarriesBoth + ", secondCarriesOne: " + secondCarriesOne +
                            ", thirdCarriesRemoval: " + thirdCarriesRemoval);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Delta publication test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a server which skipped the changes of an entity it did not hold, and loads it after, still has them:
     * they are published once they are in L2. Later changes then reach it through the stream.
     */
    public static boolean testLateLoaderHasTheChangesItSkipped() {
        String serviceName = "test-entity-stream-late-loader-" + System.currentTimeMillis();
        LegacyEntityDataService first = createServer(serviceName);
        LegacyEntityDataService second = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "one");
            first.saveEntity(entity);

            second = createServer(serviceName);
            LegacyEntityDataService secondServer = second;
            String publisher = first.getInstanceId().toString();
            boolean firstPublished = awaitCondition(() -> countMessages(secondServer, publisher) >= 1, 5000);

            // Changed on the first server while the second does not hold the entity; loaded the moment it is published
            LegacyEntityData cached = first.getEntityData(entityUuid);
            cached.addAttribute("value", "two");
            cached.addAttribute("extra", "skipped");
            first.saveEntity(cached);
            boolean changePublished = awaitCondition(() -> countMessages(secondServer, publisher) >= 2, 5000);
            LegacyEntityData loaded = second.getEntityData(entityUuid);
            boolean loadedCurrent = "two".equals(loaded.getAttribute("value")) && "skipped".equals(loaded.getAttribute("extra"));

            cached.addAttribute("later", "streamed");
            first.saveEntity(cached);
            boolean laterArrived = awaitCondition(() -> "streamed".equals(secondServer.getEntityData(entityUuid).getAttribute("later")), 8000);
            boolean earlierKept = "two".equals(second.getEntityData(entityUuid).getAttribute("value"))
                    && "skipped".equals(second.getEntityData(entityUuid).getAttribute("extra"));

            boolean success = firstPublished && changePublished && loadedCurrent && laterArrived && earlierKept;

            TestLogger.logValidation("player", "LateLoaderHasTheChangesItSkipped", success,
                    "Late Loader - published: " + firstPublished + "/" + changePublished + ", loadedCurrent: " +
                            loadedCurrent + ", laterArrived: " + laterArrived + ", earlierKept: " + earlierKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Late loader test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(first);
            if (second != null) {
                shutdownQuietly(second);
            }
        }
    }

    /**
     * Test that removing a relationship this copy never saw still beats the older addition of it elsewhere.
     */
    public static boolean testRemovalOfUnseenRelationshipBeatsOlderAddition() {
        try {
            UUID target = UUID.randomUUID();
            LegacyEntityData local = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            local.addAttribute("value", "base");
            LegacyEntityData remote = copyOf(local);

            remote.addRelationship("friend", target);
            Thread.sleep(5);
            local.removeRelationship("friend", target);

            LegacyEntityData remoteBefore = copyOf(remote);
            local.mergeChangesFrom(remote);
            remoteBefore.mergeChangesFrom(copyOf(local));

            boolean removedHere = !local.hasRelationship("friend", target);
            boolean removedThere = !remoteBefore.hasRelationship("friend", target);

            boolean success = removedHere && removedThere;

            TestLogger.logValidation("player", "RemovalOfUnseenRelationshipBeatsOlderAddition", success,
                    "Unseen Removal - removedHere: " + removedHere + ", removedThere: " + removedThere);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Unseen removal test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that clearing a relationship type also removes the additions made before it that this copy never saw,
     * in either merge direction and through a published change, while an addition made after the clearing stays.
     */
    public static boolean testClearingBeatsUnseenOlderAdditions() {
        try {
            UUID known = UUID.randomUUID();
            UUID unseen = UUID.randomUUID();
            UUID afterwards = UUID.randomUUID();
            LegacyEntityData local = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            local.addRelationship("friend", known);
            LegacyEntityData remote = copyOf(local);
            LegacyEntityData streamed = copyOf(local);

            remote.addRelationship("friend", unseen);
            streamed.addRelationship("friend", unseen);
            Thread.sleep(5);
            local.clearRelationships("friend");

            // Both directions of a full merge
            LegacyEntityData remoteCopy = copyOf(remote);
            local.mergeChangesFrom(remoteCopy);
            remote.mergeChangesFrom(copyOf(local));
            boolean clearedHere = local.countRelationships("friend") == 0;
            boolean clearedThere = remote.countRelationships("friend") == 0;

            // A published change carries the clearing too
            streamed.mergeChanges(copyOf(local).takeUnpublishedChanges());
            boolean clearedThroughStream = streamed.countRelationships("friend") == 0;

            // An addition after the clearing is kept
            LegacyEntityData later = copyOf(local);
            later.addRelationship("friend", afterwards);
            local.mergeChangesFrom(later);
            boolean laterKept = local.hasRelationship("friend", afterwards) && local.countRelationships("friend") == 1;

            boolean success = clearedHere && clearedThere && clearedThroughStream && laterKept;

            TestLogger.logValidation("player", "ClearingBeatsUnseenOlderAdditions", success,
                    "Clearing - clearedHere: " + clearedHere + ", clearedThere: " + clearedThere +
                            ", clearedThroughStream: " + clearedThroughStream + ", laterKept: " + laterKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Clearing test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that the database step reaches every entity a server wrote, however many there are: it used to persist
     * only as many as the limit, the same ones every run.
     */
    public static boolean testPersistenceReachesEveryEntityBeyondTheLimit() {
        String serviceName = "test-entity-stream-limit-" + System.currentTimeMillis();
        LegacyEntityDataService service = createServer(serviceName);

        try {
            // Cached without a save, so only the runs below persist them
            List<UUID> uuids = new ArrayList<>();
            for (int index = 0; index < 30; index++) {
                LegacyEntityData entity = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
                entity.addAttribute("index", String.valueOf(index));
                service.getL1Cache().getResource().put(entity.getUuid(), entity);
                uuids.add(entity.getUuid());
            }

            int runs = 0;
            long persisted = 0;
            while (runs < 3) {
                EntityDataPersistenceTask.of(LockSettings.of(5, 5, TimeUnit.SECONDS), service, 10).start().get(30, TimeUnit.SECONDS);
                runs++;
                persisted = uuids.stream().filter(uuid -> service.getFromDatabase(uuid) != null).count();
            }

            boolean success = persisted == uuids.size();

            TestLogger.logValidation("player", "PersistenceReachesEveryEntityBeyondTheLimit", success,
                    "Limit - persisted after " + runs + " runs of 10: " + persisted + " of " + uuids.size());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Persistence limit test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that writing an entity to L2 merges in what another server wrote there meanwhile instead of overwriting it.
     */
    public static boolean testL2WriteKeepsChangesAnotherServerWrote() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-l2-merge", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "base");
            service.saveEntity(entity);
            boolean inL2 = awaitCondition(() -> service.getFromL2Cache(entityUuid).isPresent(), 5000);

            // Another server's write lands in L2 while this one changes its own copy
            LegacyEntityData other = service.getFromL2Cache(entityUuid).orElseThrow();
            other.addAttribute("fromOther", "yes");
            service.getL2Cache().getResource().getBucket(EntityRKeyUtil.getEntityKey(entityUuid, service))
                    .set(SimplixSerializer.serialize(other).toString());
            LegacyEntityData cached = service.getFromL1Cache(entityUuid).orElseThrow();
            cached.addAttribute("fromHere", "yes");

            L1ToL2EntityDataSyncTask.of(entityUuid, service).start().get(10, TimeUnit.SECONDS);
            LegacyEntityData stored = service.getFromL2Cache(entityUuid).orElseThrow();
            boolean bothKept = "yes".equals(stored.getAttribute("fromOther")) && "yes".equals(stored.getAttribute("fromHere"));

            boolean success = inL2 && bothKept;

            TestLogger.logValidation("player", "L2WriteKeepsChangesAnotherServerWrote", success,
                    "L2 Merge - inL2: " + inL2 + ", bothKept: " + bothKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "L2 merge test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a save is published only once its state is in L2, however long writing it there is held up.
     */
    public static boolean testUpdateIsPublishedOnlyOnceInL2() {
        // The stream is never read during this test, so the published messages stay in place
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-publish-after-l2", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "one");
            service.saveEntity(entity);
            String publisher = service.getInstanceId().toString();
            boolean firstPublished = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            // Writing the entity to L2 is held up by its write lock, held elsewhere for 2.5 seconds
            CountDownLatch held = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().start(() -> {
                RReadWriteLock lock = service.getL2Cache().getResource().getReadWriteLock(
                        EntityRKeyUtil.getEntityReadWriteLockKey(EntityRKeyUtil.getEntityKey(entityUuid, service)));
                lock.writeLock().lock(30, TimeUnit.SECONDS);
                held.countDown();
                try {
                    Thread.sleep(2500);
                } catch (InterruptedException exception) {
                    Thread.currentThread().interrupt();
                } finally {
                    lock.writeLock().unlock();
                }
            });
            boolean lockHeld = held.await(5, TimeUnit.SECONDS);

            LegacyEntityData cached = service.getFromL1Cache(entityUuid).orElseThrow();
            cached.addAttribute("value", "two");
            service.saveEntity(cached);
            Thread.sleep(1500);
            boolean heldBack = countMessages(service, publisher) == 1;

            holder.join(10000);
            boolean publishedAfter = awaitCondition(() -> countMessages(service, publisher) == 2, 10000);
            boolean inL2 = service.getFromL2Cache(entityUuid).map(stored -> "two".equals(stored.getAttribute("value"))).orElse(false);

            boolean success = firstPublished && lockHeld && heldBack && publishedAfter && inL2;

            TestLogger.logValidation("player", "UpdateIsPublishedOnlyOnceInL2", success,
                    "Publish after L2 - firstPublished: " + firstPublished + ", lockHeld: " + lockHeld +
                            ", heldBack: " + heldBack + ", publishedAfter: " + publishedAfter + ", inL2: " + inL2);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Publish after L2 test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a removal stamp forgotten after the tombstone retention stays forgotten: neither writing the entity
     * to L2, which holds the stamp still, nor saving it again brings it back.
     */
    public static boolean testPrunedTombstoneStaysGoneThroughL2() {
        String serviceName = "test-entity-stream-tombstone-l2-" + System.currentTimeMillis();
        LegacyEntityDataService service = createServer(serviceName,
                EntitySyncSettings.builder().tombstoneRetention(Duration.ofSeconds(1)).build(), "net.legacy.library.player.task.redis.impl");

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("kept", "yes");
            entity.addAttribute("removed", "yes");
            service.saveEntity(entity);
            LegacyEntityData cached = service.getFromL1Cache(entityUuid).orElseThrow();
            cached.removeAttribute("removed");
            service.saveEntity(cached);
            boolean tombstoneInL2 = awaitCondition(() -> service.getFromL2Cache(entityUuid)
                    .map(stored -> stored.getAttributeStamps().containsKey("removed")).orElse(false), 5000);

            Thread.sleep(1500);
            int pruned = service.pruneTombstones();
            boolean prunedHere = !cached.getAttributeStamps().containsKey("removed");

            // L2 still holds the stamp; writing the entity there merges it
            L1ToL2EntityDataSyncTask.of(entityUuid, service).start().get(10, TimeUnit.SECONDS);
            boolean goneInL2 = service.getFromL2Cache(entityUuid)
                    .map(stored -> !stored.getAttributeStamps().containsKey("removed") && "yes".equals(stored.getAttribute("kept")))
                    .orElse(false);

            cached.addAttribute("kept", "again");
            service.saveEntity(cached);
            Thread.sleep(500);
            boolean goneAfterSave = !cached.getAttributeStamps().containsKey("removed") && cached.getAttribute("removed") == null;

            boolean success = tombstoneInL2 && pruned >= 1 && prunedHere && goneInL2 && goneAfterSave;

            TestLogger.logValidation("player", "PrunedTombstoneStaysGoneThroughL2", success,
                    "Tombstone through L2 - tombstoneInL2: " + tombstoneInL2 + ", pruned: " + pruned + ", prunedHere: " +
                            prunedHere + ", goneInL2: " + goneInL2 + ", goneAfterSave: " + goneAfterSave);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Tombstone through L2 test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that player data written straight to L2 reaches the database however low the limit: each run persists
     * what waits, instead of scanning the same keys again.
     */
    public static boolean testPlayerDataWrittenToL2ReachesTheDatabase() {
        LegacyPlayerDataService players = TestConnectionResource.createTestService(
                "player-pending-db", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            List<UUID> uuids = new ArrayList<>();
            for (int index = 0; index < 3; index++) {
                LegacyPlayerData playerData = LegacyPlayerData.of(UUID.randomUUID());
                playerData.addData("index", String.valueOf(index));
                players.saveLegacyPlayerDataToL2Cache(playerData);
                uuids.add(playerData.getUuid());
            }

            for (int run = 0; run < 3; run++) {
                PlayerDataPersistenceTask.of(LockSettings.of(5, 5, TimeUnit.SECONDS), players, 1).start().get(30, TimeUnit.SECONDS);
            }
            // getFromDatabase answers a fresh, empty player when there is none, so the stored field tells
            long persisted = uuids.stream().filter(uuid -> players.getFromDatabase(uuid).getData("index") != null).count();

            boolean success = persisted == uuids.size();

            TestLogger.logValidation("player", "PlayerDataWrittenToL2ReachesTheDatabase", success,
                    "Player pending - persisted after 3 runs of 1: " + persisted + " of " + uuids.size());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Player pending test failed: " + exception.getMessage());
            return false;
        } finally {
            players.getRedisStreamAcceptTask().cancel(false);
            players.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                players.shutdown();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test player service: %s", exception.getMessage());
            }
        }
    }

    /**
     * Test that a publication leaves out the changes merged in from other servers: they were published by their own
     * servers, and sending them again with every local change would grow the messages with every writer.
     */
    public static boolean testMergedRemoteChangesAreNotPublishedAgain() {
        // The stream is never read during this test, so the published messages stay in place
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-no-republish", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "one");
            service.saveEntity(entity);
            String publisher = service.getInstanceId().toString();
            boolean firstPublished = awaitCondition(() -> countMessages(service, publisher) == 1, 5000);

            // Another server's change, merged in as the stream does, at a higher version
            LegacyEntityData cached = service.getFromL1Cache(entityUuid).orElseThrow();
            long remoteVersion = cached.getVersion() + 5;
            cached.mergeChanges(new LegacyEntityData.Changes(Map.of("remote", "x"),
                    Map.of("remote", new LegacyEntityData.Stamp(remoteVersion, System.currentTimeMillis(), "other-server").encode()),
                    Set.of(), Map.of(), remoteVersion, System.currentTimeMillis(), 0));

            cached.addAttribute("local", "y");
            service.saveEntity(cached);
            boolean secondPublished = awaitCondition(() -> countMessages(service, publisher) == 2, 5000);

            List<JsonObject> published = readMessages(service).values().stream()
                    .map(message -> GsonUtil.getGson().fromJson(String.valueOf(message.get("data")), JsonObject.class))
                    .toList();
            boolean onlyLocal = published.get(1).getAsJsonObject("stamps").keySet().equals(Set.of("local"));

            boolean success = firstPublished && secondPublished && onlyLocal;

            TestLogger.logValidation("player", "MergedRemoteChangesAreNotPublishedAgain", success,
                    "No Republish - published: " + firstPublished + "/" + secondPublished + ", second carries: " +
                            published.get(1).getAsJsonObject("stamps").keySet());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "No republish test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that the database step saves an entity as it is in L2, which holds what every server merged in, rather
     * than the copy cached here.
     */
    public static boolean testDatabaseStepSavesTheL2State() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-db-from-l2", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData cached = LegacyEntityData.of(entityUuid, "TestEntity");
            cached.addAttribute("value", "cached");
            service.getL1Cache().getResource().put(entityUuid, cached);

            // L2 holds a newer state written by another server, still waiting for the database
            LegacyEntityData newer = copyOf(cached);
            newer.addAttribute("value", "in-l2");
            RedissonClient redissonClient = service.getL2Cache().getResource();
            redissonClient.getBucket(EntityRKeyUtil.getEntityKey(entityUuid, service)).set(SimplixSerializer.serialize(newer).toString());
            redissonClient.getSet(EntityRKeyUtil.getPendingDatabaseKey(service)).add(entityUuid.toString());

            EntityDataPersistenceTask.of(LockSettings.of(5, 30, TimeUnit.SECONDS), service).savedOnly().start().get(30, TimeUnit.SECONDS);
            LegacyEntityData persisted = service.getFromDatabase(entityUuid);
            boolean savedFromL2 = persisted != null && "in-l2".equals(persisted.getAttribute("value"));

            TestLogger.logValidation("player", "DatabaseStepSavesTheL2State", savedFromL2,
                    "DB from L2 - persisted: " + (persisted == null ? "null" : persisted.getAttribute("value")));

            return savedFromL2;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "DB from L2 test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that an entity one server wrote to L2 and never persisted, as when it crashes, is persisted by another
     * server's next run, though that run does not scan L2.
     */
    public static boolean testEntityLeftByCrashedServerIsPersistedElsewhere() {
        String serviceName = "test-entity-stream-crash-" + System.currentTimeMillis();
        LegacyEntityDataService crashed = createServer(serviceName);
        LegacyEntityDataService other = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "written before the crash");
            crashed.getL1Cache().getResource().put(entityUuid, entity);

            // Written to L2, then the server is gone before its database step
            L1ToL2EntityDataSyncTask.of(entityUuid, crashed).start().get(10, TimeUnit.SECONDS);
            crashed.getRedisStreamAcceptTask().cancel(false);
            crashed.getEntityDataPersistenceTimerTask().cancel(false);
            boolean notInDatabase = crashed.getFromDatabase(entityUuid) == null;

            other = createServer(serviceName);
            EntityDataPersistenceTask.of(LockSettings.of(5, 30, TimeUnit.SECONDS), other).savedOnly().start().get(30, TimeUnit.SECONDS);
            LegacyEntityData persisted = other.getFromDatabase(entityUuid);
            boolean persistedElsewhere = persisted != null && "written before the crash".equals(persisted.getAttribute("value"));

            boolean success = notInDatabase && persistedElsewhere;

            TestLogger.logValidation("player", "EntityLeftByCrashedServerIsPersistedElsewhere", success,
                    "Crash leftover - notInDatabase: " + notInDatabase + ", persistedElsewhere: " + persistedElsewhere);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Crash leftover test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(crashed);
            if (other != null) {
                shutdownQuietly(other);
            }
        }
    }

    /**
     * Test that a save made after the shutdown began waits for the final persistence instead of being answered at
     * once, so it is not published before its state is in L2.
     */
    public static boolean testRequestsAfterCloseWaitForTheFinalRun() {
        try {
            CoalescedPersistence persistence = new CoalescedPersistence(synced -> {
                synced.complete(null);
                return CompletableFuture.completedFuture(null);
            });
            persistence.close();
            CompletableFuture<Void> request = persistence.request();
            boolean waits = !request.isDone();
            persistence.completeFinalRun();
            boolean answered = request.isDone();

            boolean success = waits && answered;

            TestLogger.logValidation("player", "RequestsAfterCloseWaitForTheFinalRun", success,
                    "After close - waits: " + waits + ", answered by the final run: " + answered);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "After close test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that a write lock held by another server while an entity or a player is written to L2 defers it to the
     * next run without logging an error, and that the next run writes it.
     */
    public static boolean testBusyWriteLockDefersWithoutError() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-busy-lock", Duration.ofMinutes(30), Duration.ofMinutes(30));
        LegacyPlayerDataService players = TestConnectionResource.createTestService(
                "player-busy-lock", Duration.ofMinutes(30), Duration.ofMinutes(30));
        ILogger original = Log.get();
        RecordingLogger recording = new RecordingLogger(original);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "contended");
            service.getL1Cache().getResource().put(entityUuid, entity);
            LegacyPlayerData playerData = LegacyPlayerData.of(UUID.randomUUID());
            playerData.addData("value", "contended");
            players.getL1Cache().getResource().put(playerData.getUuid(), playerData);

            // Another server holds both write locks for longer than a write waits
            RedissonClient redissonClient = service.getL2Cache().getResource();
            RReadWriteLock entityLock = redissonClient.getReadWriteLock(
                    EntityRKeyUtil.getEntityReadWriteLockKey(EntityRKeyUtil.getEntityKey(entityUuid, service)));
            RReadWriteLock playerLock = players.getL2Cache().getResource().getReadWriteLock(
                    RKeyUtil.getRLPDSReadWriteLockKey(RKeyUtil.getRLPDSKey(playerData.getUuid(), players)));
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().start(() -> {
                entityLock.writeLock().lock(30, TimeUnit.SECONDS);
                playerLock.writeLock().lock(30, TimeUnit.SECONDS);
                held.countDown();
                awaitQuietly(release);
                playerLock.writeLock().unlock();
                entityLock.writeLock().unlock();
            });
            boolean lockHeld = held.await(5, TimeUnit.SECONDS);

            Log.set(recording);
            L1ToL2EntityDataSyncTask.of(entityUuid, service).start().get(10, TimeUnit.SECONDS);
            L1ToL2PlayerDataSyncTask.of(playerData.getUuid(), players).start().get(10, TimeUnit.SECONDS);
            Log.set(original);
            // Read straight from the buckets: the player read takes the read lock still held
            boolean deferred = service.getFromL2Cache(entityUuid).isEmpty()
                    && players.getL2Cache().getResource().getBucket(RKeyUtil.getRLPDSKey(playerData.getUuid(), players)).get() == null;
            boolean noError = recording.errors.isEmpty();

            release.countDown();
            holder.join(10000);
            L1ToL2EntityDataSyncTask.of(entityUuid, service).start().get(10, TimeUnit.SECONDS);
            L1ToL2PlayerDataSyncTask.of(playerData.getUuid(), players).start().get(10, TimeUnit.SECONDS);
            boolean writtenLater = service.getFromL2Cache(entityUuid).map(stored -> "contended".equals(stored.getAttribute("value"))).orElse(false)
                    && players.getFromL2Cache(playerData.getUuid()).map(stored -> "contended".equals(stored.getData("value"))).orElse(false);

            boolean success = lockHeld && deferred && noError && writtenLater;

            TestLogger.logValidation("player", "BusyWriteLockDefersWithoutError", success,
                    "Busy lock - lockHeld: " + lockHeld + ", deferred: " + deferred + ", errors logged: " +
                            recording.errors + ", writtenLater: " + writtenLater);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Busy lock test failed: " + exception.getMessage());
            return false;
        } finally {
            Log.set(original);
            shutdownQuietly(service);
            players.getRedisStreamAcceptTask().cancel(false);
            players.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                players.shutdown();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test player service: %s", exception.getMessage());
            }
        }
    }

    /**
     * Passes every line on and records those logged at error level or with a stack trace, which shows as errors.
     */
    private static final class RecordingLogger implements ILogger {

        private final ILogger delegate;
        private final List<String> errors = new CopyOnWriteArrayList<>();

        private RecordingLogger(ILogger delegate) {
            this.delegate = delegate;
        }

        @Override
        public void info(String message, Object... args) {
            delegate.info(message, args);
        }

        @Override
        public void debug(String message, Object... args) {
            delegate.debug(message, args);
        }

        @Override
        public void warn(String message, Object... args) {
            delegate.warn(message, args);
        }

        @Override
        public void error(String message, Object... args) {
            errors.add(message);
            delegate.error(message, args);
        }

        @Override
        public void info(String message, Throwable throwable, Object... args) {
            errors.add(message);
            delegate.info(message, throwable, args);
        }

        @Override
        public void debug(String message, Throwable throwable, Object... args) {
            delegate.debug(message, throwable, args);
        }

        @Override
        public void warn(String message, Throwable throwable, Object... args) {
            errors.add(message);
            delegate.warn(message, throwable, args);
        }

        @Override
        public void error(String message, Throwable throwable, Object... args) {
            errors.add(message);
            delegate.error(message, throwable, args);
        }

        @Override
        public void info(Throwable throwable) {
            errors.add(String.valueOf(throwable));
            delegate.info(throwable);
        }

        @Override
        public void debug(Throwable throwable) {
            delegate.debug(throwable);
        }

        @Override
        public void warn(Throwable throwable) {
            errors.add(String.valueOf(throwable));
            delegate.warn(throwable);
        }

        @Override
        public void error(Throwable throwable) {
            errors.add(String.valueOf(throwable));
            delegate.error(throwable);
        }

    }

    /**
     * Test that TestLogger logs a message without arguments as written, so a {@code %} in it needs no escaping, and
     * still formats a message given arguments.
     */
    public static boolean testTestLoggerFormatsOnlyWithArguments() {
        try {
            TestLogger.logInfo("player", "Rate at 100% with %d in the text, no arguments");
            TestLogger.logInfo("player", "Formatted: %d of %d", 1, 2);

            TestLogger.logValidation("player", "TestLoggerFormatsOnlyWithArguments", true,
                    "TestLogger - a message with % and no arguments logged as written");
            return true;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "TestLogger formatting test failed: " + exception.getClass().getSimpleName());
            return false;
        }
    }

    /**
     * Test the coalescing of persistence runs: requests made during a run share one more run; a run that failed to
     * start does not keep later requests from running; a failed run is retried without a new request.
     */
    public static boolean testPersistenceRunsCoalesceAndRecover() {
        try {
            // Fifty requests while a slow run goes: one run, then one more for all of them
            AtomicInteger runs = new AtomicInteger();
            CompletableFuture<Void> gate = new CompletableFuture<>();
            CoalescedPersistence coalesced = new CoalescedPersistence(synced -> {
                runs.incrementAndGet();
                synced.complete(null);
                return runs.get() == 1 ? gate : CompletableFuture.completedFuture(null);
            });
            coalesced.request();
            for (int index = 0; index < 50; index++) {
                coalesced.request();
            }
            gate.complete(null);
            boolean idle = coalesced.awaitIdle(Duration.ofSeconds(5));
            boolean coalescedRuns = idle && runs.get() == 2;

            // A run that throws while starting: the next request still runs
            AtomicInteger starts = new AtomicInteger();
            CoalescedPersistence failingStart = new CoalescedPersistence(synced -> {
                if (starts.incrementAndGet() == 1) {
                    throw new IllegalStateException("cannot start");
                }
                synced.complete(null);
                return CompletableFuture.completedFuture(null);
            });
            failingStart.request();
            failingStart.request();
            boolean recoveredStart = failingStart.awaitIdle(Duration.ofSeconds(5)) && starts.get() == 2;

            // A run that fails: retried on its own, after a short wait
            AtomicInteger attempts = new AtomicInteger();
            CoalescedPersistence failingRun = new CoalescedPersistence(synced -> {
                synced.complete(null);
                return attempts.incrementAndGet() == 1
                        ? CompletableFuture.failedFuture(new IllegalStateException("L2 unreachable"))
                        : CompletableFuture.completedFuture(null);
            });
            failingRun.request();
            boolean retried = awaitCondition(() -> attempts.get() == 2, 3000) && failingRun.awaitIdle(Duration.ofSeconds(5));

            boolean success = coalescedRuns && recoveredStart && retried;

            TestLogger.logValidation("player", "PersistenceRunsCoalesceAndRecover", success,
                    "Coalescing - runs for 51 requests: " + runs.get() + ", starts after a failed start: " + starts.get() +
                            ", attempts after a failed run: " + attempts.get());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Coalescing test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that reading an entity from L2 does not wait for its write lock: the value is always written whole.
     */
    public static boolean testL2ReadDoesNotWaitForTheWriteLock() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-lock-free-read", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "stored");
            service.getL2Cache().getResource().getBucket(EntityRKeyUtil.getEntityKey(entityUuid, service))
                    .set(SimplixSerializer.serialize(entity).toString());

            RReadWriteLock lock = service.getL2Cache().getResource().getReadWriteLock(
                    EntityRKeyUtil.getEntityReadWriteLockKey(EntityRKeyUtil.getEntityKey(entityUuid, service)));
            CountDownLatch held = new CountDownLatch(1);
            CountDownLatch release = new CountDownLatch(1);
            Thread holder = Thread.ofVirtual().start(() -> {
                lock.writeLock().lock(30, TimeUnit.SECONDS);
                held.countDown();
                awaitQuietly(release);
                lock.writeLock().unlock();
            });
            held.await(5, TimeUnit.SECONDS);

            long start = System.nanoTime();
            boolean read = service.getFromL2Cache(entityUuid).map(stored -> "stored".equals(stored.getAttribute("value"))).orElse(false);
            long tookMillis = Duration.ofNanos(System.nanoTime() - start).toMillis();
            release.countDown();
            holder.join(5000);

            boolean success = read && tookMillis < 300;

            TestLogger.logValidation("player", "L2ReadDoesNotWaitForTheWriteLock", success,
                    "Lock-free read - read: " + read + ", took: " + tookMillis + "ms with the write lock held");

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Lock-free read test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test that a shutdown first stops the scheduled persistence and the stream reads.
     */
    public static boolean testShutdownStopsSchedulesFirst() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-shutdown-order", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            service.shutdown();
            boolean timerStopped = service.getEntityDataPersistenceTimerTask().getScheduledFuture().isCancelled();
            boolean readsStopped = service.getRedisStreamAcceptTask().getScheduledFuture().isCancelled();

            boolean success = timerStopped && readsStopped;

            TestLogger.logValidation("player", "ShutdownStopsSchedulesFirst", success,
                    "Shutdown order - timerStopped: " + timerStopped + ", readsStopped: " + readsStopped);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Shutdown order test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that two threads loading the same player at once get the same instance, so neither one's changes are
     * made on a copy the cache dropped.
     */
    public static boolean testConcurrentPlayerLoadsShareOneInstance() {
        LegacyPlayerDataService players = TestConnectionResource.createTestService(
                "player-load-race", Duration.ofMinutes(30), Duration.ofMinutes(30));

        try {
            UUID uuid = UUID.randomUUID();
            int rounds = 200;
            int split = 0;
            for (int round = 0; round < rounds; round++) {
                players.getL1Cache().getResource().invalidate(uuid);
                CountDownLatch start = new CountDownLatch(1);
                CompletableFuture<LegacyPlayerData> first = CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(start);
                    return players.getLegacyPlayerData(uuid);
                });
                CompletableFuture<LegacyPlayerData> second = CompletableFuture.supplyAsync(() -> {
                    awaitQuietly(start);
                    return players.getLegacyPlayerData(uuid);
                });
                start.countDown();
                if (first.get(10, TimeUnit.SECONDS) != second.get(10, TimeUnit.SECONDS)) {
                    split++;
                }
            }

            boolean success = split == 0;

            TestLogger.logValidation("player", "ConcurrentPlayerLoadsShareOneInstance", success,
                    "Player load race - rounds with two instances: " + split + " of " + rounds);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Player load race test failed: " + exception.getMessage());
            return false;
        } finally {
            players.getRedisStreamAcceptTask().cancel(false);
            players.getPlayerDataPersistenceTimerTask().cancel(false);
            try {
                players.shutdown();
            } catch (Exception exception) {
                TestLogger.logWarning("player", "Failed to shut down test player service: %s", exception.getMessage());
            }
        }
    }

    /**
     * Test that an update in the pair format, without a version, adds what is new and loses to every stamped change.
     */
    public static boolean testPairFormatUpdateLosesToStampedChanges() {
        LegacyEntityDataService service = TestConnectionResource.createTestEntityService(
                "stream-pair-format", Duration.ofMinutes(30), ACCEPT_INTERVAL);

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "local");
            service.saveEntity(entity);

            String data = GsonUtil.getGson().toJson(Pair.of(entityUuid.toString(), Map.of("value", "pair", "added", "pair")));
            addRemoteMessage(service, UUID.randomUUID().toString(), EntityRStreamTask.of("entity-data-update", data, Duration.ofMinutes(5)));

            boolean added = awaitCondition(() -> "pair".equals(service.getEntityData(entityUuid).getAttribute("added")), 5000);
            boolean localKept = "local".equals(service.getEntityData(entityUuid).getAttribute("value"));

            boolean success = added && localKept;

            TestLogger.logValidation("player", "PairFormatUpdateLosesToStampedChanges", success,
                    "Pair format - added: " + added + ", localKept: " + localKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Pair format test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(service);
        }
    }

    /**
     * Test edge cases of the data an update carries: an entity without attributes, a value of a megabyte, and keys
     * and values outside ASCII all reach another server unchanged.
     */
    public static boolean testUnusualEntitiesReachAnotherServer() {
        String serviceName = "test-entity-stream-edge-" + System.currentTimeMillis();
        LegacyEntityDataService first = createServer(serviceName);
        LegacyEntityDataService second = null;

        try {
            UUID emptyUuid = UUID.randomUUID();
            UUID largeUuid = UUID.randomUUID();
            UUID unicodeUuid = UUID.randomUUID();
            String largeValue = "x".repeat(1024 * 1024);
            String unicodeKey = "名字|ключ|🙂";
            String unicodeValue = "值 — значение — 🎉";
            first.saveEntity(LegacyEntityData.of(emptyUuid, "TestEntity"));
            LegacyEntityData large = LegacyEntityData.of(largeUuid, "TestEntity");
            large.addAttribute("large", "small");
            first.saveEntity(large);
            LegacyEntityData unicode = LegacyEntityData.of(unicodeUuid, "TestEntity");
            unicode.addAttribute("plain", "yes");
            first.saveEntity(unicode);

            second = createServer(serviceName);
            LegacyEntityDataService secondServer = second;
            boolean loaded = awaitCondition(() -> secondServer.getEntityData(emptyUuid) != null
                    && secondServer.getEntityData(largeUuid) != null && secondServer.getEntityData(unicodeUuid) != null, 8000);
            boolean emptyKept = second.getEntityData(emptyUuid).getAttributes().isEmpty();

            // Changed after the second server holds them, so they travel through the stream
            LegacyEntityData largeCached = first.getEntityData(largeUuid);
            largeCached.addAttribute("large", largeValue);
            first.saveEntity(largeCached);
            LegacyEntityData unicodeCached = first.getEntityData(unicodeUuid);
            unicodeCached.addAttribute(unicodeKey, unicodeValue);
            first.saveEntity(unicodeCached);

            boolean largeArrived = awaitCondition(() -> largeValue.equals(secondServer.getEntityData(largeUuid).getAttribute("large")), 10000);
            boolean unicodeArrived = awaitCondition(() -> unicodeValue.equals(secondServer.getEntityData(unicodeUuid).getAttribute(unicodeKey)), 10000);

            boolean success = loaded && emptyKept && largeArrived && unicodeArrived;

            TestLogger.logValidation("player", "UnusualEntitiesReachAnotherServer", success,
                    "Edge cases - loaded: " + loaded + ", emptyKept: " + emptyKept + ", largeArrived: " + largeArrived +
                            ", unicodeArrived: " + unicodeArrived);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Edge case test failed: " + exception.getMessage());
            return false;
        } finally {
            shutdownQuietly(first);
            if (second != null) {
                shutdownQuietly(second);
            }
        }
    }

    /**
     * Test that data stored by 1.2.6, without any stamps, loads with usable stamp maps and merges as older than every
     * stamped change, its relationships kept by union.
     */
    public static boolean testUnstampedStoredDataMergesAsOlder() {
        try {
            UUID friend = UUID.randomUUID();
            LegacyEntityData stored = LegacyEntityData.of(UUID.randomUUID(), "TestEntity");
            stored.addAttribute("value", "old");
            stored.addAttribute("onlyStored", "kept");
            stored.addRelationship("friend", friend);

            // As 1.2.6 wrote it: the same fields, no stamps
            JsonObject json = GsonUtil.getGson().fromJson(SimplixSerializer.serialize(stored).toString(), JsonObject.class);
            json.remove("attributeStamps");
            json.remove("relationshipStamps");
            LegacyEntityData unstamped = SimplixSerializer.deserialize(json.toString(), LegacyEntityData.class);
            boolean mapsUsable = unstamped.getAttributeStamps() != null && unstamped.getRelationshipStamps() != null
                    && unstamped.getAttributeStamps().isEmpty();

            LegacyEntityData local = LegacyEntityData.of(stored.getUuid(), "TestEntity");
            local.addAttribute("value", "new");
            local.mergeChangesFrom(unstamped);
            boolean stampedWins = "new".equals(local.getAttribute("value"));
            boolean unstampedAdded = "kept".equals(local.getAttribute("onlyStored"));
            boolean relationshipKept = local.hasRelationship("friend", friend);

            boolean success = mapsUsable && stampedWins && unstampedAdded && relationshipKept;

            TestLogger.logValidation("player", "UnstampedStoredDataMergesAsOlder", success,
                    "Unstamped data - mapsUsable: " + mapsUsable + ", stampedWins: " + stampedWins + ", unstampedAdded: " +
                            unstampedAdded + ", relationshipKept: " + relationshipKept);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Unstamped data test failed: " + exception.getMessage());
            return false;
        }
    }

    /**
     * Test that the retention still sweeps expired messages when {@code XTRIM MINID} fails, as on Redis before 6.2,
     * and that the failure is reported once rather than every round.
     */
    @SuppressWarnings("unchecked")
    public static boolean testRetentionSweepsWhenTrimFails() {
        ILogger original = Log.get();
        RecordingLogger recording = new RecordingLogger(original);
        try {
            List<StreamMessageId> removed = new CopyOnWriteArrayList<>();
            AtomicInteger ranges = new AtomicInteger();
            RStream<Object, Object> stream = (RStream<Object, Object>) java.lang.reflect.Proxy.newProxyInstance(
                    RStream.class.getClassLoader(), new Class<?>[]{RStream.class}, (proxy, method, args) -> switch (method.getName()) {
                        case "trim" -> throw new org.redisson.client.RedisException("ERR syntax error, XTRIM MINID needs Redis 6.2");
                        case "range" -> ranges.incrementAndGet() % 2 == 1
                                ? Map.of(new StreamMessageId(1, 0), Map.of("timeout", "1"))
                                : Map.of();
                        case "remove" -> {
                            removed.addAll(Arrays.asList((StreamMessageId[]) args[0]));
                            yield 1L;
                        }
                        default -> null;
                    });

            Log.set(recording);
            for (int round = 0; round < 3; round++) {
                StreamRetentionUtil.bound(stream, Duration.ofHours(1), "timeout", false);
            }
            Log.set(original);

            boolean swept = removed.size() == 3;
            boolean reportedAtMostOnce = recording.errors.stream().filter(message -> message.contains("trim")).count() <= 1;

            boolean success = swept && reportedAtMostOnce;

            TestLogger.logValidation("player", "RetentionSweepsWhenTrimFails", success,
                    "Trim failure - expired messages removed: " + removed.size() + " of 3, trim warnings: " +
                            recording.errors.stream().filter(message -> message.contains("trim")).count());

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Trim failure test failed: " + exception.getMessage());
            return false;
        } finally {
            Log.set(original);
        }
    }

    /**
     * Test that entities written to L2 while MongoDB is unreachable stay waiting and are persisted by a later run once
     * it is back.
     */
    public static boolean testEntitiesWaitWhileMongoIsDown() {
        String serviceName = "test-entity-stream-mongo-down-" + System.currentTimeMillis();
        MongoDBConnectionConfig unreachable = new MongoDBConnectionConfig(TestConnectionResource.getTestDatabaseName(),
                "mongodb://127.0.0.1:1/?serverSelectionTimeoutMS=500&connectTimeoutMS=500");
        LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES.getResource().invalidate(serviceName);
        LegacyEntityDataService down = LegacyEntityDataService.of(serviceName, unreachable, TestConnectionResource.getRedisConfig(),
                Duration.ofMinutes(30), List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()), Duration.ofMinutes(30));
        LegacyEntityDataService back = null;

        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData entity = LegacyEntityData.of(entityUuid, "TestEntity");
            entity.addAttribute("value", "while mongo was down");
            down.getL1Cache().getResource().put(entityUuid, entity);

            EntityDataPersistenceTask.of(LockSettings.of(5, 30, TimeUnit.SECONDS), down).start().get(60, TimeUnit.SECONDS);
            boolean stillWaiting = down.getL2Cache().getResource().getSet(EntityRKeyUtil.getPendingDatabaseKey(down))
                    .contains(entityUuid.toString());

            back = createServer(serviceName);
            EntityDataPersistenceTask.of(LockSettings.of(5, 30, TimeUnit.SECONDS), back).savedOnly().start().get(30, TimeUnit.SECONDS);
            LegacyEntityData persisted = back.getFromDatabase(entityUuid);
            boolean persistedLater = persisted != null && "while mongo was down".equals(persisted.getAttribute("value"));

            boolean success = stillWaiting && persistedLater;

            TestLogger.logValidation("player", "EntitiesWaitWhileMongoIsDown", success,
                    "Mongo down - stillWaiting: " + stillWaiting + ", persistedLater: " + persistedLater);

            return success;
        } catch (Exception exception) {
            TestLogger.logFailure("player", "Mongo down test failed: " + exception.getMessage());
            return false;
        } finally {
            down.getRedisStreamAcceptTask().cancel(false);
            down.getEntityDataPersistenceTimerTask().cancel(false);
            down.getL2Cache().shutdown();
            unreachable.close();
            if (back != null) {
                shutdownQuietly(back);
            }
        }
    }

    private static void awaitQuietly(CountDownLatch latch) {
        try {
            latch.await(5, TimeUnit.SECONDS);
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        }
    }

    private static LegacyEntityDataService createServer(String serviceName, EntitySyncSettings syncSettings, String basePackage) {
        // Only one service per name may be registered; each simulated server takes the place of the previous one
        LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES.getResource().invalidate(serviceName);

        return LegacyEntityDataService.of(
                serviceName,
                TestConnectionResource.getMongoConfig(),
                TestConnectionResource.getRedisConfig(),
                Duration.ofMinutes(30),
                List.of(basePackage),
                List.of(PlayerLauncher.class.getClassLoader()),
                ACCEPT_INTERVAL,
                LegacyEntityDataService.DEFAULT_TTL_DURATION,
                syncSettings
        );
    }

    private static LegacyEntityDataService createServer(String serviceName) {
        // Only one service per name may be registered; each simulated server takes the place of the previous one
        LegacyEntityDataService.LEGACY_ENTITY_DATA_SERVICES.getResource().invalidate(serviceName);

        return LegacyEntityDataService.of(
                serviceName,
                TestConnectionResource.getMongoConfig(),
                TestConnectionResource.getRedisConfig(),
                Duration.ofMinutes(30),
                List.of("net.legacy.library.player.task.redis.impl"),
                List.of(PlayerLauncher.class.getClassLoader()),
                ACCEPT_INTERVAL
        );
    }

    private static LegacyEntityData copyOf(LegacyEntityData entity) {
        // As another holder would have it: read back from the serialized form L2 keeps, stamps included
        return SimplixSerializer.deserialize(SimplixSerializer.serialize(entity).toString(), LegacyEntityData.class);
    }

    private static String state(LegacyEntityDataService service, UUID entityUuid) {
        LegacyEntityData entity = service.getEntityData(entityUuid);
        return entity == null ? "null" : entity.getAttribute("counter") + "/" + entity.getAttribute("edit");
    }

    private static RStream<Object, Object> stream(LegacyEntityDataService service) {
        return service.getL2Cache().getResource().getStream(EntityRKeyUtil.getEntityStreamKey(service));
    }

    private static Map<StreamMessageId, Map<Object, Object>> readMessages(LegacyEntityDataService service) {
        return stream(service).read(StreamReadArgs.greaterThan(StreamMessageId.ALL));
    }

    private static long countMessages(LegacyEntityDataService service, String publisher) {
        return readMessages(service).values().stream()
                .filter(message -> Objects.equals(publisher, String.valueOf(message.get("publisher"))))
                .count();
    }

    private static void addRemoteMessage(LegacyEntityDataService service, String publisher, EntityRStreamTask task) {
        // The same entries EntityRStreamPubTask writes on the other server
        Map<Object, Object> message = Map.of(
                "actionName", task.getActionName(),
                "data", task.getData(),
                "publisher", publisher,
                "timeout", String.valueOf(System.currentTimeMillis() + task.getExpirationTimeMillis()),
                "uuid", UUID.randomUUID().toString()
        );

        stream(service).add(StreamAddArgs.entries(message));
    }

    private static boolean awaitCondition(BooleanSupplier condition, long timeoutMillis) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(100);
        }
        return condition.getAsBoolean();
    }

    private static void shutdownQuietly(LegacyEntityDataService service) {
        // cancel() only stops these timers and is safe with the shared scheduler
        service.getRedisStreamAcceptTask().cancel(false);
        service.getEntityDataPersistenceTimerTask().cancel(false);
        try {
            service.shutdown();
        } catch (InterruptedException exception) {
            Thread.currentThread().interrupt();
        } catch (Exception exception) {
            TestLogger.logWarning("player", "Failed to shut down test service %s: %s", service.getName(), exception.getMessage());
        }
    }

}
