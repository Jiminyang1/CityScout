# CityScout - 秒杀购票系统

## 项目概览

Spring Boot + Redis + Kafka + MySQL 秒杀系统，前端 React + Vite。

## 架构

```
                                                ┌──────────────────────┐
用户请求 → Controller ─┬─ Lua 原子(扣库存+pending Hash) ─┐                │
                       │                                  │                │
                       └─→ 立即返回 orderId            Kafka order.created │
                                                          │                │
                                                          ↓                │
                                          Consumer (并发=8, 幂等落库)      │
                                                          │                │
                                              ┌───────────┴─────────────┐  │
                                          成功                       失败  │
                                              ↓                          ↓ │
                                  事务: INSERT + stock-1            DLT topic
                                  afterCommit: 清 pending                ↓ │
                                                                    DLT Consumer
                                                                    └─ tb_order_failed
                                                                    └─ Redis release
                                                                            ↑
                                                              定时扫 pending 索引
                                                              ┌─→ DB 有 → 清 pending
                                                              ├─→ failed 有 → 释放
                                                              └─→ 都没有 → 重投 Kafka
                                              OrderReconcilerService (60s/轮)
```

详细架构、数据流、设计取舍见 [`guide.md`](guide.md)。

## 核心组件

- **Redis Lua** `seckill.lua` — 原子完成 4 件事：校验库存、校验一人一单、扣库存+加 user、写 pending Hash + index ZSET。pending 是 Redis 侧 outbox 凭证。
- **Producer** — `kafkaTemplate.send()` fire-and-forget（不调 `.get()`），失败不补偿，依赖 reconciler
- **Consumer** — 幂等落库（INSERT + stock 扣减），失败抛异常 → Spring Kafka 重试 3 次 → DLT
- **DLT Consumer** `OrderDltConsumer` — 落 `tb_order_failed` + 调用 release Lua 释放 Redis 名额
- **Reconciler** `OrderReconcilerService` — 60s 定时扫描 pending index，三分支自愈（清理 / 释放 / 重投）
- **取消** `cancelUnpaidOrder` — DB 事务内改状态 + 回补 stock，Redis 释放放在 `afterCommit` 同步器
- **OrderTimeoutService** — 定时扫描 15 分钟未支付订单，走取消流程

## 技术栈

- Java 17, Spring Boot 2.7.12, MyBatis-Plus 3.4.3
- Redis 6.2, Kafka 3.9 (KRaft), MySQL 8.0
- Spring Kafka 2.9.x（`DeadLetterPublishingRecoverer`）
- Docker Compose 一键部署
- React 18 + Vite + lucide-react 前端

## Redis Key 设计

| Key | 类型 | 用途 |
|---|---|---|
| `seckill:stock:{voucherId}` | String | 库存计数 |
| `seckill:order:{voucherId}` | Set | 已下单 userId 集合（一人一单） |
| `seckill:pending:{voucherId}:{orderId}` | Hash | Lua 写入的待落库凭证（userId, requestId, createTs），TTL 24h |
| `seckill:pending:index` | ZSET | pending 全局索引，score=createTs，member=`{voucherId}:{orderId}` |

## MySQL 表

- `tb_seckill_voucher` — 秒杀库存，consumer 事务内扣减
- `tb_voucher_order` — 订单生命周期，PK=orderId，唯一索引 `uk_user_voucher_active`（基于 `active_order_key` 生成列防一人多单）
- `tb_order_failed` — DLT 处理后的终态失败订单，`queryOrder` 回退查询

## Kafka 配置

- `order.created` — 8 partition，producer key=userId（同用户消息串行）
- `order.created.DLT` — 死信，由 `DeadLetterPublishingRecoverer` 自动转发
- Consumer concurrency=8（匹配 partition 数）
- Producer `acks=all`, `enable.idempotence=true`, `max.block.ms=1000`

## 时区

JVM 默认时区在 `HmDianPingApplication.main()` 强制设为 UTC，与 MySQL 容器一致。**必须保留**，否则 `OrderTimeoutService` 会误删订单（CST vs UTC 8h 差导致比较失败）。

## 测试

- 单元测试：`src/test/java/com/hmdp/service/impl/{VoucherOrderTxServiceTest,OrderReconcilerServiceTest}.java`（20 个，Mockito，覆盖关键分支）
- 端到端冒烟：`scripts/smoke_test.sh A|B|C|all`
  - A: happy path
  - B: Kafka 短暂不可用 → reconciler 重投（~3 分钟）
  - C: DB stock 漂移触发 consumer 失败 → DLT 释放

跑测试需要：JDK 17（不是 JDK 25，Lombok 1.18.24 不兼容）+ `docker-compose up -d mysql redis kafka` + 本地 `mvn spring-boot:run`。

## 需要注意的点

1. **Redis 是名额事实源**：Lua 通过即对用户做出名额承诺，不可撤销（除非 DLT 主动释放或用户取消）。
2. **afterCommit 释放**：取消订单和 consumer 落库的 Redis 操作都放在 `TransactionSynchronizationManager.afterCommit`，避免事务回滚时 Redis 已经动手导致超卖。
3. **Bootstrap SETNX**：启动时不覆盖 Redis 现有库存值。Redis 必须靠 AOF + 主从保证持久性。
4. **唯一索引兜底**：`uk_user_voucher_active`（基于 `active_order_key` 生成列）是 consumer 端最后一道防线。Consumer 撞唯一索引 = 幂等跳过（不进 DLT）。
5. **DLT 触发条件**：消息处理 3 次失败 → DLT；唯一索引冲突算幂等不算失败。
6. **Reconciler 假设单实例**：当前未加分布式锁。多实例部署需要加 ShedLock 或类似。

## 待清理

- `nginx-1.18.0/` — 老的静态 HTML 前端，docker-compose 和 Dockerfile 已不再引用，删掉即可
- `interview-chain/`、`seckill-demo/` — 独立演示目录，和主项目无关
- 一堆历史 markdown（`AMAZON_*`、`INTERVIEW_*`、`PAYMENT_*`、`TECHNICAL_*`、`authentication_logic_analysis.md` 等）和 SVG，未跟踪或已删但未 commit

## 已知遗留

- `OrderTimeoutService` 当前依赖 JVM TZ=UTC 的硬编码。彻底修法是把 `LocalDateTime` 替成 `Instant` / `OffsetDateTime`。
- `PaymentSimulationService` 10% 模拟失败时无主动恢复，依赖 `OrderTimeoutService` 15 分钟超时。
- Reconciler 单实例假设。
- 单 Redis 节点，Lua KEYS 显式声明已做但 `seckill:pending:index` 全局 ZSET 在多 slot 集群下需要 hashtag 改造。
