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
    bukkitProperties().depends.add("foundation")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // Foundation module
    compileOnly(project(":foundation"))

    // Annotation module
    compileOnly(project(":annotation"))

    // https://mvnrepository.com/artifact/org.apache.commons/commons-math3
    implementation("org.apache.commons:commons-math3:3.6.1")

    // https://github.com/WesJD/AnvilGUI
    implementation("net.wesjd:anvilgui:1.10.5-SNAPSHOT")
}