package net.legacy.library.configuration.test;

import de.leonhard.storage.Yaml;
import net.legacy.library.configuration.factory.SimplixBuilderFactory;
import net.legacy.library.configuration.migration.ConfigurationMigration;
import net.legacy.library.configuration.migration.MigrationResult;
import net.legacy.library.configuration.migration.ShippedBaselines;
import net.legacy.library.configuration.migration.SimplixMigrationUtil;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

/**
 * Test class for {@link SimplixMigrationUtil}, validating the migration of a SimplixStorage file built by
 * {@link SimplixBuilderFactory}.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "simplix-migration-util-test",
        description = "Tests SimplixMigrationUtil migration of SimplixStorage files with comments",
        tags = {"configuration", "migration", "simplix", "file"},
        priority = 3,
        timeout = 5000,
        expectedResult = "SUCCESS"
)
public class SimplixMigrationUtilTest {

    private static final String MODULE_NAME = "configuration";

    /**
     * Tests that a shipped value is rewritten, the operator's comment and edit survive, a backup is kept, and the
     * second migration does nothing.
     */
    public static boolean testSimplixFileMigrationKeepsComments() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("legacy-configuration-test");
            Path file = directory.resolve("config.yml");
            String original = "# Operator note\njoin: '&7Welcome'\nquit: See you\n";
            Files.writeString(file, original, StandardCharsets.UTF_8);
            Yaml yaml = SimplixBuilderFactory.createSimplixBuilder("config", directory.toString()).createYaml();

            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(Map.<String, Object>of("join", "&7Welcome", "quit", "Bye"))))
                    .current(Map.of("join", "<gray>Welcome", "quit", "Bye"))
                    .build();

            MigrationResult first = SimplixMigrationUtil.migrate(yaml, migration);
            String written = Files.readString(file, StandardCharsets.UTF_8);
            boolean firstOk = first.getRewrittenPaths().equals(List.of("join"))
                    && first.getKeptPaths().equals(List.of("quit"))
                    && "<gray>Welcome".equals(yaml.getString("join")) && "See you".equals(yaml.getString("quit"))
                    && written.contains("# Operator note")
                    && original.equals(Files.readString(directory.resolve("config.yml.pre-migration.bak")));

            MigrationResult second = SimplixMigrationUtil.migrate(yaml, migration);

            return check("simplix migration", firstOk && !second.isChanged()
                    && YamlMigrationUtilTest.countFiles(directory) == 2);
        } catch (Exception exception) {
            return failure("testSimplixFileMigrationKeepsComments", exception);
        } finally {
            YamlMigrationUtilTest.deleteQuietly(directory);
        }
    }

    /**
     * Tests that a missing resource reads as {@code null} rather than failing.
     */
    public static boolean testSimplixMissingResourceIsNull() {
        try {
            return check("missing resource", SimplixMigrationUtil.readResource(
                    SimplixMigrationUtilTest.class.getClassLoader(), "/no/such/baseline.yml") == null);
        } catch (Exception exception) {
            return failure("testSimplixMissingResourceIsNull", exception);
        }
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
