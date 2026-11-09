package net.legacy.library.configuration.test;

import net.legacy.library.configuration.migration.ConfigurationMigration;
import net.legacy.library.configuration.migration.MigrationResult;
import net.legacy.library.configuration.migration.ShippedBaselines;
import net.legacy.library.configuration.migration.YamlMigrationUtil;
import net.legacy.library.foundation.annotation.ModuleTest;
import net.legacy.library.foundation.util.TestLogger;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/**
 * Test class for {@link YamlMigrationUtil}, validating YAML reading and writing and the migration of files on disk.
 *
 * <p>Covers keys written with nothing after them, {@code on}/{@code off} keys, backups that are never replaced,
 * stamped files, the record of added paths and files that cannot be read.
 *
 * @author qwq-dev
 * @since 2026-11-09 12:00
 */
@ModuleTest(
        testName = "yaml-migration-util-test",
        description = "Tests YamlMigrationUtil reading, writing, backups and file migration",
        tags = {"configuration", "migration", "yaml", "file"},
        priority = 2,
        timeout = 5000,
        expectedResult = "SUCCESS"
)
public class YamlMigrationUtilTest {

    private static final String MODULE_NAME = "configuration";

    /**
     * Tests that a key written with nothing after it is read as {@code null} and written back as {@code key:}.
     */
    public static boolean testYamlEmptyKeyRoundTrip() {
        try {
            Map<String, Object> tree = YamlMigrationUtil.read("# heading\nmanual-entries:\nname: value\n");
            String written = YamlMigrationUtil.write(tree);
            Map<String, Object> reread = YamlMigrationUtil.read(written);

            return check("empty key round trip", tree.containsKey("manual-entries") && tree.get("manual-entries") == null
                    && !written.contains("null")
                    && written.lines().anyMatch(line -> line.strip().equals("manual-entries:"))
                    && reread.containsKey("manual-entries") && reread.get("manual-entries") == null);
        } catch (Exception exception) {
            return failure("testYamlEmptyKeyRoundTrip", exception);
        }
    }

    /**
     * Tests that {@code on} and {@code off} keys stay text through a round trip while values keep the loader's reading.
     */
    public static boolean testYamlOnOffKeysStayText() {
        try {
            Map<String, Object> tree = YamlMigrationUtil.read("sounds:\n  on: click\n  off: clack\n1: number\nflag: on\n");
            @SuppressWarnings("unchecked")
            Map<String, Object> sounds = (Map<String, Object>) tree.get("sounds");
            boolean readAsText = "click".equals(sounds.get("on")) && "clack".equals(sounds.get("off"))
                    && "number".equals(tree.get("1")) && Boolean.TRUE.equals(tree.get("flag"));

            Map<String, Object> reread = YamlMigrationUtil.read(YamlMigrationUtil.write(tree));
            @SuppressWarnings("unchecked")
            Map<String, Object> rereadSounds = (Map<String, Object>) reread.get("sounds");

            return check("on/off keys", readAsText && "click".equals(rereadSounds.get("on"))
                    && "clack".equals(rereadSounds.get("off")) && Boolean.TRUE.equals(reread.get("flag")));
        } catch (Exception exception) {
            return failure("testYamlOnOffKeysStayText", exception);
        }
    }

