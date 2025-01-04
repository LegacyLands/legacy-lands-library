### player

This module is primarily used for processing player data in a distributed environment. It includes `L1 (Caffeine)` and `L2 (Redis)` caches to ensure data consistency. The module facilitates player data management, enables rapid development, and eliminates concerns about data consistency issues.

### what do we automate for you?

We automate the management and synchronization of player data across multiple caching layers and the database, ensuring efficient data retrieval and consistency in a distributed system.

### Overview of LegacyPlayerDataService

- **Implementation**: The `LegacyPlayerDataService` utilizes the **Lazy Initialization (懒汉式)** pattern and operates as a **composite object**.
- **Reasoning**: Lazy initialization prevents the performance overhead associated with **Eager Initialization (饿汉式)**, especially when handling numerous `LegacyPlayerDataService` instances.
- **Instance Components**: Each `LegacyPlayerDataService` instance includes:
  - **L1 Cache**: Java in-memory cache specific to the instance (`Caffeine`).
  - **L2 Connection**: Connection to Redis for secondary caching.
  - **DB Connection**: Connection to MongoDB for persistent storage.

### Player Data Query Process

When querying player data through a specified `LegacyPlayerDataService`, the following steps are executed:

#### Step 1: Check L1 Cache

- **Action**: L1 cache is **not queried** because it holds data only for **online players**.
- **Rationale**: L1 is optimized for quick access to active player data, minimizing the need to query slower caches or databases.

#### Step 2: Query L2 Cache (Redis)

- **If L2 Cache Hit**:
  - **Action**:
    - Retrieve the player data from L2.
    - Write the retrieved data into L1 cache **without modifying L2**.
    - Return the player data to the requester.
  - **Benefit**: Enhances access speed for subsequent requests by populating L1.

- **If L2 Cache Miss**:
  - **Action**: Proceed to query the Database (MongoDB).

#### Step 3: Query Database (MongoDB)

- **If Database Query Success**:
  - **Action**:
    - Retrieve player data from MongoDB.
    - Write the data into L1 cache **without updating L2**.
    - Return the player data to the requester.
  - **Benefit**: Ensures that newly accessed data is quickly available in L1 for future accesses.

- **If Database Query Fails (No Data Found)**:
  - **Action**:
    - Create a new player data record.
    - Write this new data directly into L1 cache.
  - **Benefit**: Initializes player data efficiently without unnecessary L2 or DB interactions.

### Player Logout Process

When a player exits, the system performs the following actions:

1. **Retrieve All LegacyPlayerDataService Instances**
  - **Action**: Gather all instances managing player data.

2. **Compare L1 and L2 Cache Contents**
  - **If L1 and L2 Data Are Consistent**:
    - **Action**: No overwrite is performed.
  - **If L1 and L2 Data Are Inconsistent**:
    - **Action**: Update the L2 cache with the data from L1.

3. **Remove Player Data from L1 Cache**
  - **Action**: Delete the player's data from L1 cache.
  - **Benefit**: Frees up in-memory resources and maintains L1 cache integrity by removing offline player data.

### L2 Data Synchronization

Synchronization between L2 cache (Redis) and the database (MongoDB) is handled via automated tasks:

1. **Synchronize L1 to L2**
  - **Action**: Transfer all data from L1 caches to L2.
  - **Benefit**: Ensures that Redis holds the latest data from active sessions.

2. **Persist L2 Data to Database**
  - **Action**: Extract all data from L2 cache and save it to MongoDB.
  - **Benefit**: Maintains a persistent and durable data store reflecting the current state of all players.

### Player Data Modification Process

When modifying player data through a specified `LegacyPlayerDataService`, the following steps are taken:

1. **Determine Player Online Status**
  - **Action**: Check if the player is currently online by verifying the presence of their data in L1 cache.

2. **Update Data Based on Online Status**
  - **If Player is Online**:
    - **Action**: Directly update the player's data in the **L1 cache** of the current server.
    - **Benefit**: Provides immediate data consistency for active sessions.

  - **If Player is Offline**:
    - **Action**: Directly update the player's data in the **L2 cache** (Redis).
    - **Benefit**: Efficiently manages data for inactive players without impacting in-memory caches.

### Redis Streams for Cross-Server Notifications

To ensure data consistency across multiple servers, Redis Streams are utilized for inter-server communication:

1. **Publish Update Notification**
  - **Action**: When player data is updated in L2 cache, publish a task via Redis Streams to notify other servers of the change.
  - **Benefit**: Facilitates real-time synchronization of data across the distributed system.

2. **Handle Incoming Notifications on Each Server**
  - **Action**:
    - Upon receiving a notification, each server checks if the affected player has an active L1 cache.
    - **If Player is Online**:
      - **Action**: Update the player's data in the local L1 cache to reflect the changes.
    - **If Player is Offline**:
      - **Action**: No action is taken.
  - **Benefit**: Ensures that online players have the most recent data without unnecessary updates for offline players.

### Performance Optimizations

Optimizing the performance of each caching layer and database interactions is crucial for maintaining system efficiency.

#### L1 Cache (Caffeine)

- **Strategy**:
  - **No Expiration Time**: L1 cache retains data indefinitely as long as players are online.
- **Benefit**: Provides ultra-fast access to active player data without the overhead of managing expiration policies.

#### L2 Cache (Redis)

- **Strategy**:
  - **Long-Term Storage**: Stores data that needs to persist beyond the online state of players, including all L1 data.
  - **Expiration Policies**: Implements expiration times to manage stale data and optimize memory usage.
  - **Data Synchronization via Redis Streams**: Ensures consistency across multiple servers.
- **Benefit**: Balances the need for fast access with efficient memory management and data consistency across the system.

#### Database (MongoDB)

- **Strategy**:
  - **Conditional Queries**: Accessed only when both L1 and L2 caches miss, or when querying cold (infrequently accessed) data.
- **Benefit**: Reduces the load on the database by leveraging caching layers, enhancing overall system performance and scalability.

### Summary

This architecture leverages a multi-tier caching strategy to efficiently manage player data in a scalable and performant manner:

- **L1 Cache (Caffeine)**: Provides rapid access to data for online players, minimizing latency.
- **L2 Cache (Redis)**: Acts as a centralized cache for all player data, supporting data persistence and cross-server consistency.
- **Database (MongoDB)**: Serves as the ultimate data store for player information, accessed only when necessary to reduce load and improve performance.
- **Redis Streams**: Facilitates real-time synchronization across multiple servers, ensuring data consistency.
- **Lazy Initialization of LegacyPlayerDataService**: Optimizes resource usage by initializing services only when needed, avoiding the performance costs of eager initialization.

By following this design, the system ensures efficient data retrieval, consistent state across distributed servers, and optimized resource utilization, ultimately providing a seamless experience for players.
