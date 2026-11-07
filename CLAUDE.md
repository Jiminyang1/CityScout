# CityScout - 本地商铺与秒杀券系统

## 项目概览

Spring Boot + Redis + Kafka + MySQL 秒杀系统，带 React + Vite + TypeScript 桌面 Web 前端。当前前端只覆盖第一阶段消费者闭环：登录、浏览商铺、查看券、秒杀下单、订单状态、模拟支付和取消订单。

## 架构

```
用户 Web → Vite dev server(5173) 或 nginx Gateway(8080)
          │
          ├─ Gateway: IP 粗限流 /api/user/code, /api/user/login, /api/voucher-order/, /api/*
          ↓
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

项目说明、架构与运行方式见 [`README.md`](README.md)。

## 核心组件

- **Frontend** `frontend/` — React + Vite + TS 桌面 Web，Vite proxy 将 `/api` 转到 `http://localhost:8081`
- **Gateway** `gateway/nginx.conf` — Docker demo 入口，监听 `8080`，对登录、验证码、订单和普通 API 做 IP 粗限流，超出返回 429
- **Redis Lua** `seckill.lua` — 原子完成：校验秒杀时间窗（`seckill:meta`）、校验一人一单、校验库存、扣库存+加 user、写 pending Hash + index ZSET。pending 是 Redis 侧 outbox 凭证；重复释放通过 `seckill:released:{orderId}` 做 7 天幂等保护。
- **Producer** — `kafkaTemplate.send()` fire-and-forget（不调 `.get()`），失败不补偿，依赖 reconciler
- **Consumer** — 幂等落库（INSERT + stock 扣减），失败抛异常 → Spring Kafka 重试 3 次 → DLT
- **DLT Consumer** `OrderDltConsumer` — 落 `tb_order_failed` + 调用 release Lua 释放 Redis 名额
- **Reconciler** `OrderReconcilerService` — 60s 定时扫描 pending index，三分支自愈（清理 / 释放 / 重投），并重试 cancel release outbox
- **取消** `cancelUnpaidOrder` — DB 事务内改状态 + 回补 stock + 写 `tb_order_release_retry`，Redis 释放放在 `afterCommit`，失败后定时重试
- **OrderTimeoutService** — 定时查询 15 分钟未支付订单，并通过 Spring 代理逐单调用事务取消流程，每单独立事务
- **验证码限流** `UserServiceImpl.sendCode` — 同手机号 60 秒一次、每天最多 10 次，Redis key 为 `login:code:cooldown:{phone}` 和 `login:code:daily:{phone}:{yyyyMMdd}`
- **上传** — 图片目录由 `app.upload.image-dir` 配置，默认 `./data/imgs`；文件名限制为安全后缀，删除路径限制在 `blogs/` 下
- **登录拦截** — 匿名路由白名单集中配置在 `MvcConfig`，商铺和优惠券写接口需要登录

## 技术栈

- Java 17, Spring Boot 3.5.x, MyBatis-Plus 3.5.16（spring-boot3 starter）
- React 18, Vite, TypeScript, ESLint
- Redis 6.2, Kafka 3.9 (KRaft), MySQL 8.0
- Spring Kafka 3.3.15（`DeadLetterPublishingRecoverer`）
- Jakarta APIs
- Docker Compose 一键部署

## Redis Key 设计

| Key | 类型 | 用途 |
|---|---|---|
| `seckill:stock:{voucherId}` | String | 库存计数 |
| `seckill:order:{voucherId}` | Set | 已下单 userId 集合（一人一单） |
| `seckill:pending:{voucherId}:{orderId}` | Hash | Lua 写入的待落库凭证（userId, requestId, createTs），TTL 24h |
| `seckill:pending:index` | ZSET | pending 全局索引，score=createTs，member=`{voucherId}:{orderId}` |
| `seckill:dlt:fence:{orderId}` | String | DLT 处理窗口标记，防止 reconciler 误重投失败订单，TTL 30 分钟 |
| `seckill:released:{orderId}` | String | 订单释放幂等标记，TTL 7 天 |
| `login:code:cooldown:{phone}` | String | 验证码 60 秒冷却 |
| `login:code:daily:{phone}:{yyyyMMdd}` | String | 验证码每日次数限制 |

## MySQL 表

- `tb_shop` — 演示商铺，`tb_voucher.shop_id` 引用；脚本在 `sql/20261015_add_tb_shop.sql`
- `tb_seckill_voucher` — 秒杀库存，consumer 事务内扣减
- `tb_voucher_order` — 订单生命周期，PK=orderId，唯一索引 `uk_user_voucher_active`（基于 `active_order_key` 生成列防一人多单）
- `tb_order_failed` — DLT 处理后的终态失败订单，`queryOrder` 回退查询
- `tb_order_release_retry` — 取消/退款后 Redis 名额释放的 retry outbox

## Kafka 配置

- `order.created` — 8 partition，producer key=userId（同用户消息串行）
- `order.created.DLT` — 死信，由 `DeadLetterPublishingRecoverer` 自动转发
- Consumer concurrency=8（匹配 partition 数）
- Producer `acks=all`, `enable.idempotence=true`, `max.block.ms=1000`

## 时区

JVM 默认时区在 `HmDianPingApplication.main()` 强制设为 UTC，与 MySQL 容器一致。**必须保留**，否则 `OrderTimeoutService` 会误删订单（CST vs UTC 8h 差导致比较失败）。

## 测试

- 后端：`mvn test -q`（不需要启动 MySQL、Redis 或 Kafka；Mockito 单元测试覆盖：
  `BlogCommentsServiceImplTest`、`BlogServiceImplTest`、`FollowServiceImplTest`、
  `OrderDltProcessingServiceTest`、`OrderReconcilerServiceTest`、`OrderTimeoutServiceTest`、
  `ShopServiceImplTest`、`UserServiceImplTest`、`VoucherOrderTxServiceTest`、
  `UploadControllerTest`、`KafkaConsumerConfigTest`、`RefreshTokenInterceptorTest`）
- `HmDianPingApplicationTests` 中的两个手动集成辅助方法需要本地 MySQL 和 Redis，默认跳过
- 前端：`cd frontend && npm run lint && npm run build`
- Docker/gateway：`docker compose config`，`docker compose up -d --build mysql redis kafka backend gateway`
- 端到端冒烟：`scripts/smoke_test.sh A|B|C|all`
  - A: happy path
  - B: Kafka 短暂不可用 → reconciler 重投（~3 分钟）
  - C: DB stock 漂移触发 consumer 失败 → DLT 释放

跑测试需要：JDK 17 + Node.js 20+ + Docker Compose v2。当前解析的 Spring Kafka 为 3.3.15，Lombok 为 1.18.46。

## 需要注意的点

1. **Redis 是名额事实源**：Lua 通过即对用户做出名额承诺，不可撤销（除非 DLT 主动释放或用户取消）。
2. **afterCommit 释放**：取消订单和 consumer 落库的 Redis 操作都放在 `TransactionSynchronizationManager.afterCommit`，避免事务回滚时 Redis 已经动手导致超卖。
3. **Bootstrap SETNX**：启动时不覆盖 Redis 现有库存值。Compose 已开启 Redis AOF（`appendfsync everysec`）；Redis 当前仍为单节点。
4. **唯一索引兜底**：`uk_user_voucher_active`（基于 `active_order_key` 生成列）是 consumer 端最后一道防线。Consumer 撞同 orderId = 幂等清 pending；撞同用户同券活跃订单 = 还回本次 Lua 预扣的 Redis stock，但保留 user set。
5. **DLT 触发条件**：消息处理 3 次失败 → DLT；唯一索引冲突算幂等不算失败。
6. **Reconciler 假设单实例**：当前未加分布式锁。多实例部署需要加 ShedLock 或类似。
7. **前端轮询降级**：订单详情只在 `status=0` 创建中时轮询，节奏为 `1s -> 2s -> 3s -> 5s -> 8s -> 10s`，终态立即停止。
8. **Nginx 不是自动多实例负载均衡**：当前 upstream 只有 `backend:8081`。要 scale backend，需要去掉 `container_name` 和宿主端口绑定，再用 Compose service DNS 或显式 upstream。
9. **超时取消事务**：`OrderTimeoutService` 只查询超时订单；每笔订单经 Spring 代理调用 `cancelUnpaidOrder`，各自在独立事务中提交后释放 Redis。
10. **访问控制**：匿名路由白名单位于 `MvcConfig`；商铺和优惠券写接口要求登录。

## 已知遗留

- `OrderTimeoutService` 当前依赖 JVM TZ=UTC 的硬编码。彻底修法是把 `LocalDateTime` 替成 `Instant` / `OffsetDateTime`。
- `PaymentSimulationService` 10% 模拟失败时无主动恢复，依赖 `OrderTimeoutService` 15 分钟超时。
- Reconciler 单实例假设。
- 单 Redis 节点，Lua KEYS 显式声明已做但 `seckill:pending:index` 全局 ZSET 在多 slot 集群下需要 hashtag 改造。
- `seckill:pending:index` member 目前不带 userId；如果 pending Hash 丢失，reconciler 无法完整释放 Redis 名额。
- `queryOrder` 对 DB/failed 都不存在的订单统一返回“创建中”，需要后续区分真实 in-flight 和未知/孤儿订单。
- 当前没有管理员角色；商铺和优惠券写接口虽要求登录，但任何登录用户都可访问。
