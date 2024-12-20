### cache

L1 L2 cache module, currently L1 cache has been implemented, L2 cache is still in the planning. The L2 cache plan is most likely based on Redis

The original purpose of this module was to design a L1 cache for the `mongodb` module, but now it is **general-purpose**.

### usage
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

        
        // create memory query cache
        MemoryQueryCacheInterface<String, String> memoryQueryCacheInterface = MemoryQueryCacheFactory.create();
        
        // create cache service and bind memory query cache
        CacheService<String, String> cacheService = CacheServiceFactory.create(memoryQueryCacheInterface);
        
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
        cacheService.get("testKey", dbQuery, true, expirationSettings);

        // Now we query again test L1 cache, Testing time: 0
        cacheService.get("testKey", dbQuery, true, expirationSettings);
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

It should be noted that the element will only perform a timeout check when the `get` method is called. For this problem, you can provide a `Map` to solve it.

```java
public class MemoryQueryCacheFactory {
    public static <K, V> MemoryQueryCacheInterface<K, V> create() {
        return new MemoryQueryCache<>();
    }
    
    public static <K, V> MemoryQueryCacheInterface<K, V> create(Map<K, CacheItem<V>> cacheMap) {
        return new MemoryQueryCache<>(cacheMap);
    }
}
```

`CacheService` is a key-value pair-based cache service, which needs to be bound to a specific cache implementation, 
such as `MemoryQueryCacheInterface`. When the L2 cache is developed, it only needs to change the bound cache implementation, which is different from L1.

