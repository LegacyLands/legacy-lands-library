import com.github.jengelman.gradle.plugins.shadow.tasks.ShadowJar
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.*

fun properties(key: String) = project.findProperty(key).toString()
val isGitHubActions = project.hasProperty("isGitHubActions") && project.property("isGitHubActions") == "true"

group = properties("group")
version = properties("version")

plugins {
    // Java plugin
    id("java-library") apply true

    // Fairy framework plugin
    id("io.fairyproject") version "0.7.13b3-SNAPSHOT" apply false

    // Dependency management plugin
    id("io.spring.dependency-management") version "1.1.7"

    // Kotlin plugin
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false

    // Shadow plugin, provides the ability to shade fairy and other dependencies to compiled jar
    id("com.github.johnrengelman.shadow") version "8.1.1" apply true

    // Lombok
    id("io.freefair.lombok") version "8.13.1" apply false

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
        compileOnly("org.spigotmc:spigot-api:${properties("spigot.version")}-R0.1-SNAPSHOT")
        implementation("org.apache.commons:commons-lang3:3.17.0")
    }

    // Configure ShadowJar task
    tasks.withType(ShadowJar::class) {
        dependencies {
            exclude(dependency("com.google.code.gson:.*:.*"))
        }

        // Relocate fairy to avoid plugin conflict
        relocate("io.fairyproject.bootstrap", "${properties("package")}.fairy.bootstrap")
        relocate("net.kyori", "io.fairyproject.libs.kyori")
        relocate("com.cryptomorin.xseries", "io.fairyproject.libs.xseries")
        relocate("org.yaml.snakeyaml", "io.fairyproject.libs.snakeyaml")
        relocate("com.google.gson", "io.fairyproject.libs.gson")
        relocate("com.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")
        relocate("io.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")
        relocate("io.fairyproject.bukkit.menu", "${properties("package")}.fairy.menu")

        // Relocate
        relocate("de.leonhard.storage", "${properties("package")}.libs.simplixstorage")
        relocate("org.reflections", "${properties("package")}.libs.reflections")
        relocate("net.wesjd.anvilgui", "${properties("package")}.libs.anvilgui")

        archiveClassifier.set("plugin")
        mergeServiceFiles()
        exclude("META-INF/maven/**")
    }

    // Configure sourcesJar task
    tasks.register<Jar>("sourcesJar") {
        exclude("fairy.json")
        dependsOn(tasks.named("shadowJar"))
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
        from(zipTree(tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile))
        from(sourceSets.main.get().allSource)
        archiveClassifier.set("sources")
    }

    // Disable Javadoc warnings
    tasks.withType<Javadoc> {
        options {
            this as StandardJavadocDocletOptions
            addStringOption("Xdoclint:-missing", "-quiet")
        }
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
            rootProject.subprojects.forEach { subproject ->
                create<MavenPublication>("shadow-${subproject.name.replaceFirstChar { if (it.isLowerCase()) it.titlecase(Locale.getDefault()) else it.toString() }}") {
                    val shadowJarTask = subproject.tasks.named<ShadowJar>("shadowJar")
                    artifact(shadowJarTask.get().archiveFile.get())

                    groupId = group.toString()
                    artifactId = subproject.name
                    version = "${properties("version")}-${LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yy-hhmmss"))}"
                    description = ""
                    pom {
                        description.set("")
                    }
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