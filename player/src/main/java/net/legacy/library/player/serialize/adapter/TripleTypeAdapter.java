package net.legacy.library.player.serialize.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.legacy.library.player.annotation.TypeAdapterRegister;
import org.apache.commons.lang3.tuple.Triple;

import java.lang.reflect.ParameterizedType;
import java.lang.reflect.Type;
import java.lang.reflect.TypeVariable;
import java.lang.reflect.WildcardType;

/**
 * Custom Gson type adapter for serializing and deserializing {@link Triple} objects.
 *
 * @author qwq-dev
 * @since 2025-04-11 17:18
 */
@TypeAdapterRegister(classType = Triple.class)
public class TripleTypeAdapter implements JsonSerializer<Triple<?, ?, ?>>, JsonDeserializer<Triple<?, ?, ?>> {

    /**
     * {@inheritDoc}
     *
     * @param src       {@inheritDoc}
     * @param typeOfSrc {@inheritDoc}
     * @param context   {@inheritDoc}
     * @return {@inheritDoc}
     */
    @Override
    public JsonElement serialize(Triple<?, ?, ?> src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("left", context.serialize(src.getLeft()));
        jsonObject.add("middle", context.serialize(src.getMiddle()));
        jsonObject.add("right", context.serialize(src.getRight()));
        return jsonObject;
    }

    /**
     * {@inheritDoc}
     *
     * @param json    {@inheritDoc}
     * @param typeOfT {@inheritDoc}
     * @param context {@inheritDoc}
     * @return {@inheritDoc}
     * @throws JsonParseException {@inheritDoc}
     */
    @Override
    public Triple<?, ?, ?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject jsonObject = json.getAsJsonObject();
        Object left = context.deserialize(jsonObject.get("left"), elementType(typeOfT, 0));
        Object middle = context.deserialize(jsonObject.get("middle"), elementType(typeOfT, 1));
        Object right = context.deserialize(jsonObject.get("right"), elementType(typeOfT, 2));
        return Triple.of(left, middle, right);
    }

    /**
     * Resolves the declared type of one element of the tuple, so that a caller asking for
     * {@code Triple<String, Map<String, String>, Long>} receives a {@link Long} rather than the {@link Double} Gson
     * produces for a number read as {@link Object}. A raw tuple type keeps every element as {@link Object}.
     *
     * @param typeOfT the type being deserialized, parameterized or raw
     * @param index   the position of the element among the type arguments
     * @return the declared type argument at {@code index}, or {@link Object} if none is declared
     */
    private static Type elementType(Type typeOfT, int index) {
        if (typeOfT instanceof ParameterizedType parameterizedType) {
            Type[] arguments = parameterizedType.getActualTypeArguments();
            if (index < arguments.length && !(arguments[index] instanceof WildcardType) && !(arguments[index] instanceof TypeVariable)) {
                return arguments[index];
            }
        }
        return Object.class;
    }

}
