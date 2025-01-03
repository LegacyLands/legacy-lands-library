### player

This module is mainly used for processing player data in a distributed environment.
It includes `L1 (caffeine)` and `L2 (Redis)` cache and ensures data consistency.

This module will facilitate player data management, rapid development, and no longer need to worry about data consistency issues.

### process design

- When the player enters, check the `L1 (caffeine)` and `L2 (Redis)` cache hit problem, and query level by level if it does not hit.
- When the player data needs to be updated:
   - Use the `Redis Streams` mechanism to notify other services.
   - Each service checks whether the player is online.
   - If online, directly update the `L1 (caffeine)` data of the service; otherwise update `L2 (Redis)`.
- When the player exits, save the `L1 (caffeine)` data to `L2 (Redis)`.
- The scheduled task persists the `L2 (Redis)` data to the MongoDB.

### distributed implementation of scheduled tasks:

Through the election mechanism, a master node is elected in the distributed system to be responsible for executing scheduled tasks. Other nodes serve as backup nodes and only take over tasks when the master node fails.