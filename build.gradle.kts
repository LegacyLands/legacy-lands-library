import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import org.jetbrains.kotlin.gradle.plugin.mpp.pm20.util.archivesName
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

fun properties(key: String) = project.findProperty(key).toString()
val isGitHubActions = project.hasProperty("isGitHubActions") && project.property("isGitHubActions") == "true"

group = properties("group")
version = properties("version")
val modules = rootProject.subprojects.map { it.name }

plugins {
    // Java plugin
    id("java-library") apply true

    // Fairy framework plugin
    id("io.fairyproject") version "0.7.10b2-SNAPSHOT" apply false

    // Dependency management plugin
    id("io.spring.dependency-management") version "1.1.0"

    // Kotlin plugin
    id("org.jetbrains.kotlin.jvm") version "1.9.23" apply false

    // Shadow plugin, provides the ability to shade fairy and other dependencies to compiled jar
    id("com.github.johnrengelman.shadow") version "8.1.1" apply true

    // Lombok
    id("io.freefair.lombok") version "8.11" apply false

    // Maven publish
    id("maven-publish")
}

allprojects {
    // Apply Shadow plugin
    apply(plugin = "com.github.johnrengelman.shadow")

    // Configure repositories
    repositories {
        mavenCentral()
        maven(url = uri("https://oss.sonatype.org/content/repositories/snapshots/"))
        maven(url = uri("https://repo.codemc.io/repository/maven-public/"))
        maven(url = uri("https://hub.spigotmc.org/nexus/content/repositories/snapshots/"))
        maven(url = uri("https://repo.imanity.dev/imanity-libraries"))
        maven(url = uri("https://jitpack.io/"))
        maven(url = uri("https://repo.papermc.io/repository/maven-public/"))
    }
}
java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}
subprojects {
    // Apply necessary plugins
    apply(plugin = "java-library")
    apply(plugin = "io.fairyproject")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.github.johnrengelman.shadow")
    apply(plugin = "io.freefair.lombok")

    // Configure dependencies
    dependencies {
        compileOnlyApi("io.fairyproject:bukkit-platform")
        api("io.fairyproject:bukkit-bootstrap")
        compileOnlyApi("io.fairyproject:mc-animation")
        compileOnlyApi("io.fairyproject:bukkit-command")
        compileOnlyApi("io.fairyproject:bukkit-gui")
        compileOnlyApi("io.fairyproject:mc-hologram")
        compileOnlyApi("io.fairyproject:core-config")
        compileOnlyApi("io.fairyproject:bukkit-xseries")
        compileOnlyApi("io.fairyproject:bukkit-items")
        compileOnlyApi("io.fairyproject:mc-nametag")
        compileOnlyApi("io.fairyproject:mc-sidebar")
        compileOnlyApi("io.fairyproject:bukkit-visibility")
        compileOnlyApi("io.fairyproject:bukkit-visual")
        compileOnlyApi("io.fairyproject:bukkit-timer")
        compileOnlyApi("io.fairyproject:bukkit-nbt")
        compileOnlyApi("io.fairyproject:mc-tablist")
        compileOnlyApi("dev.folia:folia-api:${properties("spigot.version")}-R0.1-SNAPSHOT")
    }

    // Configure ShadowJar task
    tasks.withType(ShadowJar::class) {
        // Relocate fairy to avoid plugin conflict
        relocate("io.fairyproject.bootstrap", "${properties("package")}.fairy.bootstrap")
        relocate("net.kyori", "io.fairyproject.libs.kyori")
        relocate("com.cryptomorin.xseries", "io.fairyproject.libs.xseries")
        relocate("org.yaml.snakeyaml", "io.fairyproject.libs.snakeyaml")
        relocate("com.google.gson", "io.fairyproject.libs.gson")
        relocate("com.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")
        relocate("io.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")
        relocate("io.fairyproject.bukkit.menu", "${properties("package")}.fairy.menu")
        archiveClassifier.set("plugin")
        exclude("META-INF/maven/**")
    }

    // Configure sourcesJar task
    tasks.register<Jar>("sourcesJar") {
        from(tasks.named<ShadowJar>("shadowJar").get().source)
        from(sourceSets.main.get().allSource)
        archiveClassifier.set("sources")
    }

    // Configure javadocJar task
    tasks.register<Jar>("javadocJar") {
        dependsOn(tasks.named("javadoc"))
        from(tasks.named("javadoc"))
        archiveClassifier.set("javadoc")
    }

    tasks.register("allJar") {
        dependsOn("shadowJar", "sourcesJar", "javadocJar")
    }

}

publishing {
    if (isGitHubActions) {
        publications {
            modules.forEach { module ->
                create<MavenPublication>("shadow-${module.capitalize()}") {
                    val shadowJarTask = project(":$module").tasks.named<ShadowJar>("shadowJar")
                    artifact(shadowJarTask.get().archiveFile.get())

                    groupId = group.toString()
                    artifactId = "$module"
                    version = "${properties("version")}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yy-hhmmss"))}"
                    description = ""
                }
            }
        }

        // GitHub Packages
        repositories {
            maven {
                url = uri("https://maven.pkg.github.com/LegacyLands/legacy-lands-library/")
                credentials {
                    username = project.findProperty("githubUsername")?.toString() ?: System.getenv("GITHUB_USERNAME")?.toString() ?: error("GitHub username is missing")
                    password = project.findProperty("githubToken")?.toString() ?: System.getenv("GITHUB_TOKEN")?.toString() ?: error("GitHub token is missing")
                }
            }
        }
    }
}