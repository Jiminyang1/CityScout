# Benchmark Toolkit

This folder contains reproducible benchmark assets for the flash-sale purchasing module.

## Benchmark Endpoint

- `POST /api/benchmark/voucher-order/seckill/{variant}/{voucherId}`
- `variant`: `A | B | C | D`
- response payload:
  - `success`: boolean
  - `code`: `SUCCESS | NO_STOCK | DUPLICATE_ORDER | LOCK_BUSY | MQ_ERROR | ...`
  - `orderId`: optional long
  - `msg`: string
  - `variant`: string
  - `requestId`: string

## Structure

- `k6/seckill.js`: Main load script.
- `k6/config.json`: Runtime config template.
- `scripts/generate_token_pool.sh`: Generate users + redis login token pool.
- `scripts/reset_state.sh`: Reset voucher/order/redis state before each run.
- `scripts/audit.sql`: One-user-one-order and oversell audit SQL.
- `scripts/run_matrix.sh`: Execute full A/B/C/D matrix and capacity runs.
- `reports/`: Report templates for interview narratives.

## Quick start

1. Prepare dependencies: `k6`, `jq`, `mysql`, `redis-cli`, `docker` (optional for lag checks).
2. Start infra (includes MySQL/Redis/Kafka/backend):

```bash
docker compose up -d
```

3. Generate tokens:

```bash
bash benchmarks/scripts/generate_token_pool.sh 80000
```

4. Run matrix benchmark:

```bash
bash benchmarks/scripts/run_matrix.sh
```

5. Read output under:

- `benchmarks/results/<timestamp>/run_details.csv`
- `benchmarks/results/<timestamp>/summary.csv`
- `benchmarks/results/<timestamp>/interview_table.md`

## Notes

- Benchmark endpoint requires `benchmark.mode=true`.
- For consistent measurements, set `payment.simulation.enabled=false`.
- Stage2 (D capacity) assumes Kafka is available and consumer is healthy.
- `run_matrix.sh` executes:
  - Stage1: 3000 RPS, A/B/C/D each 5 runs (with rotated order to reduce sequence bias)
  - Stage2: D only, 3500/4500/5000/5500 RPS each 3 runs
