# CityScout

CityScout is a local discovery and voucher demo app. The current focus is a high-concurrency seckill flow built around Redis pre-reservation, Kafka async order creation, MySQL final state, and scheduled reconciliation.

## Tech Stack

- Backend: Java 17, Spring Boot 3.5, Spring Kafka, MyBatis-Plus
- Frontend: React 18, Vite, lucide-react
- Storage: MySQL 8.0, Redis 6.2
- Messaging: Kafka 3.9 in KRaft mode
- Build and runtime: Maven, Docker, Docker Compose

## Architecture Highlights

The seckill order path is intentionally asynchronous:

```text
Frontend
  -> POST /api/voucher-order/seckill/{voucherId}
  -> Redis Lua reserves stock and writes pending
  -> Kafka order.created
  -> OrderCreatedConsumer writes MySQL order and DB stock
  -> afterCommit clears Redis pending
```

Failure paths are handled by explicit recovery mechanisms:

- Redis Lua writes `seckill:pending:{voucherId}:{orderId}` and `seckill:pending:index` so the reconciler can replay uncertain Kafka sends.
- `DeadLetterPublishingRecoverer` writes `seckill:dlt:fence:{orderId}` before publishing to `order.created.DLT`.
- `OrderDltConsumer` refreshes the fence, persists `tb_order_failed` transactionally, then releases Redis reservation in `afterCommit`.
- `OrderReconcilerService` cleans stale pending rows, releases failed reservations, and republishes messages only when no DB order, failed row, or DLT fence exists.
- Cancel and timeout release use `tb_order_release_retry` as a DB outbox so Redis release is retried after DB commit.

## Prerequisites

- JDK 17
- Maven 3.9+
- Docker Desktop / Docker Compose v2
- Node.js 20+ if running the frontend locally without Docker

## Quick Start With Docker

```bash
docker compose up -d --build mysql redis kafka backend frontend
```

Services:

- Frontend: `http://localhost:8080`
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

### Reset Demo Data

MySQL init scripts in `sql/` only run when the Docker volume is first created. If schema changes are added after a volume already exists, reset the demo environment:

```bash
docker compose down -v
docker compose up -d --build mysql redis kafka backend frontend
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

The local backend config uses:

- MySQL: `jdbc:mysql://localhost:3306/hmdp`
- Redis: `localhost:6379`
- Kafka: `localhost:9094`

Docker backend overrides these via environment variables in `docker-compose.yml`.

## Database

Main schema and seed data live in:

- `sql/00_schema_seed.sql`
- `sql/20260515_add_tb_order_release_retry.sql`

Important tables:

- `tb_voucher_order`: final order state
- `tb_order_failed`: DLT failure terminal state
- `tb_order_release_retry`: outbox for Redis reservation release after cancel/timeout
- `tb_seckill_voucher`: DB-side voucher stock ledger

## API Examples

All backend routes are under `/api`.

```bash
# request login code
curl -X POST "http://localhost:8081/api/user/code?phone=13800000000"

# login, returns token
curl -X POST "http://localhost:8081/api/user/login" \
  -H "Content-Type: application/json" \
  -d '{"phone":"13800000000","code":"123456"}'

# seckill order
curl -X POST "http://localhost:8081/api/voucher-order/seckill/1" \
  -H "authorization: <token>"

# query order
curl "http://localhost:8081/api/voucher-order/<orderId>" \
  -H "authorization: <token>"
```

## Verification

Backend checks:

```bash
zsh -lic 'mvn test -q'
zsh -lic 'mvn -q -DskipTests package'
git diff --check
```

Docker checks:

```bash
docker compose build backend
docker compose up -d backend
docker compose logs --since=2m backend
```

Expected startup signals include:

- `Started HmDianPingApplication`
- `Tomcat started on port 8081`
- Kafka partition assignment for `order.created`
- Kafka partition assignment for `order.created.DLT`

Frontend checks:

```bash
cd frontend
npm install
npm run build
```

## Runtime Notes

- Java runtime should be 17.
- Spring Boot is 3.x, so code uses Jakarta package names.
- Redis config uses `spring.data.redis.*`.
- Kafka uses manual immediate ack and at-least-once delivery; idempotency is enforced with DB primary keys and unique indexes.
- `guide.md` is a local deep-dive document and is intentionally ignored by git in this workspace.
