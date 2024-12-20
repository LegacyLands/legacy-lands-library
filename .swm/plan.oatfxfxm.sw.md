---
title: Plan
---
At present, the annotation and configuration modules provide relatively basic services, and it is not yet clear what needs to be supplemented.

Mongodb relies on morphia to provide a relatively complete service. Its cache should not be implemented by the main body, but by using the cache or data module.

Cache currently implements Memory and Caffeine caches, and caches based on memory servers are being planned.

The data module should provide more complete content queries based on the mongodb and cache modules, which may be a relatively large project.

Security has not yet clarified what functions it will provide, but it may be certain that it should be inseparable from the permission group of folia, or there will be a new module responsible for it. What is certain is that disaster recovery and backup services will be completed by security.

We plan to use minio for disaster recovery backup of files, and the rest is yet to be determined. What we can confirm at present is that hot backup of cache and database has a lower priority.

<SwmMeta version="3.0.0" repo-id="Z2l0aHViJTNBJTNBbGVnYWN5LWxhbmRzLWxpYnJhcnklM0ElM0FMZWdhY3lMYW5kcw==" repo-name="legacy-lands-library"><sup>Powered by [Swimm](https://app.swimm.io/)</sup></SwmMeta>
