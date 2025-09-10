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
    // Foundation module
    compileOnly(project(":foundation"))
    
    // https://mvnrepository.com/artifact/org.openjdk.nashorn/nashorn-core
    implementation("org.openjdk.nashorn:nashorn-core:15.6")

    // https://mvnrepository.com/artifact/com.caoccao.javet/javet
    implementation("com.caoccao.javet:javet:4.1.5")
    implementation("com.caoccao.javet:javet-v8-linux-x86_64:4.1.5")
    implementation("com.caoccao.javet:javet-v8-windows-x86_64:4.1.5")
    implementation("com.caoccao.javet:javet-v8-macos-x86_64:4.1.5")
    implementation("com.caoccao.javet:javet-v8-macos-arm64:4.1.5")

    // https://mvnrepository.com/artifact/org.mozilla/rhino
    implementation("org.mozilla:rhino:1.8.0")

    // https://mvnrepository.com/artifact/org.apache.groovy/groovy-all
    implementation("org.apache.groovy:groovy-all:5.0.0")
}