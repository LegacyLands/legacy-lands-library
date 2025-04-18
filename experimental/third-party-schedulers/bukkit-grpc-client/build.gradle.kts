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
        artifact = "com.google.protobuf:protoc:4.30.2"
    }
    plugins {
        create("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:1.72.0"
        }
    }
    generateProtoTasks {
        all().forEach {
            it.plugins {
                create("grpc")
            }
        }
    }
    // Set the proto files location to the shared directory
    sourceSets {
        main {
            proto {
                // Use proto files from the shared directory
                srcDir("${rootProject.projectDir}/experimental/third-party-schedulers/proto")
            }
        }
    }
}

// Dependencies
dependencies {
    // Commons module
    compileOnly(project(":commons"))

    // gRPC
    implementation("io.grpc:grpc-netty-shaded:1.72.0")
    implementation("io.grpc:grpc-protobuf:1.72.0")
    implementation("io.grpc:grpc-stub:1.72.0")

    // Google Protobuf
    implementation("com.google.protobuf:protobuf-java:4.30.2")
    implementation("com.google.protobuf:protobuf-java-util:4.30.2")

    // Guava for immutable collections
    implementation("com.google.guava:guava:33.4.8-jre")
}
