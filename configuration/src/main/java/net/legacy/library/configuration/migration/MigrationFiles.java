package net.legacy.library.configuration.migration;

import io.fairyproject.log.Log;
import lombok.experimental.UtilityClass;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * File handling shared by the migration utilities: backups, atomic replacement and the record of added paths.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
class MigrationFiles {

    private static final List<String> RECORD_HEADER = List.of(
            "# Paths a configuration migration has added to, or found in, each file.",
            "# A path listed here is never added again, so deleting it from the file is final.");

    /**
     * Copies a file to {@code <name>.<label>.bak} beside it, or to {@code <name>.<label>-<n>.bak} when that exists,
     * so no earlier backup is ever replaced.
     *
     * @param file  the file to back up
     * @param label what the backup holds, such as {@code schema-3}
     * @return the backup
     * @throws IOException if the copy cannot be made
     */
    static Path backup(Path file, String label) throws IOException {
        String name = file.getFileName().toString() + "." + label;
        Path backup = file.resolveSibling(name + ".bak");
        for (int attempt = 2; Files.exists(backup); attempt++) {
            backup = file.resolveSibling(name + "-" + attempt + ".bak");
        }
        Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES);
        return backup;
    }

    /**
     * Gets the label of the backup a migration result calls for.
     *
     * @param result the result
     * @return {@code schema-<from>} for a stamped migration, {@code pre-migration} otherwise
     */
    static String labelOf(MigrationResult result) {
        return result.getFromVersion() < 0 ? "pre-migration" : "schema-" + result.getFromVersion();
    }

    /**
     * Replaces a file with new content through a sibling temporary file, so a failed write never leaves half a file.
     *
     * @param file    the file to replace
     * @param content the new content
     * @throws IOException if the content cannot be written
     */
    static void replace(Path file, String content) throws IOException {
        Path staging = file.resolveSibling(file.getFileName() + ".migrating");
        Files.writeString(staging, content, StandardCharsets.UTF_8);
        try {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(staging, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    /**
     * Applies a migration to a tree read from a file, honouring the record of added paths, and never throws.
     *
     * @param migration       the migration
     * @param tree            the tree to edit in place
     * @param insertionRecord the record, or {@code null} for none
     * @param fileName        the name of the file the tree was read from
     * @return what was done, or {@code null} when the migration failed and the tree must not be written
     */
    static MigrationResult apply(ConfigurationMigration migration, Map<String, Object> tree, Path insertionRecord,
                                 String fileName) {
        try {
            Set<String> addedBefore = insertionRecord == null ? Set.of() : readRecord(insertionRecord, fileName);
            if (addedBefore == null) {
                return migration.toBuilder().clearAddedPaths().build().migrate(tree, Set.of());
            }
            return migration.migrate(tree, addedBefore);
        } catch (RuntimeException exception) {
            Log.error("[ConfigurationMigration] %s could not be migrated, so it was left as it was", exception, fileName);
            return null;
        }
    }

    /**
     * Remembers the paths a result added or found, when there is a record.
     *
     * @param insertionRecord the record, or {@code null} for none
     * @param fileName        the name of the file
     * @param result          the result
     */
    static void rememberAdditions(Path insertionRecord, String fileName, MigrationResult result) {
        if (insertionRecord == null) {
            return;
        }

        List<String> paths = new ArrayList<>(result.getAddedPaths());
        paths.addAll(result.getPresentPaths());
        remember(insertionRecord, fileName, paths);
    }

    /**
     * Reads the paths the record holds for one file.
     *
     * @param record   the record, which may not exist yet
     * @param fileName the file the paths belong to
     * @return the paths, or {@code null} when the record exists and cannot be read; an unreadable record says nothing
     * about what an operator deleted, so the caller must add nothing then
     */
    static Set<String> readRecord(Path record, String fileName) {
        if (!Files.exists(record)) {
            return Set.of();
        }

        try {
            Set<String> paths = new HashSet<>();
            Files.readAllLines(record, StandardCharsets.UTF_8).stream()
                    .map(String::strip)
                    .filter(line -> !line.startsWith("#") && line.startsWith(fileName + " "))
                    .forEach(line -> paths.add(line.substring(fileName.length() + 1).strip()));
            return paths;
        } catch (IOException | UncheckedIOException exception) {
            Log.error("[ConfigurationMigration] %s could not be read, so nothing is added to %s", exception,
                    record.getFileName(), fileName);
            return null;
        }
    }

    /**
     * Adds paths to the record. A failure is logged and survived: the file itself is as it should be, and the next
     * load that finds the paths in it records them then.
     *
     * @param record   the record
     * @param fileName the file the paths belong to
     * @param paths    the paths to remember
     */
    static void remember(Path record, String fileName, Collection<String> paths) {
        if (paths.isEmpty()) {
            return;
        }

        try {
            List<String> lines = Files.exists(record)
                    ? new ArrayList<>(Files.readAllLines(record, StandardCharsets.UTF_8))
                    : new ArrayList<>(RECORD_HEADER);
            int before = lines.size();
            paths.stream().map(path -> fileName + " " + path).filter(entry -> !lines.contains(entry)).forEach(lines::add);
            if (lines.size() == before) {
                return;
            }

            if (record.getParent() != null) {
                Files.createDirectories(record.getParent());
            }
            replace(record, String.join(System.lineSeparator(), lines) + System.lineSeparator());
        } catch (IOException | UncheckedIOException exception) {
            Log.warn("[ConfigurationMigration] %s could not record %s; if you delete one of them before the next "
                    + "start, it is added again", exception, record.getFileName(), String.join(", ", paths));
        }
    }

}
