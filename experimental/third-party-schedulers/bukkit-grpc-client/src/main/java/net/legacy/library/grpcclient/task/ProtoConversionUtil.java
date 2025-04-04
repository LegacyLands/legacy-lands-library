package net.legacy.library.grpcclient.task;

import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.BytesValue;
import com.google.protobuf.DoubleValue;
import com.google.protobuf.FloatValue;
import com.google.protobuf.Int32Value;
import com.google.protobuf.Int64Value;
import com.google.protobuf.StringValue;
import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.util.List;
import java.util.Map;

/**
 * Utility class for converting common Java types to Protobuf {@link Any} messages.
 * Supports basic types, Lists, and Maps (string keys only).
 *
 * @author qwq-dev
 * @since 2025-4-4 16:20
 */
@UtilityClass
public class ProtoConversionUtil {
    /**
     * Converts a general Java object into a Protobuf {@link Any} message suitable for task arguments.
     *
     * <p>This method uses pattern matching to handle different types:
     * <ul>
     *   <li>Primitive wrappers (Integer, Long, Boolean, Float, Double) are packed into their corresponding Protobuf wrapper types (e.g., {@link Int32Value}).</li>
     *   <li>{@link String} is packed into {@link StringValue}.</li>
     *   <li>{@code byte[]} is packed into {@link BytesValue}.</li>
     *   <li>{@link List} is converted to a custom {@link taskscheduler.TaskSchedulerOuterClass.ListValue} via {@link #convertListToCustomProtoAny(List)} and then packed.</li>
     *   <li>{@link Map} with String keys is converted to a custom {@link taskscheduler.TaskSchedulerOuterClass.MapValue} via {@link #convertMapToCustomProtoAny(Map)} and then packed.</li>
     *   <li>{@code null} is represented by an empty {@code Any}.</li>
     *   <li>Other types are packed as {@link StringValue} using their {@code toString()} representation, with a warning log.</li>
     * </ul>
     *
     * @param value the Java object to convert
     * @return an {@link Any} message containing the packed representation of the value
     */
    public static Any convertToProtoAny(Object value) {
        return switch (value) {
            case null -> Any.newBuilder().build();
            case String s -> Any.pack(StringValue.of(s));
            case Integer i -> Any.pack(Int32Value.of(i));
            case Long l -> Any.pack(Int64Value.of(l));
            case Boolean b -> Any.pack(BoolValue.of(b));
            case Float f -> Any.pack(FloatValue.of(f));
            case Double d -> Any.pack(DoubleValue.of(d));
            case byte[] bytes -> Any.pack(BytesValue.of(ByteString.copyFrom(bytes)));
            case List<?> listValue -> convertListToCustomProtoAny(listValue);
            case Map<?, ?> mapValue -> {
                boolean allKeysAreStrings = mapValue.keySet().stream().allMatch(k -> k instanceof String);
                if (allKeysAreStrings) {
                    @SuppressWarnings("unchecked")
                    Map<String, ?> stringKeyMap = (Map<String, ?>) mapValue;
                    yield convertMapToCustomProtoAny(stringKeyMap);
                } else {
                    Log.warn("Map contains non-String keys, packing as StringValue using toString(). This will likely fail on Rust side.");
                    yield Any.pack(StringValue.of(value.toString()));
                }
            }
            default -> {
                Log.warn("Packing unknown type (%s) as StringValue using toString(). This will likely fail on Rust side.", value.getClass().getName());
                yield Any.pack(StringValue.of(value.toString()));
            }
        };
    }

    /**
     * Converts a Java {@link List} into an {@link Any} containing a custom {@code taskscheduler.ListValue}.
     * <p>Each element in the list is recursively converted using {@link #convertToProtoAny(Object)}.
     *
     * @param list the Java List to convert. Must not be null
     * @return an {@link Any} message containing the packed {@code taskscheduler.ListValue}
     */
    public static Any convertListToCustomProtoAny(List<?> list) {
        taskscheduler.TaskSchedulerOuterClass.ListValue.Builder listBuilder = taskscheduler.TaskSchedulerOuterClass.ListValue.newBuilder();
        for (Object item : list) {
            listBuilder.addValues(convertToProtoAny(item));
        }
        return Any.pack(listBuilder.build());
    }

    /**
     * Converts a Java {@link Map} with String keys into an {@link Any} containing a custom {@code taskscheduler.MapValue}.
     * <p>Each value in the map is recursively converted using {@link #convertToProtoAny(Object)}.
     *
     * @param map the Java Map to convert. Must not be null, and keys must be Strings
     * @return an {@link Any} message containing the packed {@code taskscheduler.MapValue}
     */
    public static Any convertMapToCustomProtoAny(Map<String, ?> map) {
        taskscheduler.TaskSchedulerOuterClass.MapValue.Builder mapBuilder = taskscheduler.TaskSchedulerOuterClass.MapValue.newBuilder();
        for (Map.Entry<String, ?> entry : map.entrySet()) {
            mapBuilder.putFields(entry.getKey(), convertToProtoAny(entry.getValue()));
        }
        return Any.pack(mapBuilder.build());
    }
} 