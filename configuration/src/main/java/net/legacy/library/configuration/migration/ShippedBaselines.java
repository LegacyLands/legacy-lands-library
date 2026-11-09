package net.legacy.library.configuration.migration;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Every value a plugin has ever shipped in one configuration file, used to tell its own defaults from an operator's
 * edits.
 *
 * <p>A value on disk that equals a value this plugin shipped at the same path is the plugin's own, sitting in the file
 * because the plugin wrote it there, and a migration may replace it. Any other value was written by somebody and is
 * kept. The answer comes from the <b>union</b> of every shipped file, never from the newest alone: a file nobody has
 * touched since an older release still holds that release's defaults, and against the newest baseline alone every one
 * of them would read as an operator edit and never be upgraded. Being wrong in the other direction costs an operator
 * who typed an old default by hand having it upgraded, which the migration reports.
 *
 * <p>The union also answers the question an absent key raises, which a file cannot answer by itself: a missing key
 * has no fingerprint. A path that appears in no shipped file cannot have been deleted by anybody, so a migration may
 * add it; a path that any shipped file had may have been deleted on purpose, so it is left out. See
 * {@link #isKnown(String)}.
 *
 * <p>Baselines decide what a migration may touch: a path that is in none of them is never rewritten. Keep paths whose
 * values are layout or settings an operator is expected to tune out of the baselines when only wording should move.
 *
 * <p>Instances are immutable and thread-safe. Values are compared with {@link Objects#equals(Object, Object)}, so
 * baselines must be read with the same parser as the file they are compared with.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
public final class ShippedBaselines {

    private static final ShippedBaselines EMPTY = new ShippedBaselines(Map.of(), Set.of());

    private final Map<String, List<Object>> values;
    private final Set<String> paths;

    private ShippedBaselines(Map<String, List<Object>> values, Set<String> paths) {
        this.values = values;
        this.paths = paths;
    }

    /**
     * Gets baselines that know nothing, with which no value is ever the plugin's own.
     *
     * @return the empty baselines
     */
    public static ShippedBaselines empty() {
        return EMPTY;
    }

    /**
     * Creates baselines from whole shipped files, one tree per file the plugin has ever written.
     *
     * <p>Every leaf of every file is a value the plugin shipped at that path; lists are compared as whole values.
     * Empty or {@code null} trees are skipped.
     *
     * @param files the shipped files, in any order
     * @return the union of the files
     */
    public static ShippedBaselines of(Collection<? extends Map<String, Object>> files) {
        Map<String, List<Object>> values = new HashMap<>();
        Set<String> paths = new HashSet<>();
        files.stream().filter(Objects::nonNull).forEach(file -> {
            paths.addAll(ConfigurationTreeUtil.paths(file));
            ConfigurationTreeUtil.leaves(file).forEach((path, value) -> addValue(values, path, value));
        });
        return freeze(values, paths);
    }

    /**
     * Creates baselines from a history file, in which every leaf is the list of values the plugin has shipped at that
     * path.
     *
     * <p>Use this form when only some paths are tracked, for example only the wording of a file: {@code join:
     * ["&7Welcome", "<gray>Welcome"]} says that both texts were shipped at {@code join}. A leaf that is not a list
     * is read as a single shipped value.
     *
     * @param history the history tree
     * @return baselines holding every listed value
     */
    public static ShippedBaselines ofHistory(Map<String, Object> history) {
        Map<String, List<Object>> values = new HashMap<>();
        Set<String> paths = new HashSet<>(ConfigurationTreeUtil.paths(history));
        ConfigurationTreeUtil.leaves(history).forEach((path, value) -> {
            if (value instanceof List<?> shipped) {
                shipped.forEach(element -> addValue(values, path, element));
            } else {
                addValue(values, path, value);
            }
        });
        return freeze(values, paths);
    }

    private static void addValue(Map<String, List<Object>> values, String path, Object value) {
        List<Object> shipped = values.computeIfAbsent(path, key -> new ArrayList<>());
        if (shipped.stream().noneMatch(candidate -> Objects.equals(candidate, value))) {
            shipped.add(ConfigurationTreeUtil.copy(value));
        }
    }

    private static ShippedBaselines freeze(Map<String, List<Object>> values, Set<String> paths) {
        Map<String, List<Object>> frozen = new HashMap<>();
        values.forEach((path, shipped) -> frozen.put(path, Collections.unmodifiableList(shipped)));
        return new ShippedBaselines(Collections.unmodifiableMap(frozen), Collections.unmodifiableSet(paths));
    }

    /**
     * Creates baselines holding everything this and another one hold.
     *
     * @param other the other baselines
     * @return the union of both
     */
    public ShippedBaselines merge(ShippedBaselines other) {
        Map<String, List<Object>> merged = new HashMap<>();
        values.forEach((path, shipped) -> shipped.forEach(value -> addValue(merged, path, value)));
        other.values.forEach((path, shipped) -> shipped.forEach(value -> addValue(merged, path, value)));
        Set<String> mergedPaths = new HashSet<>(paths);
        mergedPaths.addAll(other.paths);
        return freeze(merged, mergedPaths);
    }

    /**
     * Checks whether a value is one the plugin shipped at a path.
     *
     * @param path  the dotted path
     * @param value the value found on disk, which may be {@code null}
     * @return {@code true} if any shipped file had exactly this value at this path
     */
    public boolean isShipped(String path, Object value) {
        List<Object> shipped = values.get(path);
        return shipped != null && shipped.stream().anyMatch(candidate -> Objects.equals(candidate, value));
    }

    /**
     * Checks whether any shipped file had a path at all, as a value or as a section.
     *
     * <p>A path that is known may be missing from a file because an operator deleted it; a path that is not known
     * cannot have been deleted, because no file ever had it.
     *
     * @param path the dotted path
     * @return {@code true} if any shipped file had the path
     */
    public boolean isKnown(String path) {
        return paths.contains(path);
    }

    /**
     * Checks whether these baselines know nothing, for example because the resources they are read from are missing
     * from a repackaged jar.
     *
     * @return {@code true} if no path is known
     */
    public boolean isEmpty() {
        return paths.isEmpty();
    }

}
