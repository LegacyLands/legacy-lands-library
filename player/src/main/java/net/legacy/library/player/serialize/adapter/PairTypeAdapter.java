package net.legacy.library.player.serialize.adapter;

import com.google.gson.JsonDeserializationContext;
import com.google.gson.JsonDeserializer;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import com.google.gson.JsonSerializationContext;
import com.google.gson.JsonSerializer;
import net.legacy.library.player.annotation.TypeAdapterRegister;
import org.apache.commons.lang3.tuple.Pair;

import java.lang.reflect.Type;

/**
 * @author qwq-dev
 * @since 2025-01-05 19:07
 */
@TypeAdapterRegister(classType = Pair.class)
public class PairTypeAdapter implements JsonSerializer<Pair<?, ?>>, JsonDeserializer<Pair<?, ?>> {
    @Override
    public JsonElement serialize(Pair<?, ?> src, Type typeOfSrc, JsonSerializationContext context) {
        JsonObject jsonObject = new JsonObject();
        jsonObject.add("left", context.serialize(src.getKey()));
        jsonObject.add("right", context.serialize(src.getValue()));
        return jsonObject;
    }

    @Override
    public Pair<?, ?> deserialize(JsonElement json, Type typeOfT, JsonDeserializationContext context) throws JsonParseException {
        JsonObject jsonObject = json.getAsJsonObject();
        Object key = context.deserialize(jsonObject.get("left"), Object.class);
        Object value = context.deserialize(jsonObject.get("right"), Object.class);
        return Pair.of(key, value);
    }
}