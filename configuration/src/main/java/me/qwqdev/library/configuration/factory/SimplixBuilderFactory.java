package me.qwqdev.library.configuration.factory;

import de.leonhard.storage.SimplixBuilder;
import de.leonhard.storage.internal.settings.ConfigSettings;
import de.leonhard.storage.internal.settings.DataType;
import de.leonhard.storage.internal.settings.ReloadSettings;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.nio.file.Path;

/**
 * Factory class for creating SimplixBuilder instances with predefined settings.
 * This class provides various methods to create SimplixBuilder instances from different sources.
 *
 * @author qwq-dev
 * @see de.leonhard.storage.SimplixBuilder
 * @since 2024-12-18 13:48
 */
@UtilityClass
public class SimplixBuilderFactory {
    /**
     * Creates a SimplixBuilder instance from a file.
     *
     * @param file the file to create the SimplixBuilder from
     * @return a configured SimplixBuilder instance
     */
    public static SimplixBuilder createSimplixBuilderFromFile(File file) {
        return configureSimplixBuilder(SimplixBuilder.fromFile(file));
    }

    /**
     * Creates a SimplixBuilder instance from a directory.
     *
     * @param file the directory to create the SimplixBuilder from
     * @return a configured SimplixBuilder instance
     */
    public static SimplixBuilder createSimplixBuilderFromDirectory(File file) {
        return configureSimplixBuilder(SimplixBuilder.fromDirectory(file));
    }

    /**
     * Creates a SimplixBuilder instance from a path.
     *
     * @param path the path to create the SimplixBuilder from
     * @return a configured SimplixBuilder instance
     */
    public static SimplixBuilder createSimplixBuilderFromPath(Path path) {
        return configureSimplixBuilder(SimplixBuilder.fromPath(path));
    }

    /**
     * Creates a SimplixBuilder instance from a name and path.
     *
     * @param name the name of the file
     * @param path the path to the file
     * @return a configured SimplixBuilder instance
     */
    public static SimplixBuilder createSimplixBuilder(String name, String path) {
        return configureSimplixBuilder(SimplixBuilder.fromPath(name, path));
    }

    private static SimplixBuilder configureSimplixBuilder(SimplixBuilder builder) {
        return builder.setDataType(DataType.SORTED)
                .setConfigSettings(ConfigSettings.PRESERVE_COMMENTS)
                .setReloadSettings(ReloadSettings.AUTOMATICALLY);
    }
}