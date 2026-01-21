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
    bukkitProperties().depends.add("foundation")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // Foundation module for testing infrastructure
    compileOnly(project(":foundation"))

    // https://mvnrepository.com/artifact/com.github.ben-manes.caffeine/caffeine
    api("com.github.ben-manes.caffeine:caffeine:3.2.0")

    // https://mvnrepository.com/artifact/org.redisson/redisson
    api("org.redisson:redisson:3.52.0")
}