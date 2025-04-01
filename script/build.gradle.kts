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

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Dependencies
dependencies {
    // https://mvnrepository.com/artifact/org.openjdk.nashorn/nashorn-core
    implementation("org.openjdk.nashorn:nashorn-core:15.6")

    // https://mvnrepository.com/artifact/com.eclipsesource.j2v8/j2v8
    implementation("com.eclipsesource.j2v8:j2v8:6.2.1")

    // https://mvnrepository.com/artifact/com.eclipsesource.j2v8/j2v8_linux_x86_64
    implementation("com.eclipsesource.j2v8:j2v8_linux_x86_64:4.8.0")

    // https://mvnrepository.com/artifact/com.eclipsesource.j2v8/j2v8_win32_x86_64
    implementation("com.eclipsesource.j2v8:j2v8_win32_x86_64:4.6.0")

    // https://mvnrepository.com/artifact/com.eclipsesource.j2v8/j2v8_macosx_x86_64
    implementation("com.eclipsesource.j2v8:j2v8_macosx_x86_64:4.6.0")

    // https://mvnrepository.com/artifact/org.mozilla/rhino
    implementation("org.mozilla:rhino:1.8.0")
}