package net.legacy.library.configuration.test;

import net.legacy.library.configuration.migration.ConfigurationMigration;
import net.legacy.library.configuration.migration.ConfigurationTreeUtil;
import net.legacy.library.configuration.migration.MigrationResult;
import net.legacy.library.configuration.migration.ShippedBaselines;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Test class for {@link ConfigurationMigration}, validating how shipped defaults are told from an operator's edits.
 *
 * <p>Covers the union of baselines, version stamps, renames, retirements, steps and additions under the union rule,
 * on configuration trees in memory.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "configuration-migration-test",
        description = "Tests ConfigurationMigration decisions between shipped defaults and operator edits",
        tags = {"configuration", "migration", "baseline", "union"},
        priority = 1,
        timeout = 5000,
        expectedResult = "SUCCESS"
)
public class ConfigurationMigrationTest {

    private static final String MODULE_NAME = "configuration";

    /**
     * Tests that a value equal to an older shipped default is rewritten to the current one, even when the newest
     * baseline holds a different default.
     */
    public static boolean testRewriteShippedValueFromAnyBaseline() {
        try {
            ShippedBaselines baselines = ShippedBaselines.of(List.of(
                    tree("join", "&7Welcome"),
                    tree("join", "<gray>Welcome")));
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(baselines)
                    .current(tree("join", "<gray>Welcome back"))
                    .build();

            Map<String, Object> onDisk = tree("join", "&7Welcome");
            MigrationResult result = migration.migrate(onDisk);

            return check("rewritten from the oldest baseline", "<gray>Welcome back".equals(onDisk.get("join"))
                    && result.getRewrittenPaths().equals(List.of("join")) && result.isChanged());
        } catch (Exception exception) {
            return failure("testRewriteShippedValueFromAnyBaseline", exception);
        }
    }

    /**
     * Tests that an operator's own value and an operator's own key are left exactly as they are.
     */
    public static boolean testRewriteKeepsOperatorEdits() {
        try {
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(tree("join", "&7Welcome"))))
                    .current(tree("join", "<gray>Welcome back"))
                    .build();

            Map<String, Object> onDisk = tree("join", "Hello there");
            onDisk.put("custom", "mine");
            MigrationResult result = migration.migrate(onDisk);

