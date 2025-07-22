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

    // Scala
    id("scala") apply true

    // Fairy framework plugin
    id("io.fairyproject") version "0.8b1-SNAPSHOT" apply false

    // Dependency management plugin
    id("io.spring.dependency-management") version "1.1.7"

    // Kotlin plugin
    id("org.jetbrains.kotlin.jvm") version "2.1.21" apply false

    // Shadow plugin, provides the ability to shade fairy and other dependencies to compiled jar
    id("com.gradleup.shadow") version "9.0.0-rc1" apply true

    // Lombok
    id("io.freefair.lombok") version "8.13.1" apply false

    // Maven publish
    id("maven-publish")
}

allprojects {
    // Apply Shadow plugin
    apply(plugin = "com.gradleup.shadow")

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

dependencies {
    compileOnly("org.scala-lang:scala3-library_3:3.7.1")
}

subprojects {
    // Apply necessary plugins
    apply(plugin = "java-library")
    apply(plugin = "scala")
    apply(plugin = "io.fairyproject")
    apply(plugin = "io.spring.dependency-management")
    apply(plugin = "org.jetbrains.kotlin.jvm")
    apply(plugin = "com.gradleup.shadow")
    apply(plugin = "io.freefair.lombok")

    // Configure dependencies
    dependencies {
        val depend by if (project.name != "foundation") {
            configurations.compileOnly
        } else {
            configurations.api
        }

        api("io.fairyproject:bukkit-bootstrap")
        compileOnlyApi("io.fairyproject:bukkit-platform")
        compileOnly("org.spigotmc:spigot-api:${properties("spigot.version")}-R0.1-SNAPSHOT")

        depend("org.scala-lang:scala3-library_3:3.7.1")
        depend("org.apache.commons:commons-lang3:3.17.0")
        depend("com.google.guava:guava:33.4.0-jre")

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
    }

    // Configure ShadowJar task
    tasks.withType(ShadowJar::class) {
        dependencies {
            exclude(dependency("com.google.code.gson:.*:.*"))

            // Exclude Kotlin
            exclude(dependency("org.jetbrains.kotlin:.*:.*"))
            exclude(dependency("org.jetbrains:annotations:.*"))

            // Exclude SLF4J
            exclude(dependency("org.slf4j:.*:.*"))

            // Exclude dependencies that would be relocated to io.fairyproject.libs
            exclude(dependency("net.kyori:.*:.*"))
            exclude(dependency("com.cryptomorin.xseries:.*:.*"))
            exclude(dependency("org.yaml:snakeyaml:.*"))
            exclude(dependency("com.github.retrooper:packetevents:.*"))
            exclude(dependency("io.github.retrooper:packetevents:.*"))
        }

        // Exclude all io.fairyproject.libs packages since they exist in fairy lib plugin
        exclude("io/fairyproject/libs/**")

        // Relocate fairy to avoid plugin conflict
        val libsPackage = "${properties("package")}.libs"

        relocate("net.kyori", "io.fairyproject.libs.kyori")
        relocate("com.cryptomorin.xseries", "io.fairyproject.libs.xseries")
        relocate("org.yaml.snakeyaml", "io.fairyproject.libs.snakeyaml")
        relocate("com.google.gson", "io.fairyproject.libs.gson")
        relocate("com.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")
        relocate("io.github.retrooper.packetevents", "io.fairyproject.libs.packetevents")

        // Relocate
        relocate("scala", "$libsPackage.scala")

        relocate("io.fairyproject.bootstrap", "$libsPackage.io.fairyproject.bootstrap")
        relocate("io.fairyproject.bukkit.menu", "$libsPackage.io.fairyproject.bukkit.menu")

        relocate("de.leonhard.storage", "$libsPackage.de.leonhard.storage")
        relocate("net.wesjd.anvilgui", "$libsPackage.net.wesjd.anvilgui")

        archiveClassifier.set("plugin")
        mergeServiceFiles()

        exclude("META-INF/maven/**")
        exclude("**/*.tasty")

        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Configure source sets for Scala/Java joint compilation
    sourceSets {
        main {
            java {
                setSrcDirs(emptyList<String>())
            }
            scala {
                setSrcDirs(listOf("src/main/java", "src/main/scala"))
            }
        }
    }

    // Configure task dependencies and order
    tasks.named<JavaCompile>("compileJava") {
        enabled = false
    }

    // Configure Scala compilation to output to classes directory
    tasks.named<ScalaCompile>("compileScala") {
        // Ensure clean output directory
        doFirst {
            destinationDirectory.get().asFile.deleteRecursively()
            destinationDirectory.get().asFile.mkdirs()
        }
    }

    // Configure sourcesJar task
    tasks.register<Jar>("sourcesJar") {
        exclude("fairy.json")
        // Check if shadowJar task exists before depending on it
        if (tasks.names.contains("shadowJar")) {
            dependsOn(tasks.named("shadowJar"))
            from(zipTree(tasks.named<ShadowJar>("shadowJar").get().archiveFile.get().asFile))
        }
        from(sourceSets.main.get().allSource)
        archiveClassifier.set("sources")
        duplicatesStrategy = DuplicatesStrategy.EXCLUDE
    }

    // Configure Javadoc for Scala/Java mixed projects
    tasks.withType<Javadoc> {
        // Ensure compilation is done before javadoc
        dependsOn(tasks.named("compileScala"))

        // Use classpath from compileScala to resolve Lombok-generated classes
        classpath = sourceSets.main.get().compileClasspath + files(sourceSets.main.get().output)

        options {
            this as StandardJavadocDocletOptions
            addStringOption("Xdoclint:-missing", "-quiet")
            addStringOption("Xdoclint:-html", "-quiet")
            addStringOption("Xdoclint:-reference", "-quiet")
        }

        // Disable failOnError for modules with Lombok
        isFailOnError = false
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

    // After project evaluation, configure shadowJar dependencies
    afterEvaluate {
        tasks.withType<ShadowJar> {
            // Find all project dependencies and make shadowJar depend on their shadowJar tasks
            project.configurations.findByName("compileClasspath")?.allDependencies?.forEach { dep ->
                if (dep is ProjectDependency) {
                    val depProject = rootProject.allprojects.find { it.name == dep.name }
                    if (depProject != null && depProject != project) {
                        dependsOn(depProject.tasks.named("shadowJar"))
                    }
                }
            }
        }
    }
}

publishing {
    if (isGitHubActions) {
        publications {
            rootProject.subprojects.forEach { subproject ->
                create<MavenPublication>(
                    "shadow-${
                        subproject.name.replaceFirstChar {
                            if (it.isLowerCase()) it.titlecase(
                                Locale.getDefault()
                            ) else it.toString()
                        }
                    }"
                ) {
                    val shadowJarTask = subproject.tasks.named<ShadowJar>("shadowJar")
                    artifact(shadowJarTask.get().archiveFile.get())

                    groupId = group.toString()
                    artifactId = subproject.name
                    version = "${properties("version")}-${
                        LocalDateTime.now().format(DateTimeFormatter.ofPattern("dd-MM-yy-hhmmss"))
                    }"
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
                    username =
                        project.findProperty("githubUsername")?.toString() ?: System.getenv("GITHUB_USERNAME") ?: error(
                            "GitHub username is missing"
                        )
                    password =
                        project.findProperty("githubToken")?.toString() ?: System.getenv("GITHUB_TOKEN")
                                ?: error("GitHub token is missing")
                }
            }
        }
    }
}