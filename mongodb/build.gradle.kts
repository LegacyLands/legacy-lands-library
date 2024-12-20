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

    bukkitProperties().depends.add("fairy-lib-plugin")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // https://mvnrepository.com/artifact/dev.morphia.morphia/morphia-core
    api("dev.morphia.morphia:morphia-core:2.4.14")
}