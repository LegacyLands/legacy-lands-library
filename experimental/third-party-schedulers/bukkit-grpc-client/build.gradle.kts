fun properties(key: String) = project.findProperty(key).toString()
fun rootProperties(key: String) = rootProject.findProperty(key).toString()

group = rootProperties("group")
version = rootProperties("version")

// Enable protobuf and shadow plugins
plugins {
    id("com.google.protobuf") version "0.9.5"
}

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
    bukkitProperties().depends.add("commons")

    bukkitProperties().foliaSupported = true
    bukkitProperties().bukkitApi = rootProperties("spigot.version")
}

// Protobuf configuration
protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:4.31.0"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.75.0"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
}

// Configure source sets for Scala/Java joint compilation with protobuf
sourceSets {
    main {
        proto {
            // Use proto files from the shared directory
            srcDir("${rootProject.projectDir}/experimental/third-party-schedulers/proto")
        }
        scala {
            setSrcDirs(listOf("src/main/java", "src/main/scala"))
        }
        java {
            setSrcDirs(emptyList<String>())
            // Add generated protobuf sources
            srcDir("build/generated/sources/proto/main/java")
            srcDir("build/generated/sources/proto/main/grpc")
        }
    }
}

// Ensure generateProto runs before compilation
tasks.named<JavaCompile>("compileJava") {
    enabled = false
}

tasks.named<ScalaCompile>("compileScala") {
    dependsOn(tasks.generateProto)
    // Add generated sources to classpath
    classpath = sourceSets.main.get().compileClasspath + files(
        "build/generated/sources/proto/main/java",
        "build/generated/sources/proto/main/grpc"
    )
}

// Disable delombok for this module as it conflicts with protobuf
tasks.matching { it.name == "delombok" }.configureEach {
    enabled = false
}

// Dependencies
dependencies {
    // Commons module
    compileOnly(project(":commons"))

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.75.0")
    implementation("io.grpc:grpc-protobuf:1.75.0")
    implementation("io.grpc:grpc-stub:1.75.0")

    // Google Protobuf
    implementation("com.google.protobuf:protobuf-java:4.31.0")
    implementation("com.google.protobuf:protobuf-java-util:4.31.0")

    // Guava for immutable collections
    implementation("com.google.guava:guava:33.4.8-jre")
}
