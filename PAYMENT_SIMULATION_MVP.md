# Payment Simulation MVP (Kafka Branch)

## What is implemented

1. Added DB unique index for one-user-one-order:
- `(user_id, voucher_id)`

2. Added async third-party payment simulation:
- After order is persisted successfully, payment simulation starts **after transaction commit**.
- Payment delay is random: `300ms ~ 1800ms`.
- Success rate is random: `90%` success.
- On success: order status `1 -> 2`, and `pay_time` is filled.

## Files

- Order transaction and payment trigger:
  - `src/main/java/com/hmdp/service/impl/VoucherOrderTxService.java`
- Payment simulation service:
  - `src/main/java/com/hmdp/service/impl/PaymentSimulationService.java`
- SQL unique index migration:
  - `sql/20260304_add_uk_user_voucher.sql`
- Table definition updated:
  - `hmdp.sql`

## How to verify quickly

1. Ensure MySQL/Redis/Kafka are running.
2. Apply unique index SQL:

```sql
ALTER TABLE tb_voucher_order
ADD UNIQUE KEY uk_user_voucher (user_id, voucher_id);
```

3. Start app and send seckill request.
4. Immediately query order row: status should be `1` (unpaid) initially.
5. Query again after 1-2 seconds: status should become `2` for most orders.
6. In logs, check:
- `模拟支付成功...`
- or `模拟支付失败...` (about 10%).
