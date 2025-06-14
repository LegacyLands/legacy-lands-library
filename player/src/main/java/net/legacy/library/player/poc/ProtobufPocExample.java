package net.legacy.library.player.poc;

import com.google.protobuf.InvalidProtocolBufferException;
import net.legacy.library.player.model.LegacyPlayerData;
import net.legacy.library.player.serialize.protobuf.LegacyPlayerDataProtobufSerializer;

import java.util.UUID;
import java.util.Arrays;

public class ProtobufPocExample {

    public static void main(String[] args) {
        // 1. Create a sample LegacyPlayerData object
        UUID playerUuid = UUID.randomUUID();
        LegacyPlayerData originalData = new LegacyPlayerData(playerUuid);
        originalData.addData("level", "10");
        originalData.addData("gold", "500");
        originalData.addData("location", "world:100,200,300");

        System.out.println("Original LegacyPlayerData: " + originalData.getUuid());
        originalData.getData().forEach((k, v) -> System.out.println(k + ": " + v));

        // 2. Serialize to byte[]
        byte[] serializedBytes = LegacyPlayerDataProtobufSerializer.serializeDomainObject(originalData);
        System.out.println("\nSerialized byte array: " + Arrays.toString(serializedBytes));
        System.out.println("Serialized size: " + serializedBytes.length + " bytes");

        // 3. Deserialize from byte[]
        LegacyPlayerData deserializedData = null;
        try {
            deserializedData = LegacyPlayerDataProtobufSerializer.deserializeToDomainObject(serializedBytes);
        } catch (InvalidProtocolBufferException e) {
            System.err.println("Failed to deserialize: " + e.getMessage());
            return;
        }

        // 4. Verify
        if (deserializedData != null) {
            System.out.println("\nDeserialized LegacyPlayerData: " + deserializedData.getUuid());
            deserializedData.getData().forEach((k, v) -> System.out.println(k + ": " + v));

            boolean isEqual = originalData.getUuid().equals(deserializedData.getUuid()) &&
                              originalData.getData().equals(deserializedData.getData());
            System.out.println("\nData integrity check (UUID and data map are equal): " + isEqual);
        }
    }
}
