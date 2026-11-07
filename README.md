# CityScout

CityScout is a local discovery and voucher demo app. The current focus is a high-concurrency seckill flow built around Redis pre-reservation, Kafka async order creation, MySQL final state, and scheduled reconciliation.

## Tech Stack

- Backend: Java 17, Spring Boot 3.5, Spring Kafka, MyBatis-Plus
- Frontend: React 18, Vite, TypeScript
- Storage: MySQL 8.0, Redis 6.2
- Messaging: Kafka 3.9 in KRaft mode
- Build and runtime: Maven, Docker, Docker Compose

## Architecture Highlights

The seckill order path is intentionally asynchronous:

```text
Client
  -> Nginx gateway rate limits /api traffic
  -> POST /api/voucher-order/seckill/{voucherId}
  -> Redis Lua reserves stock and writes pending
  -> Kafka order.created
  -> OrderCreatedConsumer writes MySQL order and DB stock
  -> afterCommit clears Redis pending
```

Failure paths are handled by explicit recovery mechanisms:

- Redis Lua writes `seckill:pending:{voucherId}:{orderId}` and `seckill:pending:index` (member `{voucherId}:{orderId}:{userId}`) so the reconciler can replay uncertain Kafka sends, and can still republish or release the reservation if the pending hash itself is lost.
- `DeadLetterPublishingRecoverer` writes `seckill:dlt:fence:{orderId}` before publishing to `order.created.DLT`.
- `OrderDltConsumer` refreshes the fence, persists `tb_order_failed` transactionally, then releases Redis reservation in `afterCommit`.
- `OrderReconcilerService` cleans stale pending rows, releases failed reservations, and republishes messages only when no DB order, failed row, or DLT fence exists.
- Consumer retries distinguish business failures from infrastructure outages: business failures (e.g. DB stock drift) get 1 attempt + 3 retries and then go to the DLT; transient DB/Redis failures (connection refused, Hikari timeout, lock wait timeout, `CannotCreateTransactionException`, …) back off exponentially (1s → 30s, unbounded by default) so a short MySQL outage does not turn already-reserved orders into failures.
- If Redis loses the seckill admission keys while the app is running, `seckill.lua` returns 3 and the order endpoint rebuilds that voucher's `seckill:order` / `seckill:meta` / `seckill:stock` from MySQL under a short lock (`lock:seckill:init:{voucherId}`), then retries once — no backend restart needed.
- Cancel and timeout release use `tb_order_release_retry` as a DB outbox so Redis release is retried after DB commit.
- Login code sending is protected by both gateway IP rate limiting and Redis business limits: one code per phone per 60 seconds, ten codes per phone per day.
- The React frontend polls order status only while the order is still `status=0` and backs off from 1s to 10s.

## Prerequisites

- JDK 17
- Maven 3.9+
- Docker Desktop / Docker Compose v2
- Node.js 20+ if running the frontend locally
## Quick Start With Docker

```bash
docker compose up -d --build mysql redis kafka backend gateway
```

Services:

- Gateway: `http://localhost:8080`
- Backend: `http://localhost:8081`
- MySQL: `localhost:3306`
- Redis: `localhost:6379`
- Kafka external listener: `localhost:9094`

Useful commands:

```bash
docker compose ps
docker compose logs -f backend
docker compose down
```

### Gateway

The Docker gateway is an Nginx reverse proxy and coarse IP rate limiter. It listens on `8080` and forwards `/api/*` to the backend service on the Docker network.

Configured limits:

- `/api/user/code`: `5r/m`, `burst=2`
- `/api/user/login`: `10r/m`, `burst=5`
- `/api/voucher-order/`: `10r/s`, `burst=30`
- other `/api/`: `60r/s`, `burst=120`

The Vite dev server still proxies directly to `http://localhost:8081`; gateway is mainly the Docker demo and load-test entry.

### Reset Demo Data

MySQL init scripts in `sql/` only run when the Docker volume is first created. If schema changes are added after a volume already exists, reset the demo environment:

