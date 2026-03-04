# Benchmark Report Template

## 1. Environment

- Machine:
- CPU limit:
- JVM settings:
- MySQL/Redis/Kafka versions:
- Test date:

## 2. Experiment Matrix

- Stage1: A/B/C/D, 3000 RPS, 5 runs each
- Stage2: D only, 3500/4500/5000/5500 RPS, 3 runs each

## 3. Stage1 Results

| Variant | TPS Median | P95 Median | Error Rate Median | duplicate_users_max | sold_count_median |
|---|---:|---:|---:|---:|---:|
| A |  |  |  |  |  |
| B |  |  |  |  |  |
| C |  |  |  |  |  |
| D |  |  |  |  |  |

## 4. Stage2 Results (D Capacity)

| Rate | TPS Median | P95 Median | Error Rate Median |
|---:|---:|---:|---:|
| 3500 |  |  |  |
| 4500 |  |  |  |
| 5000 |  |  |  |
| 5500 |  |  |  |

## 5. Correctness Audit

- One-user-one-order violations:
- Oversell violations:
- Kafka lag notes:

## 6. Conclusions

- A -> B:
- B -> C:
- C -> D:
- Capacity statement:
