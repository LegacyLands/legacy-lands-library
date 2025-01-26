# 🗄️ MongoDB Framework

A flexible MongoDB fast connection framework built on top of [Morphia](https://morphia.dev/).

[![JDK](https://img.shields.io/badge/JDK-17%2B-blue.svg)](https://www.oracle.com/java/technologies/javase/jdk17-archive-downloads.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](../LICENSE)

## ✨ Key Features

- 🏭 **Factory-Based Configuration**
  - Simple connection setup via `MongoDBConnectionConfigFactory`

## 📚 Quick Start

### Installation

```kotlin
dependencies {
    implementation("net.legacy.library:mongodb:1.0-SNAPSHOT")
}
```

### Basic Usage

1️⃣ **Create Connection**
```java
MongoDBConnectionConfig config = MongoDBConnectionConfigFactory.create(
    "mydb",
    "mongodb://localhost:27017/",
    UuidRepresentation.STANDARD
);

Datastore datastore = config.getDatastore();
```

2️⃣ **Define Entity**
```java
@Entity("users")
public class User {
    @Id
    private ObjectId id;
    private String name;
    private int age;
    
    // Getters and setters
}
```

3️⃣ **Perform Operations**
```java
// Save entity
User user = new User("John", 25);
datastore.save(user);

// Query entity
List<User> users = datastore.find(User.class)
    .filter(Filters.eq("name", "John"))
    .iterator()
    .toList();
```

## 🔧 Core Components

### Connection Configuration
- `MongoDBConnectionConfig`: Central configuration class
- Custom client settings
- Connection lifecycle management

### Factory System
- `MongoDBConnectionConfigFactory`: Factory for creating configurations
- Support for custom MongoDB settings
- UUID representation handling

## 🎯 Advanced Features

### Custom Client Settings
```java
MongoClientSettings settings = MongoClientSettings.builder()
    .applyConnectionString(new ConnectionString("mongodb://localhost:27017"))
    .uuidRepresentation(UuidRepresentation.STANDARD)
    .build();

MongoDBConnectionConfig config = MongoDBConnectionConfigFactory.create(settings);
```

## 📄 License

This project is licensed under the MIT License - see the [LICENSE](../LICENSE) file for details.

## 🤝 Contributing

Contributions are welcome! Please feel free to submit a Pull Request.

---

Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

