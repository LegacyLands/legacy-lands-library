# 🚀 MongoDB Module

[![Morphia](https://img.shields.io/badge/Morphia-2.4-blue.svg)](https://morphia.dev/landing/index.html)
[![License](https://img.shields.io/badge/license-MIT-green.svg)](LICENSE)

This module provides a convenient encapsulation of `MongoConfig` based on [Morphia](https://morphia.dev/landing/index.html). It simplifies the implementation of MongoDB operations.

## 📦 Features

- **Datastore Operations**: Use `Datastore` directly for CRUD operations.
- **Index Creation**: Utilize [Morphia](https://morphia.dev/landing/index.html) for creating indexes.
- **Aggregation**: Perform aggregations using [Morphia Aggregations](https://morphia.dev/morphia/2.4/aggregations.html).

## 🗃️ Cache

The first and second level caches are implemented in the cache module.

## 📋 Usage

### Gradle Dependency

```kotlin
dependencies {
    compileOnly(files("libs/mongodb-1.0-SNAPSHOT.jar"))
}
```

### Example Code

We recommend using `MongoDBConnectionFactory` to create connections instead of instantiating objects directly.

```java
public class Example {
    public static void main(String[] args) {
        // Create test objects
        Person person = new Person();
        person.setUuid(UUID.randomUUID());
        person.setName("Alice");
        person.setAge(20);

        Person person2 = new Person();
        person2.setUuid(UUID.randomUUID());
        person2.setName("Alice");
        person2.setAge(45);

        // Create connection
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionConfigFactory.create(
                "example", "mongodb://localhost:27017/", UuidRepresentation.STANDARD
        );

        // Get datastore
        Datastore datastore = mongoConfig.getDatastore();

        // Save objects
        datastore.save(person);
        datastore.save(person2);

        // Find objects (thread-safe)
        @Cleanup
        MorphiaCursor<Person> iterator = datastore.find(Person.class)
                .filter(Filters.and(
                        Filters.eq("_id", "2135ef6b-8915-4d4c-a677-b1f5482ed2aa"), // _id eq
                        Filters.eq("name", "Alice"), // name eq
                        Filters.gt("age", 35) // age > 35
                ))
                .iterator(); // Get iterator

        // Print results
        for (Person person1 : iterator.toList()) {
            System.out.println(person1.getAge());
        }
    }
}

@Data
@Entity("persons")
class Person {
    @Id
    private UUID uuid;
    private String name;
    private int age;
}
```

## 🔗 Related Links

- [Morphia Documentation](https://morphia.dev/landing/index.html)
- [Morphia Aggregations](https://morphia.dev/morphia/2.4/aggregations.html)

## 📝 License

This project is licensed under the MIT License - see the [LICENSE](LICENSE) file for details.
