fun properties(key: String) = project.findProperty(key).toString()
fun rootProperties(key: String) = rootProject.findProperty(key).toString()

group = rootProperties("group")
version = rootProperties("version")

// Run server
runServer {
    javaVersion.set(JavaVersion.VERSION_21)
    version.set(rootProperties("run-server.version"))
}

// Fairy configuration
fairy {
    name.set(properties("name"))
    mainPackage.set(properties("package"))
    fairyPackage.set("io.fairyproject")

    bukkitProperties().depends.add("fairy-lib-plugin")
    bukkitProperties().depends.add("annotation")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // Annotation module
    compileOnly(project(":annotation"))

    // SimplixStorage
    api("com.github.simplix-softworks:simplixstorage:3.2.7")
}