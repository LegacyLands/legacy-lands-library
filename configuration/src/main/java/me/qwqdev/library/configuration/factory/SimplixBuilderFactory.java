package me.qwqdev.library.configuration.factory;

import de.leonhard.storage.SimplixBuilder;
import de.leonhard.storage.internal.settings.ConfigSettings;
import de.leonhard.storage.internal.settings.DataType;
import de.leonhard.storage.internal.settings.ReloadSettings;
import lombok.experimental.UtilityClass;

import java.io.File;
import java.nio.file.Path;

/**
 * A factory class for creating {@link SimplixBuilder} instances with predefined settings.
 *
 * <p>This class provides various methods to create {@link SimplixBuilder} instances from different sources, such as files, directories, or paths.
 * It configures the builder with default settings for data type, config settings, and reload behavior.
 *
 * @author qwq-dev
 * @see SimplixBuilder
 * @since 2024-12-18 13:48
 */
@UtilityClass
public class SimplixBuilderFactory {
    /**
     * Creates a {@link SimplixBuilder} instance from a file.
     *
     * <p>This method reads the specified file and configures the builder with default settings.
     *
     * @param file the file to create the {@link SimplixBuilder} from
     * @return a configured {@link SimplixBuilder} instance
     */
    public static SimplixBuilder createSimplixBuilderFromFile(File file) {
        return configureSimplixBuilder(SimplixBuilder.fromFile(file));
    }

    /**
     * Creates a {@link SimplixBuilder} instance from a directory.
     *
     * <p>This method reads the specified directory and configures the builder with default settings.
     *
     * @param file the directory to create the {@link SimplixBuilder} from
     * @return a configured {@link SimplixBuilder} instance
     */
    public static SimplixBuilder createSimplixBuilderFromDirectory(File file) {
        return configureSimplixBuilder(SimplixBuilder.fromDirectory(file));
    }

    /**
     * Creates a {@link SimplixBuilder} instance from a path.
     *
     * <p>This method reads the specified path and configures the builder with default settings.
     *
     * @param path the path to create the {@link SimplixBuilder} from
     * @return a configured {@link SimplixBuilder} instance
     */
    public static SimplixBuilder createSimplixBuilderFromPath(Path path) {
        return configureSimplixBuilder(SimplixBuilder.fromPath(path));
    }

    /**
     * Creates a {@link SimplixBuilder} instance from a name and path.
     *
     * <p>This method reads the specified file using the given name and path and configures the builder with default settings.
     *
     * @param name the name of the file
     * @param path the path to the file
     * @return a configured {@link SimplixBuilder} instance
     */
    public static SimplixBuilder createSimplixBuilder(String name, String path) {
        return configureSimplixBuilder(SimplixBuilder.fromPath(name, path));
    }

    /**
     * Configures the {@link SimplixBuilder} with default settings for data type, config settings, and reload behavior.
     *
     * @param builder the {@link SimplixBuilder} instance to configure
     * @return the configured {@link SimplixBuilder} instance
     */
    private static SimplixBuilder configureSimplixBuilder(SimplixBuilder builder) {
        return builder.setDataType(DataType.SORTED)
                .setConfigSettings(ConfigSettings.PRESERVE_COMMENTS)
                .setReloadSettings(ReloadSettings.AUTOMATICALLY);
    }
}
