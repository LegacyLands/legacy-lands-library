### cache

The cache module based on memory and memory database, the part based on Memory and Caffeine has been completed.

The original purpose of this module was to design a L1 cache for the `mongodb` module, but now it is **general-purpose**.

### usage

```kotlin
// Dependencies
dependencies {
    // annotation module
    compileOnly("me.qwqdev.library:cache:1.0-SNAPSHOT")
}
```

```java
public class Main {
    public static void main(String[] args) {
        // test object
        Person person = new Person();
        person.setUuid(UUID.randomUUID());
        person.setName("Johannes");
        person.setAge(20);

        Person person2 = new Person();
        person2.setUuid(UUID.randomUUID());
        person2.setName("Johannes");
        person2.setAge(45);


        // create connection
        MongoDBConnectionConfig mongoDBConnectionConfig =
                MongoDBConnectionConfigFactory.create("test", "mongodb://localhost:27017/");
        Datastore datastore = mongoDBConnectionConfig.getDatastore();

        // save data
        datastore.save(person);
        datastore.save(person2);

        
        // create memory cache service
        MemoryCacheServiceInterface memoryCacheService = MemoryCacheServiceFactory.create();
        
        // expiration settings (this is for one element, not the entire map)
        ExpirationSettings expirationSettings = ExpirationSettings.of(100, TimeUnit.DAYS);
        
        // db query
        Supplier<String> dbQuery = () -> String.valueOf(
                datastore.find(Person.class)
                        .filter(
                                Filters.eq("name", "Johannes"),
                                Filters.gt("age", 30)
                        )
                        .iterator()
                        .tryNext()
        );

        // Testing time: 45, db query
        memoryCacheService.get("testKey", dbQuery, true, expirationSettings);

        // Now we query again test L1 cache, Testing time: 0
        memoryCacheService.get("testKey", dbQuery, true, expirationSettings);
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

It is very simple to use. We have created cache service classes for `Memory`, `Caffeine`-based `Cache`, and `AsyncCache`.

We only need to create them through the factory class to use them without manually writing the internal logic.

### scalability

In-memory database level cache is already in the planning. 

For caches like L1 and L2, developers should manually nest them or directly use the L1 and L2 level caches provided by the `data` module.