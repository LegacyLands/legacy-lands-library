package net.legacy.library.player.serialize;

import de.leonhard.storage.internal.serialize.SimplixSerializable;
import lombok.NonNull;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.configuration.serialize.annotation.SimplixSerializerSerializableAutoRegister;
import net.legacy.library.player.model.LegacyPlayerData;

/**
 * Serializer for {@link LegacyPlayerData} objects using Gson.
 *
 * @author qwq-dev
 * @since 2025-01-03 15:12
 */
@SimplixSerializerSerializableAutoRegister
public class LegacyPlayerSerializable implements SimplixSerializable<LegacyPlayerData> {
    /**
     * {@inheritDoc}
     *
     * @param object {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ClassCastException {@inheritDoc}
     */
    @Override
    public LegacyPlayerData deserialize(@NonNull Object object) throws ClassCastException {
        return GsonUtil.getGson().fromJson(object.toString(), LegacyPlayerData.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param legacyPlayerData {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ClassCastException {@inheritDoc}
     */
    @Override
    public Object serialize(@NonNull LegacyPlayerData legacyPlayerData) throws ClassCastException {
        return GsonUtil.getGson().toJson(legacyPlayerData);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public Class<LegacyPlayerData> getClazz() {
        return LegacyPlayerData.class;
    }
}
