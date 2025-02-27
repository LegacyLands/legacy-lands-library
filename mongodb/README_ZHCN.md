### mongodb 模块

本模块仅基于 [Morphia](https://morphia.dev/landing/index.html) 封装了一个更方便的 `MongoConfig`，
其实现非常简单。我们建议直接使用 `Datastore` 进行任何 `CRUD` 操作。

### 用法

```kotlin
// Dependencies
dependencies {
    // mongodb module
    compileOnly(files("libs/mongodb-1.0-SNAPSHOT.jar"))
}
```

### 举个例子

我们建议使用 `MongoDBConnectionFactory` 来创建连接，而不是直接创建对象。

```java
public class Example {
    public static void main(String[] args) {
        // 测试对象
        Person person = new Person();
        person.setUuid(UUID.randomUUID());
        person.setName("Alice");
        person.setAge(20);

        Person person2 = new Person();
        person2.setUuid(UUID.randomUUID());
        person2.setName("Alice");
        person2.setAge(45);


        // 创建连接
        MongoDBConnectionConfig mongoConfig = MongoDBConnectionFactory.create(
                "example", "mongodb://localhost:27017/", UuidRepresentation.STANDARD
        );

        // 获取 datastore
        Datastore datastore = mongoConfig.getDatastore();


        // 保存对象
        datastore.save(person);
        datastore.save(person2);


        // 寻找对象，这是线程安全的
        @Cleanup
        MorphiaCursor<Person> iterator = datastore.find(Person.class)
                .filter(Filters.and(
                        Filters.eq("_id", "2135ef6b-8915-4d4c-a677-b1f5482ed2aa"), // _id eq
                        Filters.eq("name", "Alice"), // name eq
                        Filters.gt("age", 35) // age > 35, 老登，是时候裁员你了
                ))

                // 需要注意的是迭代器 (iterator) 不是线程安全的
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