            return check("operator edit kept", "Hello there".equals(onDisk.get("join"))
                    && "mine".equals(onDisk.get("custom"))
                    && result.getKeptPaths().equals(List.of("join"))
                    && !result.isChanged());
        } catch (Exception exception) {
            return failure("testRewriteKeepsOperatorEdits", exception);
        }
    }

    /**
     * Tests that a migration without a version key is idempotent: the second run finds nothing to do.
     */
    public static boolean testRewriteIsIdempotentWithoutStamp() {
        try {
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(tree("join", "old"))))
                    .current(tree("join", "new"))
                    .build();

            Map<String, Object> onDisk = tree("join", "old");
            boolean first = migration.migrate(onDisk).isChanged();
            MigrationResult second = migration.migrate(onDisk);

            return check("second run silent", first && !second.isChanged() && second.getRewrittenPaths().isEmpty()
                    && second.getKeptPaths().isEmpty());
        } catch (Exception exception) {
            return failure("testRewriteIsIdempotentWithoutStamp", exception);
        }
    }

    /**
     * Tests the stamped mode without a current tree, as used for a Fairy configuration: shipped values are removed so
     * the loader writes its new defaults, edits are kept, the stamp is written, and a stamped file is not touched.
     */
    public static boolean testRewriteStampedRemovesShippedForLoader() {
        try {
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(tree("a", "shipped-a", "b", "shipped-b"))))
                    .versionKey("schemaVersion")
                    .version(3)
                    .build();

            Map<String, Object> onDisk = tree("a", "shipped-a", "b", "edited-b");
            MigrationResult result = migration.migrate(onDisk);

            boolean migrated = !onDisk.containsKey("a") && "edited-b".equals(onDisk.get("b"))
                    && Integer.valueOf(3).equals(onDisk.get("schemaVersion"))
                    && result.getFromVersion() == 1 && result.getToVersion() == 3;

            onDisk.put("a", "shipped-a");
            MigrationResult again = migration.migrate(onDisk);

            return check("stamped migration", migrated && !again.isChanged() && "shipped-a".equals(onDisk.get("a")));
        } catch (Exception exception) {
            return failure("testRewriteStampedRemovesShippedForLoader", exception);
        }
    }

    /**
     * Tests that a key written with nothing after it survives, both when it is current and when it is shipped.
     */
    public static boolean testRewriteKeepsEmptyKeys() {
        try {
            Map<String, Object> shipped = new LinkedHashMap<>();
            shipped.put("manual-entries", null);
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(shipped)))
                    .current(ConfigurationTreeUtil.copyTree(shipped))
                    .build();

            Map<String, Object> onDisk = new LinkedHashMap<>();
            onDisk.put("manual-entries", null);
            MigrationResult result = migration.migrate(onDisk);

            return check("empty key kept", onDisk.containsKey("manual-entries") && onDisk.get("manual-entries") == null
                    && !result.isChanged());
        } catch (Exception exception) {
            return failure("testRewriteKeepsEmptyKeys", exception);
        }
    }

    /**
     * Tests renames: in place, with an edit carried across, an old shipped value judged at its old path, and a
     * conflict won by the new name.
     */
    public static boolean testRenameMovesValuesInPlace() {
        try {
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(tree("first", "1", "shards", "Shards", "last", "9"))))
                    .current(tree("first", "1", "money", "Money", "last", "9"))
                    .rename("shards", "money")
                    .build();

            Map<String, Object> shippedOnDisk = tree("first", "1", "shards", "Shards", "last", "9");
            MigrationResult shippedResult = migration.migrate(shippedOnDisk);
            boolean shippedMoved = List.of("first", "money", "last").equals(new ArrayList<>(shippedOnDisk.keySet()))
                    && "Money".equals(shippedOnDisk.get("money"))
                    && shippedResult.getRenamedPaths().equals(List.of("shards -> money"))
                    && shippedResult.getRewrittenPaths().equals(List.of("money"));

            Map<String, Object> editedOnDisk = tree("shards", "Coins");
            migration.migrate(editedOnDisk);
            boolean editCarried = "Coins".equals(editedOnDisk.get("money")) && !editedOnDisk.containsKey("shards");

            Map<String, Object> bothOnDisk = tree("shards", "Coins", "money", "Gold");
            MigrationResult bothResult = migration.migrate(bothOnDisk);
            boolean newWins = "Gold".equals(bothOnDisk.get("money")) && !bothOnDisk.containsKey("shards")
                    && bothResult.getRemovedPaths().equals(List.of("shards"));

            return check("renames", shippedMoved && editCarried && newWins);
        } catch (Exception exception) {
            return failure("testRenameMovesValuesInPlace", exception);
        }
    }

    /**
     * Tests retired and discarded paths: shipped values go, an operator's retired value stays, an operator's discarded
     * value goes and is reported, and a section goes only when every value under it was shipped.
     */
    public static boolean testRetireAndDiscard() {
        try {
            Map<String, Object> shipped = tree("old", "shipped", "edited", "shipped", "gone", "shipped");
            shipped.put("screen", tree("title", "Top", "size", 54));
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(shipped)))
                    .current(tree("kept", "x"))
                    .retire("old")
                    .retire("edited")
                    .retire("screen")
                    .discard("gone")
                    .build();

            Map<String, Object> onDisk = tree("old", "shipped", "edited", "mine", "gone", "mine too");
            onDisk.put("screen", tree("title", "Top", "size", 27));
            MigrationResult result = migration.migrate(onDisk);

            return check("retire and discard", !onDisk.containsKey("old") && "mine".equals(onDisk.get("edited"))
                    && !onDisk.containsKey("gone") && onDisk.containsKey("screen")
                    && result.getRemovedPaths().equals(List.of("old", "gone"))
                    && result.getKeptPaths().equals(List.of("edited", "screen"))
                    && result.getDiscardedPaths().equals(List.of("gone")));
        } catch (Exception exception) {
            return failure("testRetireAndDiscard", exception);
        }
    }

    /**
     * Tests the union rule for additions: a path no shipped file had is added beside its neighbours; a shipped path,
     * a path added before, a path without its section and a present path are not.
     */
    public static boolean testAdditionUnionRule() {
        try {
            Map<String, Object> current = tree("alpha", "a", "beta", "b", "gamma", "c", "delta", "d");
            current.put("nested", tree("one", "1", "two", "2"));
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(tree("alpha", "a", "delta", "d", "gamma", "c"))))
                    .current(current)
                    .add("beta")
                    .add("gamma")
                    .add("delta")
                    .add("nested.two")
                    .add("missing.child")
                    .build();

            Map<String, Object> onDisk = tree("alpha", "a", "delta", "d");
            onDisk.put("nested", tree("one", "1"));
            MigrationResult result = migration.migrate(onDisk, Set.of("nested.two"));

            boolean placed = List.of("alpha", "beta", "delta", "nested").equals(new ArrayList<>(onDisk.keySet()))
                    && "b".equals(onDisk.get("beta"));

            return check("additions", placed
                    && result.getAddedPaths().equals(List.of("beta"))
                    && result.getAbsentPaths().equals(List.of("gamma", "nested.two"))
                    && result.getPresentPaths().equals(List.of("delta"))
                    && !ConfigurationTreeUtil.contains(onDisk, "nested.two"));
        } catch (Exception exception) {
            return failure("testAdditionUnionRule", exception);
        }
    }

    /**
     * Tests that an addition whose earlier neighbours are missing goes before its nearest later neighbour, and that a
     * path whose section is missing is reported as orphaned.
     */
    public static boolean testAdditionPlacementAndOrphans() {
        try {
            Map<String, Object> current = tree("first", "1", "second", "2", "third", "3");
            current.put("section", tree("child", "c"));
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .current(current)
                    .add("first")
                    .add("section.child")
                    .build();

            Map<String, Object> onDisk = tree("zero", "0", "third", "3");
            MigrationResult result = migration.migrate(onDisk);

            return check("placement", List.of("zero", "first", "third").equals(new ArrayList<>(onDisk.keySet()))
                    && result.getAddedPaths().equals(List.of("first"))
                    && result.getOrphanedPaths().equals(List.of("section.child")));
        } catch (Exception exception) {
            return failure("testAdditionPlacementAndOrphans", exception);
        }
    }

    /**
     * Tests that steps run once, in order of version, only for versions above the file's stamp.
     */
    public static boolean testStepsRunByVersion() {
        try {
            List<Integer> ran = new ArrayList<>();
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .versionKey("schemaVersion")
                    .version(4)
                    .step(4, (tree, changes) -> {
                        ran.add(4);
                        changes.add("step 4");
                    })
                    .step(2, (tree, changes) -> ran.add(2))
                    .step(3, (tree, changes) -> {
                        ran.add(3);
                        tree.put("seats", 4);
                        changes.add("seats 3 -> 4");
                    })
                    .build();

            Map<String, Object> onDisk = tree("schemaVersion", 2, "seats", 3);
            MigrationResult result = migration.migrate(onDisk);

            return check("steps", ran.equals(List.of(3, 4))
                    && Integer.valueOf(4).equals(onDisk.get("seats"))
                    && result.getStepChanges().equals(List.of("seats 3 -> 4", "step 4"))
                    && result.getFromVersion() == 2 && result.getToVersion() == 4);
        } catch (Exception exception) {
            return failure("testStepsRunByVersion", exception);
        }
    }

    /**
     * Tests that a migration that would reset every default on every load is refused.
     */
    public static boolean testRewriteRequiresCurrentWithoutStamp() {
        try {
            ConfigurationMigration migration = ConfigurationMigration.builder().build();
            try {
                migration.migrate(tree("a", "b"));
                return check("refused without current tree", false);
            } catch (IllegalStateException expected) {
                return true;
            }
        } catch (Exception exception) {
            return failure("testRewriteRequiresCurrentWithoutStamp", exception);
        }
    }

    /**
     * Tests baselines built from a history file, in which each leaf lists the values shipped at that path.
     */
    public static boolean testRewriteFromHistoryBaselines() {
        try {
            Map<String, Object> history = new LinkedHashMap<>();
            history.put("messages", tree("join", List.of("&7Hi", "&8Hi")));
            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.ofHistory(history))
                    .current(Map.of("messages", tree("join", "<gray>Hi", "slot", 4)))
                    .build();

            Map<String, Object> onDisk = new LinkedHashMap<>();
            onDisk.put("messages", tree("join", "&8Hi", "slot", 7));
            MigrationResult result = migration.migrate(onDisk);

            return check("history baselines", "<gray>Hi".equals(ConfigurationTreeUtil.get(onDisk, "messages.join"))
                    && Integer.valueOf(7).equals(ConfigurationTreeUtil.get(onDisk, "messages.slot"))
                    && result.getRewrittenPaths().equals(List.of("messages.join")));
        } catch (Exception exception) {
            return failure("testRewriteFromHistoryBaselines", exception);
        }
    }

    private static Map<String, Object> tree(Object... keysAndValues) {
        Map<String, Object> tree = new LinkedHashMap<>();
        for (int index = 0; index < keysAndValues.length; index += 2) {
            tree.put((String) keysAndValues[index], keysAndValues[index + 1]);
        }
        return tree;
    }

    private static boolean check(String what, boolean condition) {
        if (!condition) {
            TestLogger.logFailure(MODULE_NAME, "Check failed: %s", what);
        }
        return condition;
    }

    private static boolean failure(String testName, Exception exception) {
        TestLogger.logFailure(MODULE_NAME, "%s failed: %s", testName, exception.getMessage());
        return false;
    }

}
