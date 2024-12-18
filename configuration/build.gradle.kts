fun properties(key: String) = project.findProperty(key).toString()
fun rootProperties(key: String) = rootProject.findProperty(key).toString()

group = rootProperties("group")
version = rootProperties("version")

// Run server
runServer {
    version.set(rootProperties("spigot.version"))
    javaVersion.set(JavaVersion.VERSION_21)
}

// Fairy configuration
fairy {
    name.set(properties("name"))
    mainPackage.set(properties("package"))
    fairyPackage.set("io.fairyproject")

    bukkitProperties().depends.add("annotation")
    bukkitProperties().depends.add("fairy-lib-plugin")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    compileOnly(project(":annotation")) {
        implementation("org.reflections:reflections:0.10.2")
    }

    // SimplixStorage
    implementation("com.github.simplix-softworks:simplixstorage:3.2.7")

    // Apache commons io
    implementation("commons-io:commons-io:2.18.0")
}