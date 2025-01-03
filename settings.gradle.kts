pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
        maven("https://repo.imanity.dev/imanity-libraries")
    }
}

rootProject.name = "legacy-lands-library"
include("configuration")
include("annotation")
include("commons")
include("mongodb")
include("cache")
include("player")
