# Q&A One-Pager

## Why not global lock?

- It serializes all requests for the same voucher and kills parallelism.

## Why Lua after fine-grained lock?

- Redis Lua atomically performs stock check + duplicate-user check + reserve in memory.
- It shifts hot contention away from DB.

## Why Kafka async?

- Request thread returns early after acceptance.
- DB writes are decoupled and smoothed by consumer.

## How do you prove no oversell?

- DB audit: `sold_count <= initial_stock` for every run.

## How do you prove one-user-one-order?

- Audit query with `HAVING COUNT(*) > 1` must stay zero.
- DB unique index on `(user_id, voucher_id)` as final guard.
