fun properties(key: String) = project.findProperty(key).toString()
fun rootProperties(key: String) = rootProject.findProperty(key).toString()

group = rootProperties("group")
version = rootProperties("version")

// Run server
runServer {
    version.set("1.20.1")
    javaVersion.set(JavaVersion.VERSION_21)
}

// Fairy configuration
fairy {
    name.set(properties("name"))
    mainPackage.set(properties("package"))
    fairyPackage.set("io.fairyproject")

    bukkitProperties().depends.add("fairy-lib-plugin")
    bukkitProperties().depends.add("configuration")
    bukkitProperties().depends.add("annotation")
    bukkitProperties().depends.add("commons")
    bukkitProperties().depends.add("mongodb")
    bukkitProperties().depends.add("cache")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // Annotation module
    compileOnly(project(":annotation"))

    // Configuration module
    compileOnly(project(":configuration"))

    // Cache module
    compileOnly(project(":cache"))

    // Commons module
    compileOnly(project(":commons"))

    // Mongodb module
    compileOnly(project(":mongodb"))
}