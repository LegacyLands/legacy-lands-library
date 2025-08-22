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

import java.lang.reflect.Type;

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
        Object left = context.deserialize(jsonObject.get("left"), Object.class);
        Object middle = context.deserialize(jsonObject.get("middle"), Object.class);
        Object right = context.deserialize(jsonObject.get("right"), Object.class);
        return Triple.of(left, middle, right);
    }

}