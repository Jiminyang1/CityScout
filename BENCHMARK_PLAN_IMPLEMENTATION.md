# 本地控制变量压测实现说明

本仓库已实现 A/B/C/D 四个秒杀变体与批量压测脚本，目标用于复现实验并支撑面试 STAR 叙述。

## 1. 已实现能力

- Benchmark 接口：`POST /api/benchmark/voucher-order/seckill/{variant}/{voucherId}`
- 四个变体：
  - A：事件级 Redisson 锁 + 同步 DB
  - B：用户级 Redisson 锁 + 同步 DB
  - C：用户级锁 + Redis Lua + 同步 DB（失败补偿）
  - D：用户级锁 + Redis Lua + Kafka 异步落库
- Kafka 双主题消费：`order.created` + `order.created.bench`
- 压测工具链：
  - `benchmarks/scripts/generate_token_pool.sh`
  - `benchmarks/scripts/reset_state.sh`
  - `benchmarks/scripts/run_matrix.sh`
  - `benchmarks/scripts/audit.sql`
- 结果产物：
  - `benchmarks/results/<timestamp>/run_details.csv`
  - `benchmarks/results/<timestamp>/summary.csv`
  - `benchmarks/results/<timestamp>/interview_table.md`

## 2. 快速执行

```bash
docker compose up -d
bash benchmarks/scripts/generate_token_pool.sh 80000
bash benchmarks/scripts/run_matrix.sh
```

## 3. 控制变量与统计口径

- Stage1：3000 RPS，A/B/C/D 各 5 轮，轮换执行顺序降低顺序偏差。
- Stage2：仅 D，3500/4500/5000/5500 RPS 各 3 轮。
- 每轮固定 reset，同机同配置。
- 汇总口径使用中位数与波动区间。

## 4. 正确性审计

- 一人一单：`duplicate_users = 0`
- 不超卖：`sold_count <= initial_stock`
- D 版本先等待 consumer lag 回落再审计。

## 5. 关键配置

- `benchmark.mode=true`
- `benchmark.kafka.topic=order.created.bench`
- `payment.simulation.enabled=false`（压测建议）