```bash
docker compose down -v
docker compose up -d --build mysql redis kafka backend gateway
```

This deletes demo MySQL, Redis, and Kafka data.

## Local Development

Start infrastructure only:

```bash
docker compose up -d mysql redis kafka
```

Run backend locally:

```bash
mvn spring-boot:run
```

Run frontend locally:

```bash
cd frontend
npm install
npm run dev
```

The frontend dev server runs on `http://localhost:5173` and proxies `/api` to `http://localhost:8081`.

The local backend config uses:

- MySQL: `jdbc:mysql://localhost:3306/hmdp`
- Redis: `localhost:6379`
- Kafka: `localhost:9094`

Docker backend overrides these via environment variables in `docker-compose.yml`.

## Database

Main schema and seed data live in:

- `sql/00_schema_seed.sql`
- `sql/20260304_add_uk_user_voucher.sql`
- `sql/20260514_add_tb_order_failed.sql`
- `sql/20260515_add_tb_order_release_retry.sql`
- `sql/20260516_add_tb_shop_demo.sql`
- `sql/20260516_expand_dummy_catalog.sql`

Important tables:

- `tb_shop`: demo shops (voucher shop_id=1 points here)
- `tb_voucher_order`: final order state
- `tb_order_failed`: DLT failure terminal state
- `tb_order_release_retry`: outbox for Redis reservation release after cancel/timeout
- `tb_seckill_voucher`: DB-side voucher stock ledger

## API Examples

All backend routes are under `/api`. Use `8080` to go through gateway, or `8081` to call the backend directly during development.

```bash
# request login code
curl -X POST "http://localhost:8080/api/user/code?phone=13800000000"

# login, returns token
curl -X POST "http://localhost:8080/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","code":"123456"}'

# seckill order
curl -X POST "http://localhost:8080/api/voucher-order/seckill/1" \
  -H "authorization: <token>"

# query order
curl "http://localhost:8080/api/voucher-order/<orderId>" \
  -H "authorization: <token>"
```

## Verification

Backend checks:

```bash
zsh -lic 'mvn test -q'
zsh -lic 'mvn -q -DskipTests package'
git diff --check
```

Frontend checks:

```bash
cd frontend
npm install
npm run lint
npm run build
```

Docker checks:

```bash
docker compose build backend
docker compose up -d backend gateway
docker compose config
docker compose logs --since=2m backend
```

Expected startup signals include:

- `Started HmDianPingApplication`
- `Tomcat started on port 8081`
- Kafka partition assignment for `order.created`
- Kafka partition assignment for `order.created.DLT`

## Runtime Notes

- Java runtime should be 17.
- Spring Boot is 3.x, so code uses Jakarta package names.
- Redis config uses `spring.data.redis.*`.
- Kafka uses manual immediate ack and at-least-once delivery; idempotency is enforced with DB primary keys and unique indexes.
- Docker Redis runs with AOF `appendfsync everysec`; production should still add Redis HA.
- Kafka topics are declared by the Spring app with 8 partitions; Docker's broker default is also set to 8 to avoid accidental auto-create drift.
- `docs/` may exist as a local-only handbook in this workspace. It is intentionally ignored and should not be staged, committed, or treated as required project documentation.

## Known Gaps

- When Redis is rebuilt from MySQL after data loss, in-flight Kafka orders are not yet reflected in DB stock, so Redis may briefly admit a few extra users; the consumer's conditional DB decrement rejects them into the DLT (no DB oversell, but those users see a failed order). Redis still needs HA (Sentinel / replica) in production.
- `queryOrder` returns `status=0` when neither `tb_voucher_order` nor `tb_order_failed` has the order. This is fine for normal in-flight orders, but unknown or orphaned order ids need a timeout/failure policy.
- Scheduled jobs currently assume one active backend worker. Multi-instance deployments should add ShedLock or a dedicated worker profile.
- Voucher write APIs now require login, but there is no admin role model yet. Add role-based authorization before exposing write APIs beyond local demos.
