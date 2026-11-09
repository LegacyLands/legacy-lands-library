package net.legacy.library.configuration.migration;

import de.leonhard.storage.internal.FlatFile;
import de.leonhard.storage.internal.editor.yaml.SimpleYamlReader;
import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class for migrating a SimplixStorage file, such as one created with
 * {@link net.legacy.library.configuration.factory.SimplixBuilderFactory}.
 *
 * <p>The file is read and written through SimplixStorage itself, so comments survive when the file was built with
 * {@code ConfigSettings.PRESERVE_COMMENTS}, as the factory builds it. SimplixStorage reads every scalar as a
 * {@link String} and keeps a key written with nothing after it as {@code null}; baselines must be read with
 * {@link #readResource(ClassLoader, String)} so they compare the same way.
 *
 * <p>Nothing here throws: a file that cannot be backed up or migrated is left as it was and the failure is logged.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class SimplixMigrationUtil {

    /**
     * Migrates a SimplixStorage file in place.
     *
     * @param flatFile  the file
     * @param migration the migration to apply
     * @return what was done
     * @see #migrate(FlatFile, ConfigurationMigration, Path)
     */
    public static MigrationResult migrate(FlatFile flatFile, ConfigurationMigration migration) {
        return migrate(flatFile, migration, null);
    }

    /**
     * Migrates a SimplixStorage file in place, remembering added paths in a record so a path an operator deletes after
     * it was added is never added again.
     *
     * <p>When the file changes, it is first copied to a backup beside it, as {@link YamlMigrationUtil} does, and
     * nothing is written unless that copy succeeds. The migrated values then replace the file's data and are written
     * by SimplixStorage.
     *
     * @param flatFile        the file
     * @param migration       the migration to apply
     * @param insertionRecord the record of added paths, or {@code null} for none
     * @return what was done; untouched when the file is empty or could not be written
     */
    public static MigrationResult migrate(FlatFile flatFile, ConfigurationMigration migration, Path insertionRecord) {
        Path file = flatFile.getFile().toPath();
        String fileName = file.getFileName().toString();
        Map<String, Object> tree = ConfigurationTreeUtil.copyTree(flatFile.getData());
        if (tree.isEmpty()) {
            return MigrationResult.untouched(-1);
        }

        MigrationResult result = MigrationFiles.apply(migration, tree, insertionRecord, fileName);
        if (result == null) {
            return MigrationResult.untouched(-1);
        }

        if (!result.isChanged()) {
            MigrationFiles.rememberAdditions(insertionRecord, fileName, result);
            return result;
        }

        Path backup;
        try {
            backup = MigrationFiles.backup(file, MigrationFiles.labelOf(result));
            flatFile.getFileData().clear();
            flatFile.getFileData().loadData(tree);
            flatFile.write();
        } catch (Exception exception) {
            Log.error("[ConfigurationMigration] %s could not be migrated, so it was left as it was", exception, fileName);
            flatFile.forceReload();
            return MigrationResult.untouched(result.getFromVersion());
        }

        MigrationFiles.rememberAdditions(insertionRecord, fileName, result);
        result.log(fileName, backup.getFileName().toString());
        return result;
    }

    /**
     * Reads a YAML resource with SimplixStorage's own parser, such as a baseline shipped inside the plugin's jar.
     *
     * @param classLoader the class loader to find the resource with, usually the plugin's
     * @param name        the resource name, with or without a leading {@code /}
     * @return a new mutable tree, or {@code null} when the resource is missing or cannot be read
     */
    public static Map<String, Object> readResource(ClassLoader classLoader, String name) {
        String resourceName = name.startsWith("/") ? name.substring(1) : name;
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return null;
            }

            try (SimpleYamlReader reader = new SimpleYamlReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                Map<String, Object> loaded = reader.readToMap();
                return loaded == null ? null : ConfigurationTreeUtil.copyTree(loaded);
            }
        } catch (Exception exception) {
            Log.warn("[ConfigurationMigration] Resource %s could not be read: %s", resourceName, exception.getMessage());
            return null;
        }
    }

    /**
     * Reads shipped YAML resources with SimplixStorage's own parser into baselines, skipping any that are missing.
     *
     * @param classLoader the class loader to find the resources with, usually the plugin's
     * @param names       the resource names of every file the plugin has shipped
     * @return the union of the resources found
     */
    public static ShippedBaselines readBaselines(ClassLoader classLoader, String... names) {
        List<Map<String, Object>> files = new ArrayList<>();
        Arrays.stream(names).map(name -> readResource(classLoader, name)).filter(Objects::nonNull).forEach(files::add);
        return ShippedBaselines.of(files);
    }

}
