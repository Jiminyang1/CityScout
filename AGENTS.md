# AGENTS.md

本文件给 Codex / Claude / 其他 coding agent 使用。它是仓库内可追踪的协作约定；本机私有笔记不要写进 Git。

## 项目概览

CityScout 是 Spring Boot + Redis + Kafka + MySQL 的本地商铺与秒杀券 demo，前端是 React + Vite + TypeScript。

核心秒杀链路：

1. 前端调用 `POST /api/voucher-order/seckill/{voucherId}`。
2. Nginx gateway 对 `/api/*` 做粗限流。
3. Redis Lua 校验时间窗、库存、一人一单，并写 pending outbox。
4. Controller 立即返回 `orderId`。
5. Kafka `order.created` 异步落库。
6. Consumer 事务内写 `tb_voucher_order` 并扣 DB stock。
7. afterCommit 清 Redis pending；失败进入 DLT，DLT consumer 写 `tb_order_failed` 并释放 Redis 名额。
8. Reconciler 定时扫描 pending index，清理、释放或重投。

可共享架构文档以 `README.md`、`CLAUDE.md` 为准。

## 本地文件约定

- `docs/` 是本机 local-only 交互式教学手册，已通过 `.git/info/exclude` 忽略。
- 可以阅读 `docs/` 辅助理解，但不要 `git add docs/`，不要提交它，也不要假设其他开发者一定有它。
- 改了秒杀链路、Redis key、Kafka 配置、表结构等行为时，更新 `README.md`、`CLAUDE.md`、`AGENTS.md`；本机有 `docs/` 时顺手更新对应章节。

## 常用命令

后端验证：

```bash
mvn test -q
mvn -q -DskipTests package
```

前端验证：

```bash
cd frontend
npm run lint
npm run build
```

Docker / gateway 验证：

```bash
docker compose config
docker compose up -d --build mysql redis kafka backend gateway
docker compose logs -f backend
```

代码卫生：

```bash
git diff --check
git status --short --untracked-files=all
```

## 重要路径

- Backend entry: `src/main/java/com/hmdp/HmDianPingApplication.java`
- Controllers: `src/main/java/com/hmdp/controller/`
- Seckill service: `src/main/java/com/hmdp/service/impl/VoucherOrderServiceImpl.java`
- Transaction boundary: `src/main/java/com/hmdp/service/impl/VoucherOrderTxService.java`
- Reconciler: `src/main/java/com/hmdp/service/impl/OrderReconcilerService.java`
- Kafka config: `src/main/java/com/hmdp/config/KafkaConsumerConfig.java`
- Lua scripts: `src/main/resources/seckill.lua`, `seckill_release.lua`, `seckill_duplicate_active_release.lua`
- Schema / seed: `sql/`
- Frontend: `frontend/src/`
- Gateway: `gateway/nginx.conf`

## Architecture Invariants

- Redis is the admission/source-of-truth for seckill slots. Lua success means the user has been promised a slot.
- MySQL is the auditable order lifecycle source of truth.
- Kafka is the async transport; do not block controller threads on `send().get()`.
- Pending Redis hashes and `seckill:pending:index` are recovery credentials, not user-facing status.
- `tb_order_failed` is the terminal failed state used by `queryOrder`.
- Redis mutations that depend on a DB transaction must happen after commit.
- Duplicate Kafka delivery is expected; business idempotency is enforced by DB PK / unique indexes and Redis compensation scripts.
- `seckill:dlt:fence:{orderId}` prevents DLT processing from racing with reconciler republish.
- `seckill:pending:index` members are `{voucherId}:{orderId}:{userId}` (legacy `{voucherId}:{orderId}` still accepted); keep Lua scripts, `VoucherOrderTxService` cleanup and `OrderReconcilerService` parsing in sync.
- Consumer retry policy: only business failures go to the DLT (1 attempt + 3 retries); transient DB/Redis failures (see `TransientFailures`) back off exponentially without going to the DLT.
- Missing Redis admission keys (Lua code 3) are rebuilt on demand by `SeckillRedisInitializer`; `SeckillStockBootstrap` uses the same initializer at startup.

## Known Gaps

- `queryOrder` still returns `status=0` when neither `tb_voucher_order` nor `tb_order_failed` has the order; unknown IDs need a timeout/failure policy.
- Reconciler assumes one active backend worker. Multi-instance deployments need ShedLock or a dedicated worker profile.
- Redis is single-node in local Docker. Docker enables AOF, but production still needs HA. Rebuilding Redis from MySQL after data loss can briefly over-admit while Kafka still holds in-flight orders; those land in the DLT.
- Redis Cluster requires redesigning `seckill:pending:index` or applying per-voucher hash tags.
- Voucher write APIs require login but do not yet have admin role authorization.

## Agent Working Rules

- Do not revert user changes unless explicitly asked.
- Keep changes scoped to the requested behavior.
- Prefer existing Spring / MyBatis-Plus / React patterns over new abstractions.
- Use `rg` for search.
- Use `apply_patch` for manual file edits.
- Run focused verification before final response when code or config changes.
- If committing, split large changes by topic and keep commit messages conventional.
