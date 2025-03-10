### mongodb

This module only encapsulates a more convenient `MongoConfig` based
on [Morphia](https://morphia.dev/landing/index.html),
and its implementation is very simple. We recommend using `Datastore` directly for any `CRUD` operations.

### never supporting other databases

There are no plans to support any other types of databases (such as `MySQL`, `PostgreSQL`, `SQLite`, etc.).

This design decision is based on an in-depth analysis of the needs of `Minecraft` plugin development and careful
consideration of the features of `MongoDB`. While there are numerous documents available explaining this choice, we'll
provide a brief summary, highlighting the key reasons:

1. Tailor-Made Flexibility for Minecraft Plugins (Schema-less)

    * **Minecraft's Dynamic Nature:** The Minecraft world itself is a highly customizable and constantly evolving
      environment. This data often exhibits a high degree of dynamism and uncertainty.
    * **MongoDB's Document Model:** `MongoDB` uses a flexible, `JSON`-like document (`BSON`) format to store data. This
      means:
        * **No Predefined Schema:** You don't need to define all the data fields in advance. You can add, remove, or
          modify fields as needed without affecting existing data.
        * **Adapting to Changing Requirements:** When your plugin introduces new features or needs to store new data
          types, you can simply add new fields to the documents without modifying the database structure.
        * **Handling Complex Data:** Many data structures in Minecraft have complex nested relationships. `MongoDB`'s
          document model can naturally represent these nested relationships, avoiding the cumbersome table joins (`JOIN`
          operations) required in relational databases.
    * **Example:** Suppose your plugin needs to store players' custom buildings. Some players might only build a few
      blocks, while others might create complex structures with a lot of detail. Using `MongoDB`, you can store a
      document for each player, with each document containing different fields to describe their buildings, without
      needing to pre-allocate a large number of potentially empty fields for all players.

2. Scalability Designed for Minecraft Servers (Sharding)

    * **The Challenge of Large Servers:** Popular, large Minecraft servers can have hundreds or even thousands of
      players online simultaneously, generating massive amounts of data.
    * **MongoDB's Horizontal Scaling:** `MongoDB` has built-in sharding functionality, which allows data to be
      distributed across multiple servers, achieving horizontal scaling. This means:
        * **Easily Handle Growth:** As the number of players and the amount of data increase, you can expand the
          database's capacity and throughput by adding more servers, without requiring complex database restructuring.
        * **High Availability:** The sharding mechanism also improves the database's availability. Even if some servers
          experience failures, the overall database operation remains unaffected.
    * **Limitations of Relational Databases:** Relational databases are generally more difficult to scale horizontally,
      requiring more complex configuration and management.

3. Increased Efficiency for Minecraft Developers

    * **Reduced ORM Dependency:** When using relational databases, Object-Relational Mapping (ORM) tools are typically
      required to handle the conversion between objects and relational tables.  `MongoDB`'s document model is closer to
      object-oriented programming principles, which can reduce or even eliminate the need for ORM, simplifying
      development and reducing the likelihood of errors.
    * **Rapid Prototyping:** `MongoDB`'s flexibility and ease of use make it ideal for rapid prototyping. You can
      quickly experiment with new ideas and features without spending a lot of time on database design.

4. Optimized Features for Minecraft Scenarios

    * **Aggregation Framework:**
        * You may need to perform various statistical analyses on your data.  `MongoDB` provides a powerful aggregation
          framework that allows you to perform complex data processing and analysis using pipeline operators, without
          writing complex `SQL` statements.
    * **Embedded Documents:**  Can effectively represent the various complex structures within the Minecraft world.

5. Advantages for Specific Plugin Types

    * **Logging and Record-Keeping Plugins:** If your plugin needs to record a large number of events, chat logs, or
      player activities, `MongoDB`'s write performance and flexible data model are advantageous.
    * **Custom Data Plugins:** If your plugin needs to store various custom data with unfixed structures, `MongoDB`'s
      schema-less nature is extremely convenient.
    * **Real-time Data Plugins:** If your plugin needs to process large amounts of data in real-time (such as updating
      leaderboards live), `MongoDB`'s performance and scalability are very helpful.
    * **MMORPG Plugins:** For Massively Multiplayer Online Role-Playing Game (MMORPG) type plugins, the advantages of
      `MongoDB` are particularly pronounced because these plugins often need to handle large, complex, and constantly
      changing data.

### usage

```kotlin
// Dependencies
dependencies {
    // mongodb module
    compileOnly(files("libs/mongodb-1.0-SNAPSHOT.jar"))
}
```

### Example

We recommend using `MongoDBConnectionFactory` to create connections instead of creating objects directly.

```java
public class Example {
    public static void main(String[] args) {
        // test object
        Person person = new Person();
        person.setUuid(UUID.randomUUID());
        person.setName("Alice");
        person.setAge(20);

        Person person2 = new Person();
        person2.setUuid(UUID.randomUUID());
        person2.setName("Alice");
        person2.setAge(45);


        // create connection
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionFactory.create(
                "example", "mongodb://localhost:27017/", UuidRepresentation.STANDARD
        );

        // get datastore
        Datastore datastore = mongoConfig.getDatastore();


        // save object
        datastore.save(person);
        datastore.save(person2);


        // find object, its thread safety
        @Cleanup
        MorphiaCursor<Person> iterator = datastore.find(Person.class)
                .filter(Filters.and(
                        Filters.eq("_id", "2135ef6b-8915-4d4c-a677-b1f5482ed2aa"), // _id eq
                        Filters.eq("name", "Alice"), // name eq
                        Filters.gt("age", 35) // age > 35, it's time to lay off employees lol
                ))

                // It should be noted that iterators are not thread-safe
                .iterator(); // get iterator

        // just print
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