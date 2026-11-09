package net.legacy.library.configuration.migration;

import java.util.List;
import java.util.Map;

/**
 * A change to a configuration tree that the declarative parts of a {@link ConfigurationMigration} cannot express, such
 * as moving a value between sections or adding an entry to a list.
 *
 * <p>A step registered for version {@code n} runs once, on a file stamped below {@code n}. In a migration without a
 * version key every step runs on every load, so it must then leave a tree it has already changed as it is.
 *
 * <p>A step should change only what it can prove is the plugin's own, for example a value equal to one that
 * {@link ShippedBaselines#isShipped(String, Object)} recognises, and leave anything else as the operator wrote it.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@FunctionalInterface
public interface MigrationStep {

    /**
     * Applies this step to a tree.
     *
     * @param tree    the tree to edit in place, see {@link ConfigurationTreeUtil}
     * @param changes the list to add one readable line to for every change made, such as
     *                {@code "dividends.seats 3 -> 4"}; a step that adds nothing has changed nothing
     */
    void apply(Map<String, Object> tree, List<String> changes);

}