    /**
     * Tests a stamped file migration: the backup holds the original, the file is rewritten and stamped, and the
     * second migration neither writes nor backs up again.
     */
    public static boolean testYamlStampedFileMigration() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("legacy-configuration-test");
            Path file = directory.resolve("messages.yml");
            String original = "join: '&7Welcome'\nquit: Bye for now\n";
            Files.writeString(file, original, StandardCharsets.UTF_8);

            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(YamlMigrationUtil.read("join: '&7Welcome'\nquit: '&7Bye'\n"))))
                    .versionKey("schemaVersion")
                    .version(2)
                    .build();

            MigrationResult first = YamlMigrationUtil.migrate(file, migration);
            Path backup = directory.resolve("messages.yml.schema-1.bak");
            Map<String, Object> migrated = YamlMigrationUtil.read(file);
            boolean firstOk = first.isChanged() && Files.exists(backup)
                    && original.equals(Files.readString(backup, StandardCharsets.UTF_8))
                    && !migrated.containsKey("join") && "Bye for now".equals(migrated.get("quit"))
                    && Integer.valueOf(2).equals(migrated.get("schemaVersion"))
                    && !Files.exists(directory.resolve("messages.yml.migrating"));

            String afterFirst = Files.readString(file, StandardCharsets.UTF_8);
            MigrationResult second = YamlMigrationUtil.migrate(file, migration);

            return check("stamped file migration", firstOk && !second.isChanged()
                    && afterFirst.equals(Files.readString(file, StandardCharsets.UTF_8))
                    && countFiles(directory) == 2);
        } catch (Exception exception) {
            return failure("testYamlStampedFileMigration", exception);
        } finally {
            deleteQuietly(directory);
        }
    }

    /**
     * Tests that an existing backup is never replaced.
     */
    public static boolean testYamlBackupNeverReplaced() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("legacy-configuration-test");
            Path file = directory.resolve("config.yml");
            Files.writeString(file, "a: old\n", StandardCharsets.UTF_8);
            Files.writeString(directory.resolve("config.yml.pre-migration.bak"), "earlier", StandardCharsets.UTF_8);

            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(Map.<String, Object>of("a", "old"))))
                    .current(Map.of("a", "new"))
                    .build();
            MigrationResult result = YamlMigrationUtil.migrate(file, migration);

            return check("backup kept", result.isChanged()
                    && "earlier".equals(Files.readString(directory.resolve("config.yml.pre-migration.bak")))
                    && "a: old\n".equals(Files.readString(directory.resolve("config.yml.pre-migration-2.bak")))
                    && "new".equals(YamlMigrationUtil.read(file).get("a")));
        } catch (Exception exception) {
            return failure("testYamlBackupNeverReplaced", exception);
        } finally {
            deleteQuietly(directory);
        }
    }

    /**
     * Tests that the record of added paths stops a path an operator deleted after it was added from coming back.
     */
    public static boolean testYamlInsertionRecordRemembersDeletions() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("legacy-configuration-test");
            Path file = directory.resolve("config.yml");
            Path record = directory.resolve("migration-record.txt");
            Files.writeString(file, "a: 1\n", StandardCharsets.UTF_8);

            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .current(YamlMigrationUtil.read("a: 1\nb: 2\n"))
                    .add("b")
                    .build();

            MigrationResult first = YamlMigrationUtil.migrate(file, migration, record);
            boolean added = first.getAddedPaths().equals(List.of("b"))
                    && Integer.valueOf(2).equals(YamlMigrationUtil.read(file).get("b"))
                    && Files.readAllLines(record).contains("config.yml b");

            // The operator deletes the new key
            Files.writeString(file, "a: 1\n", StandardCharsets.UTF_8);
            MigrationResult second = YamlMigrationUtil.migrate(file, migration, record);

            return check("record", added && !second.isChanged() && second.getAbsentPaths().equals(List.of("b"))
                    && !YamlMigrationUtil.read(file).containsKey("b"));
        } catch (Exception exception) {
            return failure("testYamlInsertionRecordRemembersDeletions", exception);
        } finally {
            deleteQuietly(directory);
        }
    }

    /**
     * Tests that a missing file and a file that cannot be parsed are left alone.
     */
    public static boolean testYamlUnreadableFileLeftAlone() {
        Path directory = null;
        try {
            directory = Files.createTempDirectory("legacy-configuration-test");
            Path file = directory.resolve("broken.yml");
            String broken = "a: [unclosed\n  b: c\n";
            Files.writeString(file, broken, StandardCharsets.UTF_8);

            ConfigurationMigration migration = ConfigurationMigration.builder()
                    .baselines(ShippedBaselines.of(List.of(Map.<String, Object>of("a", "x"))))
                    .versionKey("schemaVersion")
                    .version(2)
                    .build();

            MigrationResult brokenResult = YamlMigrationUtil.migrate(file, migration);
            MigrationResult missingResult = YamlMigrationUtil.migrate(directory.resolve("missing.yml"), migration);

            return check("left alone", !brokenResult.isChanged() && !missingResult.isChanged()
                    && broken.equals(Files.readString(file, StandardCharsets.UTF_8)) && countFiles(directory) == 1);
        } catch (Exception exception) {
            return failure("testYamlUnreadableFileLeftAlone", exception);
        } finally {
            deleteQuietly(directory);
        }
    }

    /**
     * Tests that baselines read from resources skip a missing resource.
     */
    public static boolean testYamlBaselinesSkipMissingResources() {
        try {
            ShippedBaselines baselines = YamlMigrationUtil.readBaselines(
                    YamlMigrationUtilTest.class.getClassLoader(), "/no/such/baseline.yml", "plugin.yml");

            return check("missing resources skipped", baselines.isKnown("name") && !baselines.isKnown("no-such-key"));
        } catch (Exception exception) {
            return failure("testYamlBaselinesSkipMissingResources", exception);
        }
    }

    static long countFiles(Path directory) throws IOException {
        try (Stream<Path> files = Files.list(directory)) {
            return files.count();
        }
    }

    static void deleteQuietly(Path directory) {
        if (directory == null) {
            return;
        }

        try (Stream<Path> paths = Files.walk(directory)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> path.toFile().delete());
        } catch (IOException exception) {
            TestLogger.logWarning(MODULE_NAME, "Could not delete %s: %s", directory, exception.getMessage());
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
