package net.legacy.library.configuration.migration;

import lombok.Builder;
import lombok.Getter;
import lombok.Singular;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;

/**
 * Brings a configuration file an operator already has up to what the current version of a plugin ships, without
 * touching anything the operator wrote.
 *
 * <p>A plugin writes its default file once, when there is none, and never looks at the shipped copy again; a
 * configuration loader such as Fairy's {@code loadAndSave()} keeps every key the file has and only falls back to the
 * default for a missing one. Changing a default therefore reaches no server that has started once. This class
 * delivers the change, deciding each value by one rule: a value that equals one the plugin has ever shipped at that
 * path is the plugin's own and is upgraded; any other value was written by somebody and is kept and reported. Which
 * values were shipped comes from {@link ShippedBaselines}, the union of every shipped file.
 *
 * <p>{@link #migrate(Map, Set)} works on a configuration tree in memory, in this order:
 *
 * <ol>
 *   <li><b>Renames</b> ({@code rename}): a value moves to its new path in place. When the new path is set as well,
 *       the new one wins and the old one is removed. A renamed value is compared with the baselines at both paths, so
 *       baselines may keep the old name.</li>
 *   <li><b>Steps</b> ({@code step}): every {@link MigrationStep} registered for a version above the file's stamp
 *       runs, in ascending order of version.</li>
 *   <li><b>Retired paths</b> ({@code retire} and {@code discard}): a path the plugin no longer has is removed when
 *       every value under it was shipped. A retired path that holds an operator's value is kept and reported, for
 *       files read by a loader that keeps unknown keys; a discarded path is removed anyway and reported as discarded,
 *       for a loader that drops unknown keys on save, where the backup is the only copy left.</li>
 *   <li><b>Rewrites</b>: every leaf the baselines know whose value was shipped and differs from the current tree
 *       becomes the current value. Without a current tree, such a leaf is removed, so the loader writes its own new
 *       default; that is the way to deliver a changed default to a Fairy configuration.</li>
 *   <li><b>Additions</b> ({@code add}): a path of the current tree that no shipped file ever had is copied in next to
 *       its neighbours. A path any shipped file had, or one that {@code addedBefore} names, is left out, because its
 *       absence may be a deletion.</li>
 *   <li><b>Stamp</b>: with a {@link #getVersionKey()}, the tree is stamped with {@link #getVersion()}.</li>
 * </ol>
 *
 * <p>With a version key, a file stamped at {@link #getVersion()} or above is not touched at all, which is what keeps an
 * operator who deliberately set an old default back from having it rewritten on every load. The stamp is also the
 * one failure this class cannot report: a default changed without raising {@link #getVersion()} never reaches a file
 * already stamped with it. Raise the version, and add the file it replaces to the baselines, on every change of a
 * default. Without a version key the migration runs on every load; it is idempotent as long as a current tree is
 * given and every step is idempotent, because after one run no value equals an old default any more.
 *
 * <p>Instances are immutable and may be shared; {@link #migrate(Map, Set)} edits only the tree it is given and is not
 * thread-safe for that tree. Nothing here does I/O; see {@link YamlMigrationUtil} and {@link SimplixMigrationUtil} for
 * files.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@Getter
@Builder(toBuilder = true)
public class ConfigurationMigration {

    /**
     * Every value the plugin has ever shipped in this file.
     */
    @Builder.Default
    private final ShippedBaselines baselines = ShippedBaselines.empty();

    /**
     * The tree the current version ships, or {@code null} when the loader writes defaults itself; required without a
     * {@link #versionKey} and for additions.
     */
    private final Map<String, Object> current;

    /**
     * The top-level key the version stamp is written under, such as {@code schemaVersion}, or {@code null} for a
     * migration that runs on every load. The loader must keep the key, for a Fairy configuration by declaring a field
     * for it, or the stamp is lost on save and the migration runs again.
     */
    private final String versionKey;

    /**
     * The version this migration brings a file to.
     */
    @Builder.Default
    private final int version = 1;

    /**
     * The version assumed for a file without a stamp, written before the stamp existed.
     */
    @Builder.Default
    private final int unstampedVersion = 1;

    /**
     * Paths that changed name, old to new, applied in insertion order.
     */
    @Singular("rename")
    private final Map<String, String> renames;

    /**
     * Paths the plugin no longer has, removed only when every value under them was shipped.
     */
    @Singular("retire")
    private final List<String> retiredPaths;

    /**
     * Paths the plugin no longer has, removed whatever they hold.
     */
    @Singular("discard")
    private final List<String> discardedPaths;

    /**
     * Paths of the current tree to add to a file that does not have them.
     */
    @Singular("add")
    private final List<String> addedPaths;

    /**
     * Steps by the version that introduced them.
     */
    @Singular("step")
    private final Map<Integer, MigrationStep> steps;

    /**
     * Finds the key a new key goes after: its nearest earlier sibling in the current tree that the file has, or, when
     * there is none, the key before its nearest later sibling the file has.
     */
    private static String anchorOf(Map<String, Object> parent, Map<String, Object> currentParent, String key) {
        List<String> siblings = new ArrayList<>(currentParent.keySet());
        int index = siblings.indexOf(key);

        for (int earlier = index - 1; earlier >= 0; earlier--) {
            if (parent.containsKey(siblings.get(earlier))) {
                return siblings.get(earlier);
            }
        }

        List<String> existing = new ArrayList<>(parent.keySet());
        for (int later = index + 1; later < siblings.size(); later++) {
            int position = existing.indexOf(siblings.get(later));
            if (position >= 0) {
                return position == 0 ? null : existing.get(position - 1);
            }
        }

        return existing.isEmpty() ? null : existing.getLast();
    }

    /**
     * Migrates a tree, adding every path of {@link #getAddedPaths()} that no shipped file had.
     *
     * @param tree the tree to edit in place
     * @return what was done
     * @see #migrate(Map, Set)
     */
    public MigrationResult migrate(Map<String, Object> tree) {
        return migrate(tree, Set.of());
    }

    /**
     * Migrates a tree in place.
     *
     * <p>Without a version key, an added path an operator deletes would be added again on the next load, because it
     * is still in no shipped file. Pass the paths added to this file before as {@code addedBefore}, and remember
     * {@link MigrationResult#getAddedPaths()} and {@link MigrationResult#getPresentPaths()} for the next load; with a
     * version key, additions only happen on the load that raises the stamp, so an empty set is enough.
     *
     * @param tree        the tree to edit in place, with {@link String} keys
     * @param addedBefore paths of {@link #getAddedPaths()} this file has had before, never added again
     * @return what was done; {@link MigrationResult#isChanged()} tells whether to write the tree back
     * @throws IllegalStateException if there is no current tree but a migration without a version key or with
     *                               additions needs one
     */
    public MigrationResult migrate(Map<String, Object> tree, Set<String> addedBefore) {
        if (current == null && versionKey == null) {
            throw new IllegalStateException("A migration without a version key needs the current tree, "
                    + "or it would remove every shipped value on every load");
        }

        if (current == null && !addedPaths.isEmpty()) {
            throw new IllegalStateException("Additions need the current tree to copy from");
        }

        int fromVersion = versionOf(tree);
        if (versionKey != null && fromVersion >= version) {
            return MigrationResult.untouched(fromVersion);
        }

        MigrationResult.MigrationResultBuilder result = MigrationResult.builder()
                .fromVersion(fromVersion)
                .toVersion(versionKey == null ? -1 : version);

        applyRenames(tree, result);
        applySteps(tree, fromVersion, result);
        applyRetirements(tree, result);
        applyRewrites(tree, result);
        applyAdditions(tree, addedBefore, result);

        if (versionKey != null) {
            tree.put(versionKey, version);
        }

        return result.build();
    }

    /**
     * Gets the version a tree is stamped with.
     *
     * @param tree the tree to read
     * @return the stamp, {@link #getUnstampedVersion()} when there is none, or {@code -1} without a version key
     */
    public int versionOf(Map<String, Object> tree) {
        if (versionKey == null) {
            return -1;
        }
        return tree.get(versionKey) instanceof Number number ? number.intValue() : unstampedVersion;
    }

    /**
     * Checks whether a value at a path is one the plugin shipped, at that path or at the path it had before a rename.
     *
     * @param path  the dotted path
     * @param value the value found on disk
     * @return {@code true} if the value was shipped
     */
    public boolean isShipped(String path, Object value) {
        return formerPathsOf(path).stream().anyMatch(former -> baselines.isShipped(former, value));
    }

    /**
     * Checks whether any shipped file had a path, under its current name or the name it had before a rename.
     *
     * @param path the dotted path
     * @return {@code true} if the path is known
     */
    public boolean isKnown(String path) {
        return formerPathsOf(path).stream().anyMatch(baselines::isKnown);
    }

    private List<String> formerPathsOf(String path) {
        List<String> formerPaths = new ArrayList<>();
        formerPaths.add(path);
        renames.forEach((from, to) -> {
            if (path.equals(to)) {
                formerPaths.add(from);
            } else if (path.startsWith(to + ConfigurationTreeUtil.SEPARATOR)) {
                formerPaths.add(from + path.substring(to.length()));
            }
        });
        return formerPaths;
    }

    private void applyRenames(Map<String, Object> tree, MigrationResult.MigrationResultBuilder result) {
        renames.forEach((from, to) -> {
            if (!ConfigurationTreeUtil.contains(tree, from)) {
                return;
            }

            if (ConfigurationTreeUtil.contains(tree, to)) {
                // Configured under both names: the new one is what the plugin reads
                ConfigurationTreeUtil.remove(tree, from);
                result.removedPath(from);
                return;
            }

            ConfigurationTreeUtil.rename(tree, from, to);
            result.renamedPath(from + " -> " + to);
        });
    }

    private void applySteps(Map<String, Object> tree, int fromVersion, MigrationResult.MigrationResultBuilder result) {
        new TreeMap<>(steps).forEach((stepVersion, step) -> {
            if (versionKey != null && fromVersion >= stepVersion) {
                return;
            }

            List<String> changes = new ArrayList<>();
            step.apply(tree, changes);
            changes.forEach(result::stepChange);
        });
    }

    private void applyRetirements(Map<String, Object> tree, MigrationResult.MigrationResultBuilder result) {
        retiredPaths.stream().filter(path -> ConfigurationTreeUtil.contains(tree, path)).forEach(path -> {
            if (isShippedEverywhere(path, ConfigurationTreeUtil.get(tree, path))) {
                ConfigurationTreeUtil.remove(tree, path);
                result.removedPath(path);
            } else {
                result.keptPath(path);
            }
        });

        discardedPaths.stream().filter(path -> ConfigurationTreeUtil.contains(tree, path)).forEach(path -> {
            Object value = ConfigurationTreeUtil.remove(tree, path);
            result.removedPath(path);
            if (!isShippedEverywhere(path, value)) {
                result.discardedPath(path);
            }
        });
    }

    private void applyRewrites(Map<String, Object> tree, MigrationResult.MigrationResultBuilder result) {
        ConfigurationTreeUtil.leaves(tree).forEach((path, value) -> {
            if (path.equals(versionKey) || isRetired(path) || !isKnown(path)) {
                return;
            }

            if (current == null) {
                if (isShipped(path, value)) {
                    // The loader writes its own default for a missing key
                    ConfigurationTreeUtil.remove(tree, path);
                    result.rewrittenPath(path);
                } else {
                    result.keptPath(path);
                }
                return;
            }

            if (!ConfigurationTreeUtil.contains(current, path)) {
                // Gone from this version and not retired: nothing to rewrite it to
                return;
            }

            Object currentValue = ConfigurationTreeUtil.get(current, path);
            if (Objects.equals(value, currentValue)) {
                return;
            }

            if (isShipped(path, value)) {
                ConfigurationTreeUtil.set(tree, path, ConfigurationTreeUtil.copy(currentValue));
                result.rewrittenPath(path);
            } else {
                result.keptPath(path);
            }
        });
    }

    private void applyAdditions(Map<String, Object> tree, Set<String> addedBefore,
                                MigrationResult.MigrationResultBuilder result) {
        addedPaths.stream().filter(path -> ConfigurationTreeUtil.contains(current, path)).forEach(path -> {
            if (ConfigurationTreeUtil.contains(tree, path)) {
                result.presentPath(path);
                return;
            }

            if (addedBefore.contains(path) || isKnown(path)) {
                result.absentPath(path);
                return;
            }

            String parentPath = ConfigurationTreeUtil.parentPathOf(path);
            Map<String, Object> parent = ConfigurationTreeUtil.sectionOf(tree, parentPath);
            if (parent == null) {
                result.orphanedPath(path);
                return;
            }

            String key = ConfigurationTreeUtil.lastKeyOf(path);
            Object value = ConfigurationTreeUtil.copy(ConfigurationTreeUtil.get(current, path));
            ConfigurationTreeUtil.insertAfter(parent, key, value,
                    anchorOf(parent, ConfigurationTreeUtil.sectionOf(current, parentPath), key));
            result.addedPath(path);
        });
    }

    private boolean isRetired(String path) {
        return retiredPaths.stream().anyMatch(retired -> path.equals(retired)
                || path.startsWith(retired + ConfigurationTreeUtil.SEPARATOR));
    }

    private boolean isShippedEverywhere(String path, Object value) {
        if (!(value instanceof Map<?, ?> section)) {
            return isShipped(path, value);
        }

        return ConfigurationTreeUtil.leaves(ConfigurationTreeUtil.copyTree(section)).entrySet().stream()
                .allMatch(leaf -> isShipped(path + ConfigurationTreeUtil.SEPARATOR + leaf.getKey(), leaf.getValue()));
    }

}
