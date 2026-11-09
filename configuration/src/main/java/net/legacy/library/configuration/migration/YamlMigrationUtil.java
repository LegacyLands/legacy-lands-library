package net.legacy.library.configuration.migration;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;
import org.yaml.snakeyaml.DumperOptions;
import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;
import org.yaml.snakeyaml.nodes.MappingNode;
import org.yaml.snakeyaml.nodes.NodeTuple;
import org.yaml.snakeyaml.nodes.ScalarNode;
import org.yaml.snakeyaml.nodes.Tag;
import org.yaml.snakeyaml.representer.Representer;

import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Utility class for migrating YAML files with SnakeYAML before the plugin's own loader reads them.
 *
 * <p>SnakeYAML is the parser Fairy's configuration and Bukkit's {@code YamlConfiguration} both use (Fairy's copy is
 * relocated inside {@code fairy-lib-plugin}, which this module is built against), so a value compares here exactly as
 * the loader will read it. Two of its behaviours would otherwise damage a file on the way through, and both are
 * handled:
 *
 * <ul>
 *   <li>A key written with nothing after it ({@code key:}) holds {@code null}. Bukkit's loader deletes such a key,
 *       and SnakeYAML's own writer turns it into {@code key: null}; here it is read as {@code null} and written back
 *       as {@code key:}.</li>
 *   <li>YAML 1.1 reads a plain {@code on}, {@code off}, {@code yes} or {@code no} as a boolean, which turns a map key
 *       {@code on:} into {@code true}. Keys are read here as the text they are written as, so {@code on:} stays
 *       {@code "on"} and is written back quoted, {@code 'on':}, which is what it was meant to say. Values keep the
 *       loader's reading.</li>
 * </ul>
 *
 * <p>Comments are not preserved: the file is written from its values. That suits a file whose loader writes its
 * comments itself, such as a Fairy configuration, which {@code loadAndSave()} regenerates straight after the
 * migration. For a file whose comments belong to the operator, keep it in SimplixStorage and use
 * {@link SimplixMigrationUtil}.
 *
 * <p>Nothing here throws: a file that cannot be read, backed up or written is left exactly as it was and the failure
 * is logged, because a migration must never be the thing that stops a plugin from starting.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class YamlMigrationUtil {

    /**
     * Migrates a YAML file in place.
     *
     * @param file      the file, which need not exist; a missing or empty file is left alone, because the plugin
     *                  writes its current defaults to it anyway
     * @param migration the migration to apply
     * @return what was done; untouched when the file was missing, unreadable or could not be written
     * @see #migrate(Path, ConfigurationMigration, Path)
     */
    public static MigrationResult migrate(Path file, ConfigurationMigration migration) {
        return migrate(file, migration, null);
    }

    /**
     * Migrates a YAML file in place, remembering added paths in a record so a path an operator deletes after it was
     * added is never added again.
     *
     * <p>When the file changes, it is first copied to {@code <name>.schema-<from>.bak} (or {@code
     * <name>.pre-migration.bak} without a version key) beside it, never replacing an earlier backup, and nothing is
     * written unless that copy succeeds. The new content then replaces the file through a temporary sibling. What was
     * done is logged through {@link MigrationResult#log(String, String)}.
     *
     * @param file            the file, which need not exist
     * @param migration       the migration to apply
     * @param insertionRecord the record of added paths, shared by every file of a plugin and created on demand, or
     *                        {@code null} for none; when it exists and cannot be read, nothing is added
     * @return what was done; untouched when the file was missing, unreadable or could not be written
     */
    public static MigrationResult migrate(Path file, ConfigurationMigration migration, Path insertionRecord) {
        String fileName = file.getFileName().toString();
        Map<String, Object> tree = read(file);
        if (tree == null || tree.isEmpty()) {
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
            MigrationFiles.replace(file, write(tree));
        } catch (IOException | RuntimeException exception) {
            Log.error("[ConfigurationMigration] %s could not be migrated, so it was left as it was", exception, fileName);
            return MigrationResult.untouched(result.getFromVersion());
        }

        MigrationFiles.rememberAdditions(insertionRecord, fileName, result);
        result.log(fileName, backup.getFileName().toString());
        return result;
    }

    /**
     * Reads a YAML file as a configuration tree.
     *
     * @param file the file
     * @return a new mutable tree, or {@code null} when the file does not exist, is empty, cannot be parsed or is not a
     * mapping
     */
    public static Map<String, Object> read(Path file) {
        if (!Files.isRegularFile(file)) {
            return null;
        }

        try (Reader reader = Files.newBufferedReader(file, StandardCharsets.UTF_8)) {
            return asTree(yaml().load(reader));
        } catch (IOException | RuntimeException exception) {
            // The loader reports a syntax error properly a moment later; migrating a file nobody can read is never right
            Log.warn("[ConfigurationMigration] %s could not be read (%s), so it was not migrated",
                    file.getFileName(), exception.getMessage());
            return null;
        }
    }

    /**
     * Reads a YAML document from a string.
     *
     * @param content the document
     * @return a new mutable tree, or {@code null} when the document is empty or not a mapping
     * @throws org.yaml.snakeyaml.error.YAMLException if the document cannot be parsed
     */
    public static Map<String, Object> read(String content) {
        return asTree(yaml().load(content));
    }

    /**
     * Reads a YAML resource, such as a baseline shipped inside the plugin's jar.
     *
     * @param classLoader the class loader to find the resource with, usually the plugin's
     * @param name        the resource name, with or without a leading {@code /}
     * @return a new mutable tree, or {@code null} when the resource is missing or cannot be read, which only happens
     * to a jar somebody has repacked
     */
    public static Map<String, Object> readResource(ClassLoader classLoader, String name) {
        String resourceName = name.startsWith("/") ? name.substring(1) : name;
        try (InputStream stream = classLoader.getResourceAsStream(resourceName)) {
            if (stream == null) {
                return null;
            }
            return asTree(yaml().load(new InputStreamReader(stream, StandardCharsets.UTF_8)));
        } catch (IOException | RuntimeException exception) {
            Log.warn("[ConfigurationMigration] Resource %s could not be read: %s", resourceName, exception.getMessage());
            return null;
        }
    }

    /**
     * Reads shipped YAML resources into baselines, skipping any that are missing.
     *
     * @param classLoader the class loader to find the resources with, usually the plugin's
     * @param names       the resource names of every file the plugin has shipped
     * @return the union of the resources found; {@link ShippedBaselines#isEmpty()} when none was
     */
    public static ShippedBaselines readBaselines(ClassLoader classLoader, String... names) {
        List<Map<String, Object>> files = new ArrayList<>();
        Arrays.stream(names).map(name -> readResource(classLoader, name)).filter(Objects::nonNull).forEach(files::add);
        return ShippedBaselines.of(files);
    }

    /**
     * Writes a configuration tree as a YAML document, in block style, with a key holding {@code null} written as
     * {@code key:}.
     *
     * @param tree the tree
     * @return the document
     */
    public static String write(Map<String, Object> tree) {
        return yaml().dump(tree);
    }

    private static Map<String, Object> asTree(Object loaded) {
        return loaded instanceof Map<?, ?> map ? ConfigurationTreeUtil.copyTree(map) : null;
    }

    private static Yaml yaml() {
        LoaderOptions loaderOptions = new LoaderOptions();
        loaderOptions.setAllowDuplicateKeys(false);

        DumperOptions dumperOptions = new DumperOptions();
        dumperOptions.setDefaultFlowStyle(DumperOptions.FlowStyle.BLOCK);
        dumperOptions.setSplitLines(false);
        dumperOptions.setIndent(2);

        return new Yaml(new LiteralKeyConstructor(loaderOptions), new EmptyNullRepresenter(dumperOptions),
                dumperOptions, loaderOptions);
    }

    /**
     * Reads every scalar map key as the text it is written as, so {@code on:} is {@code "on"} rather than
     * {@code true}.
     */
    private static final class LiteralKeyConstructor extends SafeConstructor {

        LiteralKeyConstructor(LoaderOptions loaderOptions) {
            super(loaderOptions);
        }

        @Override
        protected void constructMapping2ndStep(MappingNode node, Map<Object, Object> mapping) {
            node.getValue().stream()
                    .map(NodeTuple::getKeyNode)
                    .filter(keyNode -> keyNode instanceof ScalarNode && !Tag.MERGE.equals(keyNode.getTag()))
                    .forEach(keyNode -> keyNode.setTag(Tag.STR));
            super.constructMapping2ndStep(node, mapping);
        }

    }

    /**
     * Writes {@code null} as nothing, so a key written as {@code key:} is written back the same way.
     */
    private static final class EmptyNullRepresenter extends Representer {

        EmptyNullRepresenter(DumperOptions dumperOptions) {
            super(dumperOptions);
            this.nullRepresenter = data -> representScalar(Tag.NULL, "");
        }

    }

}
