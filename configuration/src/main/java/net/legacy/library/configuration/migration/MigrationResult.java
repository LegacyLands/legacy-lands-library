package net.legacy.library.configuration.migration;

import io.fairyproject.log.Log;
import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.List;

/**
 * What one {@link ConfigurationMigration} did to one configuration tree.
 *
 * <p>Every list names paths, in the order they were decided. {@link #isChanged()} tells whether the tree needs to be
 * written back; the lists of things left alone ({@link #getKeptPaths()}, {@link #getAbsentPaths()},
 * {@link #getOrphanedPaths()}, {@link #getPresentPaths()}) never make a tree changed by themselves, because writing a
 * file only to report that nothing in it moved would take a backup on every load.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@Getter
@Builder(toBuilder = true)
public class MigrationResult {

    /**
     * The version the tree was stamped with before the migration, or {@code -1} for a migration without a version key.
     */
    @Builder.Default
    private final int fromVersion = -1;

    /**
     * The version the tree is stamped with after the migration, or {@code -1} for a migration without a version key.
     */
    @Builder.Default
    private final int toVersion = -1;

    /**
     * Renamed paths, as {@code "old -> new"}.
     */
    @Singular
    private final List<String> renamedPaths;

    /**
     * Paths whose value was one the plugin shipped and that now hold the current value, or were removed so the loader
     * writes its own default.
     */
    @Singular
    private final List<String> rewrittenPaths;

    /**
     * Paths the plugin knows whose value matched no shipped value, so the operator wrote it; left exactly as it was.
     */
    @Singular
    private final List<String> keptPaths;

    /**
     * Paths the plugin no longer has that were removed.
     */
    @Singular
    private final List<String> removedPaths;

    /**
     * Removed paths whose value the operator had written; the only place their wording is left is the backup.
     */
    @Singular
    private final List<String> discardedPaths;

    /**
     * Paths added because no shipped file ever had them, so nobody can have deleted them.
     */
    @Singular
    private final List<String> addedPaths;

    /**
     * Paths to add that were not added, because a shipped file had them or they were added once before, so their
     * absence may be a deletion.
     */
    @Singular
    private final List<String> absentPaths;

    /**
     * Paths to add whose section the tree does not have, so there was nowhere to put them.
     */
    @Singular
    private final List<String> orphanedPaths;

    /**
     * Paths to add that the tree already has.
     */
    @Singular
    private final List<String> presentPaths;

    /**
     * The lines the {@link MigrationStep}s reported.
     */
    @Singular
    private final List<String> stepChanges;

    /**
     * Creates a result for a tree that was not touched.
     *
     * @param version the version the tree is stamped with, or {@code -1}
     * @return a result with nothing changed
     */
    public static MigrationResult untouched(int version) {
        return builder().fromVersion(version).toVersion(version).build();
    }

    private static void logPaths(String fileName, String what, List<String> paths) {
        if (!paths.isEmpty()) {
            Log.info("[ConfigurationMigration] %s: %s (%s): %s", fileName, what, paths.size(), String.join(", ", paths));
        }
    }

    /**
     * Checks whether the migration changed the tree, including only its version stamp.
     *
     * @return {@code true} if the tree needs to be written back
     */
    public boolean isChanged() {
        return fromVersion != toVersion || !renamedPaths.isEmpty() || !rewrittenPaths.isEmpty() || !removedPaths.isEmpty()
                || !addedPaths.isEmpty() || !stepChanges.isEmpty();
    }

    /**
     * Logs what happened through {@link Log}, naming every path an operator may want to look at. Logs nothing when
     * nothing changed, so every load after the first is silent.
     *
     * @param fileName   the name of the file, used in every line
     * @param backupName the name of the backup kept beside the file, or {@code null} when there is none
     */
    public void log(String fileName, String backupName) {
        if (!isChanged()) {
            return;
        }

        if (fromVersion != toVersion) {
            Log.info("[ConfigurationMigration] %s: migrated from version %s to %s", fileName, fromVersion, toVersion);
        }

        if (backupName != null) {
            Log.info("[ConfigurationMigration] %s: the file as it was is kept as %s", fileName, backupName);
        }

        logPaths(fileName, "renamed", renamedPaths);
        logPaths(fileName, "rewritten to the new default", rewrittenPaths);
        logPaths(fileName, "removed, no longer used", removedPaths);
        logPaths(fileName, "added, new in this version", addedPaths);
        logPaths(fileName, "changed by migration steps", stepChanges);
        logPaths(fileName, "kept as you wrote them, so they keep their old wording", keptPaths);
        logPaths(fileName, "not added, because they may have been deleted on purpose", absentPaths);
        logPaths(fileName, "not added, because their section is missing", orphanedPaths);

        if (!discardedPaths.isEmpty()) {
            Log.warn("[ConfigurationMigration] %s: %s are no longer used and held your own values; they are in %s",
                    fileName, String.join(", ", discardedPaths), backupName == null ? "no backup" : backupName);
        }
    }

}
