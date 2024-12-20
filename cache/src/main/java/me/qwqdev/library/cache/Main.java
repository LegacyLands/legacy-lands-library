package me.qwqdev.library.cache;

import dev.morphia.Datastore;
import dev.morphia.annotations.Entity;
import dev.morphia.annotations.Id;
import dev.morphia.query.filters.Filters;
import lombok.Data;
import me.qwqdev.library.cache.factory.CacheServiceFactory;
import me.qwqdev.library.cache.factory.MemoryQueryCacheFactory;
import me.qwqdev.library.cache.model.ExpirationSettings;
import me.qwqdev.library.cache.service.CacheService;
import me.qwqdev.library.cache.service.memory.MemoryQueryCacheInterface;
import me.qwqdev.library.mongodb.factory.MongoDBConnectionConfigFactory;
import me.qwqdev.library.mongodb.model.MongoDBConnectionConfig;

import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author qwq-dev
 * @since 2024-12-20 15:05
 */
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

        // create L1 cache
        MemoryQueryCacheInterface<String, String> memoryQueryCacheInterface = MemoryQueryCacheFactory.create();
        CacheService<String, String> cacheService = CacheServiceFactory.create(memoryQueryCacheInterface);
        ExpirationSettings expirationSettings = ExpirationSettings.of(100, TimeUnit.DAYS);

        // db query function
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
