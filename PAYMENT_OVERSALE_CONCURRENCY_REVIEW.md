# CityScout Payment / Oversell / High-Concurrency Design Review

## 1. Scope and Method

- Scope:
  - Voucher seckill order entry and order creation path
  - Payment-related order state transitions
  - Oversell prevention logic and concurrency handling
- Evidence files:
  - `src/main/java/com/hmdp/controller/VoucherOrderController.java`
  - `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
  - `src/main/resources/seckill.lua`
  - `src/main/java/com/hmdp/service/impl/VoucherServiceImpl.java`
  - `hmdp.sql`

## 2. Current Design Summary

Current online path is:

1. `POST /voucher-order/seckill/{id}` enters `SeckillVoucherWithLua(...)`
2. Lua atomically does:
   - check stock
   - check one-user-one-order in Redis Set
   - decrement Redis stock and mark user ordered
3. If Lua returns success, order is pushed into local JVM `BlockingQueue`
4. Single worker thread asynchronously consumes queue and writes order to MySQL (with DB stock CAS decrement)

This design has useful ideas (Redis atomic gate + async DB write), but it currently has several correctness gaps that directly affect payment, consistency, and oversell safety.

## 3. Findings (ordered by severity)

## P0-1: Async consumer uses `AopContext.currentProxy()` in worker thread (likely runtime failure)

- Evidence:
  - `VoucherOrderServiceImpl` worker thread calls proxy lookup in async context: line 86
  - `proxy.handleRealOrderCreation(...)`: line 87
- Why this is critical:
  - `AopContext.currentProxy()` depends on current AOP invocation context (ThreadLocal).
  - Worker thread is not inside a proxied service invocation context, so this call can throw `IllegalStateException`.
  - If thrown continuously, Redis stock/user-set may be changed, but DB orders never persist.
- Impact:
  - Severe inconsistency and large-scale order loss under load.

## P0-2: Lua success + queue `add()` failure has no compensation

- Evidence:
  - Lua pre-deducts stock and marks user ordered: `seckill.lua` lines 20-22
  - Java enqueues using `ordersTaskQueue.add(voucherOrder)`: `VoucherOrderServiceImpl` line 158
  - No rollback/compensation when enqueue fails
- Why this is critical:
  - `add()` throws when queue is full.
  - At that point Redis has already reserved stock and user-order marker; request may fail or lose task.
- Impact:
  - Permanent stock loss, false "already ordered", and unrecoverable mismatch.

## P0-3: No real payment confirmation flow; stock is consumed before payment success

- Evidence:
  - Order table has payment fields/status (`pay_time`, `status`): `hmdp.sql` lines 1271-1275
  - No payment callback/confirm/refund workflow in codebase (no payment controller/service path)
  - Async DB creation directly decrements stock and saves order: `VoucherOrderServiceImpl` lines 102-125
- Why this is critical:
  - Orders can remain unpaid while occupying stock.
  - No timeout-cancel release, no paid-confirm transition, no refund release handling.
- Impact:
  - Inventory gets locked by unpaid orders; revenue and fulfillment correctness risk.

## P1-1: Seckill time window is not validated in Lua path

- Evidence:
  - Controller always uses Lua path: `VoucherOrderController` line 36
  - Lua path does not check begin/end time: `SeckillVoucherWithLua(...)` lines 131-160 and `seckill.lua`
  - Time checks exist only in another method (`SeckillVoucher`) not used by endpoint: lines 173-177
- Impact:
  - If Redis stock key exists, users may place orders outside official sale window.

## P1-2: One-user-one-order lacks DB unique constraint

- Evidence:
  - `tb_voucher_order` has only PK(id), no unique `(user_id, voucher_id)`: `hmdp.sql` lines 1266-1278
  - Async path `handleRealOrderCreation(...)` does not do DB duplicate check before insert
- Impact:
  - Redis inconsistency/replay/manual operations can still produce duplicates.
  - DB final consistency boundary is weak.

## P1-3: Queue is in-memory + single-thread only (not durable, limited throughput)

- Evidence:
  - In-memory `ArrayBlockingQueue`: `VoucherOrderServiceImpl` lines 62-64
  - Single-thread consumer: lines 66-67
- Impact:
  - Process crash/redeploy loses queued orders.
  - Throughput ceiling at one consumer thread.
  - Multi-instance deployment amplifies inconsistency risk.

## P1-4: Lua script does not guard missing stock key

- Evidence:
  - `if tonumber(redis.call('get', stockKey)) <= 0 then`: `seckill.lua` line 12
- Impact:
  - Missing key can cause Lua runtime error (`nil` comparison), leading to 500 errors.

## P2-1: Automated tests do not cover voucher-order high-concurrency path

- Evidence:
  - No `VoucherOrder`/`Seckill` tests under `src/test/java`
- Impact:
  - Regressions in concurrency/consistency are hard to detect before production.

## 4. Oversell Prevention Assessment

What is good:

- Redis Lua gate is atomic for stock decrement + user marker.
- DB stock CAS update (`stock = stock - 1 where stock > 0`) exists in persistence path.

What is missing for production-grade anti-oversell:

- Durable queue/event bus instead of in-memory queue.
- DB uniqueness as final one-user-one-order guard.
- Compensation for any step after Redis reservation failure.
- End-to-end reconciliation jobs between Redis and DB.

Conclusion: Current anti-oversell is conceptually correct but operationally unsafe due to reliability and compensation gaps.

## 5. Payment Design Assessment

Current state:

- Data model supports payment states, but code does not implement payment lifecycle.
- System currently behaves as "reserve-and-create unpaid order", not "pay-then-confirm".

Production recommendation:

1. Introduce explicit states: `CREATED -> PAYING -> PAID -> USED/CANCELLED/REFUNDED`.
2. Add payment callback endpoint with idempotent upsert/transition.
3. Add timeout cancel job (e.g., 15 min unpaid) to release reserved stock.
4. Add ledger/reconciliation task against payment provider and order table.

## 6. Suggested Remediation Plan

## Phase 1 (Immediate, 1-2 days)

1. Fix async transaction invocation:
   - Replace worker `AopContext.currentProxy()` usage with injected proxied bean or split transactional method into another service bean.
2. Protect enqueue step:
   - Replace `add()` with `offer(...)` + fallback handling.
   - If enqueue fails after Lua success, run atomic compensation Lua to restore stock and remove user marker.
3. Add Lua nil guard:
   - `local stock = tonumber(redis.call('get', stockKey) or '-1')`.

## Phase 2 (Short term, 3-5 days)

1. Add DB unique index:
   - `UNIQUE KEY uk_user_voucher(user_id, voucher_id)`.
2. Enforce seckill time in Lua path:
   - Pass/validate begin/end (or maintain activity metadata in Redis).
3. Add order timeout-cancel and stock release task.

## Phase 3 (Mid term, 1-2 weeks)

1. Replace JVM local queue with durable stream/MQ:
   - Redis Streams (consumer group) / RabbitMQ / Kafka.
2. Implement idempotent payment callback and full state machine.
3. Add reconciliation jobs and high-concurrency integration tests.

## 7. Minimal SQL and Engineering Baseline

- SQL baseline:
  - Add `uk_user_voucher(user_id, voucher_id)`
  - Optional index for timeout scan: `(status, create_time)`
- Engineering baseline:
  - Idempotency keys for payment callbacks
  - Retry + dead-letter strategy for async order events
  - Observability: queue lag, reservation/order mismatch metrics, callback failure rate

## 8. Final Verdict

- Current implementation is not ready for payment-critical high-concurrency production.
- Main blockers are reliability and consistency (P0-level), not pure throughput.
- Fixing P0/P1 items will significantly reduce oversell and stock-loss risk.
