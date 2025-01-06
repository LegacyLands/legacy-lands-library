### player

This module is designed for processing player data within a distributed environment, employing L1 (Caffeine) and L2 (Redis) caches to ensure data consistency and efficient access.

### automation

We automate the management and synchronization of player data across multiple layers, including caches and databases.

### key components

#### legacyplayerdataservice
- **Description**: A composite object that can be instantiated indefinitely, each instance having its own dedicated connections:
    - **Redis Connection**: For L2 caching layer.
    - **MongoDB Connection**: For persistent storage.
    - **Redis Streams Connection**: For inter-server communication.

#### caching layers
- **L1 Cache (Caffeine)**: In-memory cache designed for online player data, offering ultra-fast data access.
- **L2 Cache (Redis)**: Secondary caching layer meant for persistent storage of player data, providing a centralized view.

### player data query process

1. **Check L1 Cache**:
    - **Note**: This layer is optimized for online players and is not queried for offline data.

2. **Query L2 Cache (Redis)**:
    - **Cache Hit**:
        - Retrieve data from L2.
        - Store the retrieved data in L1 for faster future access.
        - Return data to the requester.
    - **Cache Miss**:
        - Proceed to query the MongoDB database.

3. **Query Database (MongoDB)**:
    - **Success**:
        - Retrieve player data from the database.
        - Store this data in L1 for quick future access.
        - Return the data to the requester.
    - **No Data Found**:
        - Create a new player record and store it directly in L1.

### player logout process

1. **Retrieve All Instances**: Gather all instances of `LegacyPlayerDataService`.
2. **Compare L1 and L2 Cache**:
    - **If Consistent**: No changes are needed.
    - **If Inconsistent**: Update the L2 cache with data from the L1 cache.
3. **Remove Player Data**: Delete the player’s data from the L1 cache to maintain its integrity.

### data synchronization

1. **Synchronize L1 to L2**: Transfer all current data from the L1 caches to the L2 cache.
2. **Persist L2 Data to Database**: Save data from the L2 cache into MongoDB for persistent storage.

### player data modification process

1. **Publish Update Tasks Using Redis Stream**:
    - Send update tasks via Redis Stream to ensure distributed synchronization.
    - Directly update the content in the L2 cache.
    - **If the L2 Cache Misses the Data**: Update it in the database instead.

2. **Process on Each Server**:
    - **If Player is Online**: Retrieve the updated data from the L2 cache and synchronize it to the L1 cache.

### player data acquisition process

1. **L1 L2 data synchronization**: Use Redis Stream to synchronize all server L1 L2 caches.
2. **Search**: Search directly in the L2 cache, and if it does not hit, search in the database

### performance optimizations

- **L1 Cache**:
    - Retains data indefinitely for active players, enabling rapid access without expiration management.

- **L2 Cache**:
    - Functions as long-term storage with configurable expiration policies to manage stale data.

- **Database**:
    - Accessed mainly when the data is not found in either cache, minimizing database load and enhancing performance.

### summary

This architecture utilizes a multi-tier caching strategy for efficient player data management:

- **L1 Cache (Caffeine)**: Provides speedy access for online players.
- **L2 Cache (Redis)**: Centralized caching of persistent player data.
- **Database (MongoDB)**: Acts as the main storage when cache misses occur.
- **Redis Streams**: Enables real-time synchronization between servers for consistent data.
- **LegacyPlayerDataService**: Instantiable composite objects with dedicated connections for Redis, MongoDB, and Redis Streams.

In conclusion, this design maximizes data retrieval efficiency, ensures consistency across distributed servers, and optimizes overall performance, thereby delivering a seamless experience for players.