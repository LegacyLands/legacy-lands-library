### mongodb 模块

本模块仅基于 [Morphia](https://morphia.dev/landing/index.html) 封装了一个更方便的 `MongoConfig`，
其实现非常简单。我们建议直接使用 `Datastore` 进行任何 `CRUD` 操作。

### MongoDB 作为 player 模块的主要选择

`player` 模块是专门围绕 MongoDB 设计的，目前没有计划为该模块添加对其他数据库类型（如 `MySQL`、`PostgreSQL`、`SQLite` 等）的支持。

这一设计决策是基于对 `player` 模块架构和数据需求的深入分析。该模块的数据模型（`LegacyPlayerData` 和 `LegacyEntityData`
）具有以下特点：

- **动态属性系统**，使用灵活的键值对存储
- **复杂的关系映射**（Map<String, Set<UUID>>），在 SQL 中需要多个关联表
- **版本控制和时间戳**，用于分布式环境下的冲突解决
- **文档级原子操作**，用于合并来自不同服务器的更改
- **多级缓存架构**（L1 Caffeine + L2 Redis + MongoDB），受益于文档序列化

然而，我们认识到 SQL 数据库在某些场景下表现卓越。未来，我们将在必要时引入 SQL 数据库支持，以确保不同用例的最佳性能（例如事务性操作、
复杂分析查询或需要严格 ACID 合规性的模块）。

以下是 MongoDB 特别适合 Minecraft 插件开发的关键理由：

1. 为 Minecraft 插件量身定制的灵活性 (Schema-less)

    * **Minecraft 的动态特性:** Minecraft 世界本身就是一个高度可定制、不断变化的环境。这些数据往往具有高度的动态性和不确定性。
    * **MongoDB 的文档模型:** `MongoDB` 采用灵活的、类似 `JSON` 的文档（`BSON`）来存储数据。这意味着：
        * **无需预定义模式:** 你不需要事先定义好所有的数据字段。可以随时根据需要添加、删除或修改字段，而不会影响已有的数据。
        * **适应变化的需求:** 当你的插件引入新功能或需要存储新的数据类型时，可以直接在文档中添加新的字段，无需修改数据库结构。
        * **应对复杂数据:** Minecraft 中的许多数据都具有复杂的嵌套结构。`MongoDB` 的文档模型可以自然地表示这些嵌套关系，避免了关系型数据库中繁琐的表连接（
          `JOIN`）操作。
    * **示例:** 假设你的插件需要存储玩家的自定义建筑。有的玩家可能只建造了几个方块，而另一些玩家可能建造了包含大量细节的复杂建筑。使用
      `MongoDB`，你可以为每个玩家存储一个文档，每个文档包含不同的字段来描述他们的建筑，而无需为所有玩家预留大量可能为空的字段。

2. 为 Minecraft 服务器设计的可扩展性 (Sharding)

    * **大型服务器的挑战:** 流行的大型 Minecraft 服务器可能有成百上千的玩家同时在线，产生海量的数据。
    * **MongoDB 的水平扩展:** `MongoDB` 内置了分片（`Sharding`）功能，可以将数据分散到多个服务器上，实现水平扩展。这意味着：
        * **轻松应对增长:** 随着玩家数量和数据量的增加，你可以通过添加更多的服务器来扩展数据库的容量和吞吐量，而无需对数据库进行复杂的重构。
        * **高可用性:** 分片机制还可以提高数据库的可用性，即使部分服务器出现故障，也不会影响整个数据库的运行。
    * **关系型数据库的局限:** 关系型数据库通常更难进行水平扩展，需要更复杂的配置和管理。

3. 为 Minecraft 开发者带来的高效率

    * **减少 ORM 的依赖:** 在使用关系型数据库时，通常需要使用对象关系映射（`ORM`）工具来处理对象和关系表之间的转换。
      `MongoDB`
      的文档模型更接近面向对象编程的思想，可以减少甚至消除对 `ORM` 的依赖，从而简化开发，减少出错的可能性。
    * **快速原型开发:** `MongoDB` 的灵活性和易用性使得它非常适合快速原型开发。你可以快速地尝试新的想法和功能，而无需花费大量时间在数据库设计上。

4. 专为 Minecraft 场景优化的功能

    * **聚合框架 (Aggregation Framework):**
        * 你可能需要对数据进行各种统计和分析，`MongoDB` 提供了强大的聚合框架，可以使用管道操作符对数据进行复杂的处理和分析，而无需编写复杂的
          `SQL` 语句。
    * **嵌入式文档 (Embedded Documents):** 能够很好的表示 Minecraft 世界中的各种复杂结构.

5. 针对特定插件类型的优势

    * **记录和日志插件:** 如果你的插件需要记录大量的事件、聊天记录或玩家活动，`MongoDB` 的写入性能和灵活的数据模型会很有优势。
    * **自定义数据插件:** 如果你的插件需要存储各种自定义数据，而这些数据的结构不固定，`MongoDB` 的 `Schema-less` 特性会非常方便。
    * **实时数据插件:** 如果你的插件需要实时处理大量数据（如实时更新排行榜等），`MongoDB` 的性能和扩展性会很有帮助。
    * **MMORPG 插件:** 对于大型多人在线角色扮演游戏（`MMORPG`）类型的插件，`MongoDB` 的优势尤为明显，因为这类插件通常需要处理大量的、复杂的、不断变化的数据。

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
