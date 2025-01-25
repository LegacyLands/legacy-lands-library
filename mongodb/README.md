# 🚀 MongoDB Module

Leverages [Morphia](https://morphia.dev/landing/index.html) to simplify MongoDB operations and datastore management.
This module provides a streamlined way to configure and interact with MongoDB, automatically setting up connections and
enabling advanced CRUD, indexing, and aggregation functionality.

[![Morphia](https://img.shields.io/badge/Morphia-2.4-blue.svg)](https://morphia.dev/landing/index.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

## Contents

- [Overview](#overview)
- [Features](#features)
- [Installation](#installation)
- [Core Classes](#core-classes)
    - [MongoDBConnectionConfig](#mongodbconnectionconfig)
    - [MongoDBConnectionConfigFactory](#mongodbconnectionconfigfactory)
- [Usage Example](#usage-example)
- [Additional Notes](#additional-notes)
- [License](#license)

## Overview

The MongoDB Module provides a ready-to-use Morphia-based solution for modeling, saving, and retrieving data from
MongoDB. With just a few configuration lines, you can:

1. Set up a MongoDB connection using a friendly factory class.
2. Acquire a Morphia-backed Datastore for straightforward data operations.
3. Leverage official MongoDB Java driver settings (e.g., custom UUID representation, client settings).
4. Enjoy integrated CRUD, indexing, and aggregation methods from Morphia.

## Features

• Easy-to-use factory (MongoDBConnectionConfigFactory) for building connections or customizing MongoClient settings.  
• Automatic creation and injection of a Datastore instance point, simplifying database queries and indexing.  
• Support for advanced Morphia features (indexed fields, aggregates, filters).  
• Encourages best practices like properly closing the client connection via the config object's close() method.

## Installation

Add the built JAR or Gradle/Maven dependency for the MongoDB module to your project. For example (Gradle Kotlin DSL):

```kotlin
dependencies {
    compileOnly(files("libs/mongodb-1.0-SNAPSHOT.jar"))
}
```

## Core Classes

### MongoDBConnectionConfig

• A configuration class wrapping both the MongoClient and the Morphia Datastore.  
• Automatically configures the client with your specified connection string or custom MongoClientSettings.  
• Allows usage of custom UUID representations (STANDARD, C_SHARP_LEGACY, PYTHON_LEGACY, etc.).  
• Provides a close() method to safely shut down the underlying MongoClient.

### MongoDBConnectionConfigFactory

• Creates MongoDBConnectionConfig objects based on different parameters you pass:

- A plain databaseName + connection URL.
- A custom UuidRepresentation.
- A fully customized MongoClientSettings.  
  • Encapsulates best practices for building stable connections (like setting up the application name, or handling edge
  cases around empty parameters).

## Usage Example

Below is a typical usage scenario, demonstrating how to create a Datastore, persist data, and fetch results. For your
actual code, you may prefer using Fairy IoC or injecting the config instance elsewhere as needed.

```java
public class Example {
    public static void main(String[] args) {
        // Create the config
        MongoDBConnectionConfig config = 
            MongoDBConnectionConfigFactory.create(
                "example-db", 
                "mongodb://localhost:27017/", 
                UuidRepresentation.STANDARD
            );

        // Access the datastore from the config
        Datastore datastore = config.getDatastore();

        // Define and save some entities
        Person person = new Person("Steve", 30);
        datastore.save(person);

        // Query the datastore with filters
        List<Person> found = datastore.find(Person.class)
            .filter(Filters.eq("name", "Steve"))
            .iterator()
            .toList();

        found.forEach(foundPerson -> System.out.println(foundPerson.getName()));

        // Close the connection when no longer needed
        config.close();
    }
}

@Entity("persons")
@Data
class Person {
    @Id
    private UUID id;
    private String name;
    private int age;

    public Person(String name, int age) {
        this.id = UUID.randomUUID();
        this.name = name;
        this.age = age;
    }

    // No-arg constructor for Morphia usage
    public Person() {}
}
```

## Additional Notes

• If you want to fine-tune behavior (e.g., timeouts, SSL, etc.), create a custom MongoClientSettings instance and pass
it to MongoDBConnectionConfigFactory.create(...).  
• Morphia supports advanced indexing options @Indexed or @Indexes for your entities. Use these if you have
performance-critical queries.  
• For large-scale operations, consider exploring Morphia's aggregation queries (like pipeline stages, grouping, etc.).

## License

This module is licensed under the MIT License. See [LICENSE](../LICENSE) file for more details.

---


Made with ❤️ by [LegacyLands Team](https://github.com/LegacyLands)

