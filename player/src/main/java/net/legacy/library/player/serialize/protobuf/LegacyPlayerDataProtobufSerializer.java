package net.legacy.library.player.serialize.protobuf;

import com.google.protobuf.InvalidProtocolBufferException;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.model.proto.LegacyPlayerDataProto.LegacyPlayerDataMsg; // Hypothetical generated class

import java.util.UUID;

public class LegacyPlayerDataProtobufSerializer {

    // Convert from domain LegacyPlayerData to Protobuf LegacyPlayerDataMsg
    public static LegacyPlayerDataMsg toProtobuf(LegacyPlayerData domainData) {
        if (domainData == null) {
            return null;
        }
        return LegacyPlayerDataMsg.newBuilder()
                .setUuid(domainData.getUuid().toString())
                .putAllData(domainData.getData())
                .build();
    }

    // Convert from Protobuf LegacyPlayerDataMsg to domain LegacyPlayerData
    public static LegacyPlayerData fromProtobuf(LegacyPlayerDataMsg protoMsg) {
        if (protoMsg == null) {
            return null;
        }
        UUID uuid = UUID.fromString(protoMsg.getUuid());
        LegacyPlayerData domainData = new LegacyPlayerData(uuid); // Assuming constructor takes UUID
        domainData.getData().putAll(protoMsg.getDataMap()); // Use getDataMap() for proto3 maps
        return domainData;
    }

    // Serialize Protobuf LegacyPlayerDataMsg to byte[]
    public static byte[] serialize(LegacyPlayerDataMsg protoMsg) {
        if (protoMsg == null) {
            return null;
        }
        return protoMsg.toByteArray();
    }

    // Deserialize byte[] to Protobuf LegacyPlayerDataMsg
    public static LegacyPlayerDataMsg deserialize(byte[] bytes) throws InvalidProtocolBufferException {
        if (bytes == null || bytes.length == 0) {
            return null;
        }
        return LegacyPlayerDataMsg.parseFrom(bytes);
    }

    // Convenience method: domain LegacyPlayerData -> byte[]
    public static byte[] serializeDomainObject(LegacyPlayerData domainData) {
        LegacyPlayerDataMsg protoMsg = toProtobuf(domainData);
        return serialize(protoMsg);
    }

    // Convenience method: byte[] -> domain LegacyPlayerData
    public static LegacyPlayerData deserializeToDomainObject(byte[] bytes) throws InvalidProtocolBufferException {
        LegacyPlayerDataMsg protoMsg = deserialize(bytes);
        return fromProtobuf(protoMsg);
    }
}
