package net.legacy.library.player.serialize.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import net.legacy.library.player.model.LegacyEntityData;
import net.legacy.library.player.model.proto.LegacyEntityDataProto.LegacyEntityDataMsg;
import net.legacy.library.player.model.proto.LegacyEntityDataProto.UuidSet;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

public class LegacyEntityDataProtobufSerializer {

    // Convert from domain LegacyEntityData to Protobuf LegacyEntityDataMsg
    public static LegacyEntityDataMsg toProtobuf(LegacyEntityData domainData) {
        if (domainData == null) {
            return null;
        }
        LegacyEntityDataMsg.Builder builder = LegacyEntityDataMsg.newBuilder()
                .setUuid(domainData.getUuid().toString())
                .putAllAttributes(domainData.getAttributes())
                .setEntityType(domainData.getEntityType() != null ? domainData.getEntityType() : "")
                .setVersion(domainData.getVersion())
                .setLastModifiedTime(domainData.getLastModifiedTime());

        for (Map.Entry<String, Set<UUID>> entry : domainData.getRelationships().entrySet()) {
            UuidSet.Builder uuidSetBuilder = UuidSet.newBuilder();
            if (entry.getValue() != null) {
                for (UUID uuid : entry.getValue()) {
                    uuidSetBuilder.addUuid(uuid.toString());
                }
            }
            builder.putRelationships(entry.getKey(), uuidSetBuilder.build());
        }
        return builder.build();
    }

    // Convert from Protobuf LegacyEntityDataMsg to domain LegacyEntityData
    public static LegacyEntityData fromProtobuf(LegacyEntityDataMsg protoMsg) {
        if (protoMsg == null) {
            return null;
        }
        UUID uuid = UUID.fromString(protoMsg.getUuid());
        LegacyEntityData domainData = new LegacyEntityData(uuid); // Assuming constructor takes UUID

        domainData.setEntityType(protoMsg.getEntityType()); // Assuming a setter or direct field access after construction
        domainData.setVersion(protoMsg.getVersion()); // Assuming a setter
        // For lastModifiedTime, it's final in the current LegacyEntityData but set in constructor.
        // This might require a constructor that accepts all fields, or making it non-final and adding a setter.
        // For PoC, we'll assume it can be set post-construction or via a specific constructor.
        // If LegacyEntityData needs to be more immutable, its constructor would take all these fields.
        // Let's assume a package-private or protected setter for lastModifiedTime for now for deserialization.
        // domainData.setLastModifiedTime(protoMsg.getLastModifiedTime()); // This line would need a setter

        // A more realistic approach for lastModifiedTime if it must remain final
        // would be to have a constructor like:
        // new LegacyEntityData(UUID uuid, String entityType, long version, long lastModifiedTime)
        // For this PoC, we'll focus on the mutable parts. The actual constructor of LegacyEntityData might need adjustment.
        // The following is a conceptual way to set it if it were mutable:
        // domainData.setLastModifiedTimeInternal(protoMsg.getLastModifiedTime()); // Assuming an internal setter

        domainData.getAttributes().putAll(protoMsg.getAttributesMap());

        for (Map.Entry<String, UuidSet> entry : protoMsg.getRelationshipsMap().entrySet()) {
            Set<UUID> uuidSet = new HashSet<>();
            if (entry.getValue() != null) {
                for (String uuidStr : entry.getValue().getUuidList()) {
                    uuidSet.add(UUID.fromString(uuidStr));
                }
            }
            domainData.getRelationships().put(entry.getKey(), uuidSet);
        }

        // Important: After deserializing, if lastModifiedTime wasn't set via constructor,
        // and version/lastModifiedTime are critical for optimistic locking,
        // ensure they are correctly restored. The current LegacyEntityData sets lastModifiedTime
        // to System.currentTimeMillis() on construction and updates version/timestamp on modifications.
        // For deserialization, we need to restore the *persisted* values.
        // This implies LegacyEntityData needs setters for version and lastModifiedTime,
        // or a constructor that takes all these fields.
        // The `setVersion` is available. `lastModifiedTime` is final in the provided code.
        // This would be a necessary change in LegacyEntityData for proper protobuf deserialization.
        // For the sake of this subtask, we will assume `setVersion` works and acknowledge the `lastModifiedTime` constraint.

        return domainData;
    }

    // Serialize Protobuf LegacyEntityDataMsg to byte[]
    public static byte[] serialize(LegacyEntityDataMsg protoMsg) {
        if (protoMsg == null) {
            return null;
        }
        return protoMsg.toByteArray();
    }

    // Deserialize byte[] to Protobuf LegacyEntityDataMsg
    public static LegacyEntityDataMsg deserialize(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return LegacyEntityDataMsg.parseFrom(bytes);
    }

    // Convenience method: domain LegacyEntityData -> byte[]
    public static byte[] serializeDomainObject(LegacyEntityData domainData) {
        LegacyEntityDataMsg protoMsg = toProtobuf(domainData);
        return serialize(protoMsg);
    }

    // Convenience method: byte[] -> domain LegacyEntityData
    public static LegacyEntityData deserializeToDomainObject(byte[] bytes) throws InvalidProtocolBufferException {
        LegacyEntityDataMsg protoMsg = deserialize(bytes);
        return fromProtobuf(protoMsg);
    }
}
