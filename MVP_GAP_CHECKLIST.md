# Order Pipeline MVP Gap Checklist

## MVP implemented in this codebase

- API path uses `Redis Lua + Kafka + MySQL` without a distributed lock in the seckill hot path.
- Lua script atomically does: stock check, duplicate-user check, and stock reserve.
- If Kafka publish fails after Lua success, compensation Lua rolls back Redis reservation.
- Kafka consumer asynchronously persists orders with transactional service (`VoucherOrderTxService`).
- DB write path uses CAS stock decrement (`stock > 0`) and duplicate-order check.
- One-user-one-order DB unique index migration SQL is provided:
  - `sql/20260304_add_uk_user_voucher.sql`
- Third-party payment simulation is added (MVP):
  - Random delay `300~1800ms`
  - Random success rate `90%`
  - Successful simulation updates order `status` from `1` to `2`

## Still missing before production

1. **Payment lifecycle hardening**
- Current implementation is simulation for MVP.
- Production still needs real payment gateway callback, timeout-cancel, and refund flow.

2. **Message reliability hardening**
- Dead-letter topic and alerting action.
- Retry backoff tuning and poison-message handling policy.

3. **Compensation and reconciliation**
- Scheduled reconciliation between Redis reserve state and MySQL order state.
- Automatic repair workflow for drift.

4. **Seckill time validation in Lua (optional but recommended)**
- Current implementation checks time in service layer before Lua.
- For stronger consistency in distributed calls, duplicate window checks in Lua args and script.

5. **Observability**
- Metrics: Kafka lag, publish-fail rate, compensate count, order-create success rate.
- Tracing: requestId/orderId in logs and dashboards.

6. **Scalability tuning**
- Current Kafka consumer concurrency is conservative (`1`) for MVP.
- For higher throughput, scale consumer concurrency and enforce strict idempotency.

7. **Security and governance**
- Kafka auth/TLS and ACLs.
- Topic retention, partitioning, and quota policy.
