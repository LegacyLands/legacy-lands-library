package net.legacy.library.player.test;

import com.google.protobuf.InvalidProtocolBufferException;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.serialize.protobuf.LegacyPlayerDataProtobufSerializer;
import net.legacy.library.player.serialize.protobuf.LegacyEntityDataProtobufSerializer;

import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

@ModuleTest(
        testName = "protobuf-serialization-test",
        description = "Tests Protocol Buffer serialization and deserialization for player and entity data.",
        tags = {"player", "entity", "protobuf", "serialization"},
        priority = 1, // Higher priority as it tests core serialization logic
        timeout = 5000, // 5 seconds
        expectedResult = "SUCCESS"
)
public class ProtobufSerializationTest {

    private static final String MODULE_NAME = "player-serialization";

    public static boolean testPlayerProtoSerializationCycle() {
        TestLogger.logInfo(MODULE_NAME, "Starting testPlayerProtoSerializationCycle...");
        try {
            UUID playerUuid = UUID.randomUUID();
            LegacyPlayerData originalData = new LegacyPlayerData(playerUuid);
            originalData.addData("level", "15");
            originalData.addData("gold", "1200");
            originalData.addData("class", "Warrior");

            byte[] serializedBytes = LegacyPlayerDataProtobufSerializer.serializeDomainObject(originalData);
            if (serializedBytes == null || serializedBytes.length == 0) {
                TestLogger.logFailure(MODULE_NAME, "testPlayerProtoSerializationCycle: Serialization resulted in null or empty byte array.");
                return false;
            }

            LegacyPlayerData deserializedData = LegacyPlayerDataProtobufSerializer.deserializeToDomainObject(serializedBytes);
            if (deserializedData == null) {
                TestLogger.logFailure(MODULE_NAME, "testPlayerProtoSerializationCycle: Deserialization resulted in null object.");
                return false;
            }

            boolean uuidEqual = originalData.getUuid().equals(deserializedData.getUuid());
            boolean dataEqual = originalData.getData().equals(deserializedData.getData());

            if (uuidEqual && dataEqual) {
                TestLogger.logInfo(MODULE_NAME, "testPlayerProtoSerializationCycle: PASSED. Original and deserialized LegacyPlayerData are equal.");
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "testPlayerProtoSerializationCycle: FAILED. Data mismatch."
                        + "\n  UUIDs equal: " + uuidEqual
                        + "\n  Data maps equal: " + dataEqual
                        + "\n  Original Data: " + originalData.getData()
                        + "\n  Deserialized Data: " + deserializedData.getData());
                return false;
            }
        } catch (InvalidProtocolBufferException e) {
            TestLogger.logFailure(MODULE_NAME, "testPlayerProtoSerializationCycle: FAILED with InvalidProtocolBufferException - " + e.getMessage());
            return false;
        } catch (Exception e) {
            TestLogger.logFailure(MODULE_NAME, "testPlayerProtoSerializationCycle: FAILED with unexpected exception - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static boolean testEntityProtoSerializationCycle() {
        TestLogger.logInfo(MODULE_NAME, "Starting testEntityProtoSerializationCycle...");
        try {
            UUID entityUuid = UUID.randomUUID();
            // Note: LegacyEntityData constructor sets lastModifiedTime to System.currentTimeMillis()
            // and version to 1 initially. For a true cycle test where these are preserved,
            // LegacyEntityData would need a constructor that accepts these, or setters.
            // We will set them manually in originalData for comparison basis.
            LegacyEntityData originalData = new LegacyEntityData(entityUuid);
            originalData.setEntityType("MOB_GOBLIN");
            originalData.setVersion(5); // Manually set version for test
            // originalData.setLastModifiedTimeInternal(1234567890L); // Conceptual: if an internal setter existed

            originalData.addAttribute("health", "100");
            originalData.addAttribute("mana", "50");

            Set<UUID> allies = new HashSet<>();
            allies.add(UUID.randomUUID());
            allies.add(UUID.randomUUID());
            originalData.addRelationship("allies", allies.iterator().next()); // addRelationship adds one by one
            originalData.addRelationship("allies", allies.stream().reduce((first, second) -> second).orElse(null));


            byte[] serializedBytes = LegacyEntityDataProtobufSerializer.serializeDomainObject(originalData);
            if (serializedBytes == null || serializedBytes.length == 0) {
                TestLogger.logFailure(MODULE_NAME, "testEntityProtoSerializationCycle: Serialization resulted in null or empty byte array.");
                return false;
            }

            LegacyEntityData deserializedData = LegacyEntityDataProtobufSerializer.deserializeToDomainObject(serializedBytes);
            if (deserializedData == null) {
                TestLogger.logFailure(MODULE_NAME, "testEntityProtoSerializationCycle: Deserialization resulted in null object.");
                return false;
            }

            boolean uuidEqual = originalData.getUuid().equals(deserializedData.getUuid());
            boolean attributesEqual = originalData.getAttributes().equals(deserializedData.getAttributes());
            boolean relationshipsEqual = originalData.getRelationships().equals(deserializedData.getRelationships());
            boolean entityTypeEqual = originalData.getEntityType().equals(deserializedData.getEntityType());
            boolean versionEqual = originalData.getVersion() == deserializedData.getVersion();
            // boolean lastModifiedTimeEqual = originalData.getLastModifiedTime() == deserializedData.getLastModifiedTime();
            // lastModifiedTime is final and set on construction in current LegacyEntityData.
            // The serializer doesn't set it back from proto. This would require changes in LegacyEntityData.
            // So, we acknowledge this limitation for the PoC.

            if (uuidEqual && attributesEqual && relationshipsEqual && entityTypeEqual && versionEqual) {
                TestLogger.logInfo(MODULE_NAME, "testEntityProtoSerializationCycle: PASSED. Original and deserialized LegacyEntityData are equal (excluding lastModifiedTime).");
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "testEntityProtoSerializationCycle: FAILED. Data mismatch."
                        + "\n  UUIDs equal: " + uuidEqual
                        + "\n  Attributes equal: " + attributesEqual
                        + "\n  Relationships equal: " + relationshipsEqual
                        + "\n  EntityType equal: " + entityTypeEqual
                        + "\n  Version equal: " + versionEqual
                        // + "\n  LastModifiedTime equal: " + lastModifiedTimeEqual
                        + "\n  Original Attributes: " + originalData.getAttributes()
                        + "\n  Deserialized Attributes: " + deserializedData.getAttributes()
                        + "\n  Original Relationships: " + originalData.getRelationships()
                        + "\n  Deserialized Relationships: " + deserializedData.getRelationships());
                return false;
            }
        } catch (InvalidProtocolBufferException e) {
            TestLogger.logFailure(MODULE_NAME, "testEntityProtoSerializationCycle: FAILED with InvalidProtocolBufferException - " + e.getMessage());
            return false;
        } catch (Exception e) {
            TestLogger.logFailure(MODULE_NAME, "testEntityProtoSerializationCycle: FAILED with unexpected exception - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static boolean testEmptyPlayerDataSerialization() {
        TestLogger.logInfo(MODULE_NAME, "Starting testEmptyPlayerDataSerialization...");
        try {
            UUID playerUuid = UUID.randomUUID();
            LegacyPlayerData originalData = new LegacyPlayerData(playerUuid); // Data map is empty by default

            byte[] serializedBytes = LegacyPlayerDataProtobufSerializer.serializeDomainObject(originalData);
            if (serializedBytes == null || serializedBytes.length == 0) { // an empty map will still produce some bytes for uuid
                TestLogger.logFailure(MODULE_NAME, "testEmptyPlayerDataSerialization: Serialization resulted in null or unexpectedly zero byte array.");
                return false;
            }

            LegacyPlayerData deserializedData = LegacyPlayerDataProtobufSerializer.deserializeToDomainObject(serializedBytes);
            if (deserializedData == null) {
                TestLogger.logFailure(MODULE_NAME, "testEmptyPlayerDataSerialization: Deserialization resulted in null object.");
                return false;
            }

            boolean uuidEqual = originalData.getUuid().equals(deserializedData.getUuid());
            boolean dataEmptyAndEqual = deserializedData.getData() != null && deserializedData.getData().isEmpty() &&
                                        originalData.getData().isEmpty();

            if (uuidEqual && dataEmptyAndEqual) {
                TestLogger.logInfo(MODULE_NAME, "testEmptyPlayerDataSerialization: PASSED. Empty LegacyPlayerData serialized and deserialized correctly.");
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "testEmptyPlayerDataSerialization: FAILED. Data mismatch or not empty."
                        + "\n  UUIDs equal: " + uuidEqual
                        + "\n  Data empty and equal: " + dataEmptyAndEqual);
                return false;
            }
        } catch (InvalidProtocolBufferException e) {
            TestLogger.logFailure(MODULE_NAME, "testEmptyPlayerDataSerialization: FAILED with InvalidProtocolBufferException - " + e.getMessage());
            return false;
        } catch (Exception e) {
            TestLogger.logFailure(MODULE_NAME, "testEmptyPlayerDataSerialization: FAILED with unexpected exception - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }

    public static boolean testEmptyEntityDataSerialization() {
        TestLogger.logInfo(MODULE_NAME, "Starting testEmptyEntityDataSerialization...");
        try {
            UUID entityUuid = UUID.randomUUID();
            LegacyEntityData originalData = new LegacyEntityData(entityUuid);
            originalData.setEntityType("EMPTY_TYPE");
            // Version and lastModifiedTime are set by constructor.

            byte[] serializedBytes = LegacyEntityDataProtobufSerializer.serializeDomainObject(originalData);
             if (serializedBytes == null || serializedBytes.length == 0) {
                TestLogger.logFailure(MODULE_NAME, "testEmptyEntityDataSerialization: Serialization resulted in null or unexpectedly zero byte array.");
                return false;
            }

            LegacyEntityData deserializedData = LegacyEntityDataProtobufSerializer.deserializeToDomainObject(serializedBytes);
            if (deserializedData == null) {
                TestLogger.logFailure(MODULE_NAME, "testEmptyEntityDataSerialization: Deserialization resulted in null object.");
                return false;
            }

            boolean uuidEqual = originalData.getUuid().equals(deserializedData.getUuid());
            boolean attributesEmptyAndEqual = deserializedData.getAttributes() != null && deserializedData.getAttributes().isEmpty() &&
                                              originalData.getAttributes().isEmpty();
            boolean relationshipsEmptyAndEqual = deserializedData.getRelationships() != null && deserializedData.getRelationships().isEmpty() &&
                                                 originalData.getRelationships().isEmpty();
            boolean entityTypeEqual = originalData.getEntityType().equals(deserializedData.getEntityType());
            boolean versionEqual = originalData.getVersion() == deserializedData.getVersion();


            if (uuidEqual && attributesEmptyAndEqual && relationshipsEmptyAndEqual && entityTypeEqual && versionEqual) {
                TestLogger.logInfo(MODULE_NAME, "testEmptyEntityDataSerialization: PASSED. Empty LegacyEntityData serialized and deserialized correctly (excluding lastModifiedTime).");
                return true;
            } else {
                TestLogger.logFailure(MODULE_NAME, "testEmptyEntityDataSerialization: FAILED. Data mismatch or not empty."
                        + "\n  UUIDs equal: " + uuidEqual
                        + "\n  Attributes empty and equal: " + attributesEmptyAndEqual
                        + "\n  Relationships empty and equal: " + relationshipsEmptyAndEqual
                        + "\n  EntityType equal: " + entityTypeEqual
                        + "\n  Version equal: " + versionEqual);
                return false;
            }
        } catch (InvalidProtocolBufferException e) {
            TestLogger.logFailure(MODULE_NAME, "testEmptyEntityDataSerialization: FAILED with InvalidProtocolBufferException - " + e.getMessage());
            return false;
        } catch (Exception e) {
            TestLogger.logFailure(MODULE_NAME, "testEmptyEntityDataSerialization: FAILED with unexpected exception - " + e.getMessage());
            e.printStackTrace();
            return false;
        }
    }
}
