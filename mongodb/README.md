### mongodb

This module only encapsulates a more convenient MongoConfig based on [Morphia](https://morphia.dev/landing/index.html), and its implementation is very simple.

We recommend using Datastore directly for any CRUD operations, using [Morphia](https://morphia.dev/landing/index.html) for index creation or using the [aggregation](https://morphia.dev/morphia/2.4/aggregations.html).

### cache

For the first and second level cache, it is actually implemented in the cache module.

### usage

```kotlin
// Dependencies
dependencies {
    // mongodb module
    compileOnly("me.qwqdev.library:mongodb:1.0-SNAPSHOT")
}
```

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