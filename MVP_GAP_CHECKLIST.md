# Order Pipeline MVP Gap Checklist

## MVP implemented in this codebase

- API path uses `Redis Lua + Kafka + MySQL` without a distributed lock in the seckill hot path.
- Lua script atomically does: stock check, duplicate-user check, stock reserve, and pending outbox write.
- If Kafka publish fails after Lua success, Redis reservation is not rolled back; `OrderReconcilerService` republishes from pending.
- Kafka consumer asynchronously persists orders with transactional service (`VoucherOrderTxService`).
- DB write path uses CAS stock decrement (`stock > 0`) and the `uk_user_voucher_active` unique key.
- Active-order duplicate conflicts restore the extra Redis stock while keeping the user blocked by the existing active order.
- Dead-letter handling writes `tb_order_failed` and releases Redis reservations.
- Cancel release failures are persisted in `tb_order_release_retry` and retried by the reconciler.
- Third-party payment simulation is added (MVP):
  - Random delay `300~1800ms`
  - Random success rate `90%`
  - Successful simulation updates order `status` from `1` to `2`

## Still missing before production

1. **Payment lifecycle hardening**
- Current implementation is simulation for MVP.
- Production still needs real payment gateway callback, timeout-cancel, and refund flow.

2. **Message reliability hardening**
- Alerting action for DLT volume and release retry backlog.
- Retry backoff tuning and poison-message handling policy.

3. **Compensation and reconciliation**
- Broader operational dashboards for Redis reserve state, MySQL order state, and retry backlog.
- Manual repair tooling for rare Redis data loss / operator-induced drift.

4. **Seckill time validation in Lua (optional but recommended)**
- Current implementation checks time in service layer before Lua.
- For stronger consistency in distributed calls, duplicate window checks in Lua args and script.

5. **Observability**
- Metrics: Kafka lag, publish-fail rate, compensate count, order-create success rate.
- Tracing: requestId/orderId in logs and dashboards.

6. **Scalability tuning**
- Current Kafka consumer concurrency is `8`, matching the demo topic partition count.
- For higher throughput, scale partition count/concurrency and keep strict idempotency.

7. **Security and governance**
- Kafka auth/TLS and ACLs.
- Topic retention, partitioning, and quota policy.
