package net.legacy.library.player.serialize;

import de.leonhard.storage.internal.serialize.SimplixSerializable;
import lombok.NonNull;
import net.legacy.library.commons.util.GsonUtil;
import net.legacy.library.configuration.serialize.annotation.SimplixSerializerSerializableAutoRegister;
import net.legacy.library.player.model.LegacyEntityData;

/**
 * Serializer for {@link LegacyEntityData} objects using Gson.
 *
 * @author qwq-dev
 * @since 2024-03-30 01:49
 */
@SimplixSerializerSerializableAutoRegister
public class LegacyEntitySerializable implements SimplixSerializable<LegacyEntityData> {

    /**
     * {@inheritDoc}
     *
     * @param object {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ClassCastException {@inheritDoc}
     */
    @Override
    public LegacyEntityData deserialize(@NonNull Object object) throws ClassCastException {
        return GsonUtil.getGson().fromJson(object.toString(), LegacyEntityData.class);
    }

    /**
     * {@inheritDoc}
     *
     * @param legacyEntityData {@inheritDoc}
     * @return {@inheritDoc}
     * @throws ClassCastException {@inheritDoc}
     */
    @Override
    public Object serialize(@NonNull LegacyEntityData legacyEntityData) throws ClassCastException {
        return GsonUtil.getGson().toJson(legacyEntityData);
    }

    /**
     * {@inheritDoc}
     *
     * @return {@inheritDoc}
     */
    @Override
    public Class<LegacyEntityData> getClazz() {
        return LegacyEntityData.class;
    }

}