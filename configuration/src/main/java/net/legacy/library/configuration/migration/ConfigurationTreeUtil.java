package net.legacy.library.configuration.migration;

import lombok.experimental.UtilityClass;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Utility class for reading and editing a configuration tree by dotted path.
 *
 * <p>A configuration tree is what a YAML parser returns for a mapping: nested {@link Map}s with {@link String} keys,
 * whose leaves are scalars, {@code null} (a key written with nothing after it) or {@link List}s. A path names one
 * entry by its keys joined with {@value #SEPARATOR}, such as {@code messages.join}; keys must therefore not contain
 * {@value #SEPARATOR}.
 *
 * <p>Every editing method keeps the order of the keys around the one it edits, because the order of a file an
 * operator has arranged is part of the file. None of the methods is thread-safe; a tree is meant to be edited by
 * the one thread that loaded it.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@UtilityClass
public class ConfigurationTreeUtil {

    /**
     * The separator between the keys of a path.
     */
    public static final String SEPARATOR = ".";

    /**
     * Gets the value at a path.
     *
     * @param tree the tree to read
     * @param path the dotted path
     * @return the value, or {@code null} when the path is absent or holds {@code null}; use
     * {@link #contains(Map, String)} to tell the two apart
     */
    public static Object get(Map<String, Object> tree, String path) {
        Map<String, Object> parent = parentOf(tree, path, false);
        return parent == null ? null : parent.get(lastKeyOf(path));
    }

    /**
     * Checks whether a path is present, including a key that holds {@code null}.
     *
     * @param tree the tree to read
     * @param path the dotted path
     * @return {@code true} if the key exists
     */
    public static boolean contains(Map<String, Object> tree, String path) {
        Map<String, Object> parent = parentOf(tree, path, false);
        return parent != null && parent.containsKey(lastKeyOf(path));
    }

    /**
     * Sets the value at a path, creating the sections above it when they are missing.
     *
     * <p>An existing key keeps its place; a new key is appended to its section. A key on the way that holds a value
     * rather than a section is replaced by a section.
     *
     * @param tree  the tree to edit
     * @param path  the dotted path
     * @param value the value to set, which may be {@code null}
     */
    public static void set(Map<String, Object> tree, String path, Object value) {
        parentOf(tree, path, true).put(lastKeyOf(path), value);
    }

    /**
     * Removes the key at a path. Sections left empty are kept, because an empty section an operator can see is
     * easier to understand than one that disappeared.
     *
     * @param tree the tree to edit
     * @param path the dotted path
     * @return the value that was removed, or {@code null} when there was none
     */
    public static Object remove(Map<String, Object> tree, String path) {
        Map<String, Object> parent = parentOf(tree, path, false);
        return parent == null ? null : parent.remove(lastKeyOf(path));
    }

    /**
     * Renames the key at a path in place, so the value keeps its position in the file.
     *
     * <p>When both paths share a section, the key is renamed where it stands. Otherwise the value is removed and set
     * at the new path, which appends it to that section. Nothing happens when {@code from} is absent.
     *
     * @param tree the tree to edit
     * @param from the current path
     * @param to   the new path, which must not be present
     * @return {@code true} if a value was moved
     */
    public static boolean rename(Map<String, Object> tree, String from, String to) {
        if (!contains(tree, from)) {
            return false;
        }

        if (parentPathOf(from).equals(parentPathOf(to))) {
            Map<String, Object> parent = parentOf(tree, from, false);
            String fromKey = lastKeyOf(from);
            String toKey = lastKeyOf(to);
            Map<String, Object> rebuilt = new LinkedHashMap<>();
            parent.forEach((key, value) -> rebuilt.put(key.equals(fromKey) ? toKey : key, value));
            parent.clear();
            parent.putAll(rebuilt);
            return true;
        }

        set(tree, to, remove(tree, from));
        return true;
    }

    /**
     * Inserts a key into a section directly after another key of that section.
     *
     * @param section the section to edit
     * @param key     the key to insert, which must not be present
     * @param value   the value of the new key
     * @param after   the key to insert after, or {@code null} to insert first
     */
    public static void insertAfter(Map<String, Object> section, String key, Object value, String after) {
        Map<String, Object> rebuilt = new LinkedHashMap<>();
        if (after == null) {
            rebuilt.put(key, value);
        }
        section.forEach((existing, existingValue) -> {
            rebuilt.put(existing, existingValue);
            if (existing.equals(after)) {
                rebuilt.put(key, value);
            }
        });
        if (!rebuilt.containsKey(key)) {
            rebuilt.put(key, value);
        }
        section.clear();
        section.putAll(rebuilt);
    }

    /**
     * Flattens a tree into its leaves, in file order.
     *
     * <p>A leaf is every value that is not a section: a scalar, a list or {@code null}. An empty section has no
     * leaves.
     *
     * @param tree the tree to read
     * @return a new ordered map from the path of each leaf to its value
     */
    public static Map<String, Object> leaves(Map<String, Object> tree) {
        Map<String, Object> leaves = new LinkedHashMap<>();
        collect(tree, "", leaves, null);
        return leaves;
    }

    /**
     * Lists every path of a tree, sections included, in file order.
     *
     * @param tree the tree to read
     * @return a new ordered set of paths
     */
    public static Set<String> paths(Map<String, Object> tree) {
        Set<String> paths = new LinkedHashSet<>();
        collect(tree, "", null, paths);
        return paths;
    }

    /**
     * Copies a value deeply, so that the copy shares no section or list with the original.
     *
     * @param value the value to copy
     * @return the copy; scalars are returned as they are
     */
    public static Object copy(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copied = new LinkedHashMap<>();
            map.forEach((key, child) -> copied.put(String.valueOf(key), copy(child)));
            return copied;
        }

        if (value instanceof List<?> list) {
            List<Object> copied = new ArrayList<>(list.size());
            list.forEach(element -> copied.add(copy(element)));
            return copied;
        }

        return value;
    }

    /**
     * Copies a tree deeply, turning every key into a {@link String}.
     *
     * <p>A YAML parser may return other key types, such as an {@link Integer} for {@code 1:}; a configuration tree
     * only has {@link String} keys, as Bukkit's own configuration has.
     *
     * @param tree the tree to copy
     * @return a new mutable tree
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> copyTree(Map<?, ?> tree) {
        return (Map<String, Object>) copy(tree);
    }

    /**
     * Gets the path of the section that holds a path.
     *
     * @param path the dotted path
     * @return the parent path, or an empty string for a top-level key
     */
    public static String parentPathOf(String path) {
        int separator = path.lastIndexOf(SEPARATOR);
        return separator < 0 ? "" : path.substring(0, separator);
    }

    /**
     * Gets the last key of a path.
     *
     * @param path the dotted path
     * @return the key the path ends with
     */
    public static String lastKeyOf(String path) {
        int separator = path.lastIndexOf(SEPARATOR);
        return separator < 0 ? path : path.substring(separator + 1);
    }

    /**
     * Gets the section at a path.
     *
     * @param tree the tree to read
     * @param path the dotted path, or an empty string for the tree itself
     * @return the section, or {@code null} when the path is absent or is not a section
     */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> sectionOf(Map<String, Object> tree, String path) {
        if (path.isEmpty()) {
            return tree;
        }
        return get(tree, path) instanceof Map<?, ?> section ? (Map<String, Object>) section : null;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> parentOf(Map<String, Object> tree, String path, boolean create) {
        Map<String, Object> section = tree;
        String[] keys = path.split("\\.", -1);
        for (int index = 0; index < keys.length - 1; index++) {
            Object child = section.get(keys[index]);
            if (!(child instanceof Map<?, ?>)) {
                if (!create) {
                    return null;
                }
                child = new LinkedHashMap<String, Object>();
                section.put(keys[index], child);
            }
            section = (Map<String, Object>) child;
        }
        return section;
    }

    private static void collect(Map<?, ?> section, String prefix, Map<String, Object> leaves, Set<String> paths) {
        section.forEach((key, value) -> {
            String path = prefix + key;
            if (paths != null) {
                paths.add(path);
            }
            if (value instanceof Map<?, ?> child) {
                collect(child, path + SEPARATOR, leaves, paths);
            } else if (leaves != null) {
                leaves.put(path, value);
            }
        });
    }

}
