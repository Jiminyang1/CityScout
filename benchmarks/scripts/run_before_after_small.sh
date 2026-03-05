#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)

K6_SCRIPT="$ROOT_DIR/benchmarks/k6/seckill.js"
TOKENS_FILE="${TOKENS_FILE:-$ROOT_DIR/benchmarks/data/tokens.json}"
RESET_SCRIPT="$ROOT_DIR/benchmarks/scripts/reset_state.sh"

BASE_URL="${BASE_URL:-http://localhost:8081}"
RATE="${RATE:-1200}"
WARMUP_SECONDS="${WARMUP_SECONDS:-5}"
MEASURE_SECONDS="${MEASURE_SECONDS:-15}"
RUNS="${RUNS:-1}"
INITIAL_STOCK="${INITIAL_STOCK:-20000}"
VOUCHER_BASE="${VOUCHER_BASE:-980000}"

DB_HOST=${DB_HOST:-127.0.0.1}
DB_PORT=${DB_PORT:-3306}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-123456}
DB_NAME=${DB_NAME:-hmdp}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-hm-dianping-mysql}
MYSQL=()

KAFKA_GROUP=${KAFKA_GROUP:-hmdp-order-group}
BENCH_TOPIC=${BENCH_TOPIC:-order.created.bench}

TS=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="$ROOT_DIR/benchmarks/results/before_after_small_$TS"
RAW_DIR="$RESULT_DIR/raw"
CFG_DIR="$RESULT_DIR/configs"
LOG_DIR="$RESULT_DIR/logs"
mkdir -p "$RAW_DIR" "$CFG_DIR" "$LOG_DIR"

DETAILS_CSV="$RESULT_DIR/run_details.csv"
SUMMARY_MD="$RESULT_DIR/summary.md"

echo "flow,variant,run,voucher_id,rate,req_count,tps,p95_ms,error_rate,success_count,no_stock_count,duplicate_count,lock_busy_count,mq_error_count,non200_count,http5xx_count,duplicate_users,sold_count,db_write_tps" > "$DETAILS_CSV"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1"; exit 1; }
}

require_cmd k6
require_cmd jq

if command -v mysql >/dev/null 2>&1; then
  MYSQL=(mysql -N -s -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME")
elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "^${MYSQL_CONTAINER}$"; then
  MYSQL=(docker exec -i "$MYSQL_CONTAINER" mysql -N -s -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME")
else
  echo "missing mysql client and mysql container (${MYSQL_CONTAINER}) not running"
  exit 1
fi

if [[ ! -f "$TOKENS_FILE" ]]; then
  echo "tokens file missing: $TOKENS_FILE"
  echo "run benchmarks/scripts/generate_token_pool.sh first"
  exit 1
fi

read_metric() {
  local file=$1
  local expr=$2
  jq -r "$expr // 0" "$file"
}

calc_div() {
  local a=$1
  local b=$2
  awk -v a="$a" -v b="$b" 'BEGIN { if (b == 0) printf "0"; else printf "%.4f", a / b }'
}

wait_kafka_lag_zero() {
  local timeout_seconds=${1:-60}
  local start_ts now lag_report lag

  if ! command -v docker >/dev/null 2>&1; then
    sleep 2
    return
  fi

  if ! docker ps --format '{{.Names}}' | grep -q '^hm-dianping-kafka$'; then
    sleep 2
    return
  fi

  start_ts=$(date +%s)
  while true; do
    lag_report=$(docker exec hm-dianping-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 \
      --describe \
      --group "$KAFKA_GROUP" 2>/dev/null || true)

    lag=$(printf '%s\n' "$lag_report" | awk -v topic="$BENCH_TOPIC" '
      NR > 1 && $2 == topic && $6 ~ /^[0-9]+$/ {sum += $6}
      END {print sum + 0}
    ')
    lag=${lag:-0}

    if [[ "$lag" == "0" ]]; then
      return
    fi

    now=$(date +%s)
    if (( now - start_ts >= timeout_seconds )); then
      echo "[warn] kafka lag not drained within ${timeout_seconds}s, lag=${lag}" >&2
      return
    fi
    sleep 2
  done
}

avg_col() {
  local flow=$1
  local col=$2
  awk -F, -v f="$flow" -v c="$col" '
    NR > 1 && $1 == f {sum += $c; cnt++}
    END {
      if (cnt == 0) printf "0";
      else printf "%.4f", sum / cnt;
    }' "$DETAILS_CSV"
}

max_col() {
  local flow=$1
  local col=$2
  awk -F, -v f="$flow" -v c="$col" '
    NR > 1 && $1 == f {if ($c > max) max = $c}
    END {print max + 0}' "$DETAILS_CSV"
}

append_row() {
  local flow=$1
  local variant=$2
  local run=$3
  local voucher_id=$4
  local raw_file=$5

  local req_count p95 error_rate success_count no_stock_count duplicate_count lock_busy_count mq_error_count non200_count http5xx_count
  local duplicate_users sold_count tps db_write_tps

  req_count=$(read_metric "$raw_file" '.metrics.bench_request_count.count')
  p95=$(read_metric "$raw_file" '.metrics.bench_latency["p(95)"]')
  error_rate=$(read_metric "$raw_file" '.metrics.bench_error_rate.value')

  success_count=$(read_metric "$raw_file" '.metrics.bench_success_count.count')
  no_stock_count=$(read_metric "$raw_file" '.metrics.bench_no_stock_count.count')
  duplicate_count=$(read_metric "$raw_file" '.metrics.bench_duplicate_count.count')
  lock_busy_count=$(read_metric "$raw_file" '.metrics.bench_lock_busy_count.count')
  mq_error_count=$(read_metric "$raw_file" '.metrics.bench_mq_error_count.count')
  non200_count=$(read_metric "$raw_file" '.metrics.bench_non_200_count.count')
  http5xx_count=$(read_metric "$raw_file" '.metrics.bench_http_5xx_count.count')

  duplicate_users=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=${voucher_id} GROUP BY user_id HAVING COUNT(*) > 1) t;")
  sold_count=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=${voucher_id};")

  tps=$(calc_div "$req_count" "$MEASURE_SECONDS")
  db_write_tps=$(calc_div "$sold_count" "$MEASURE_SECONDS")

  echo "${flow},${variant},${run},${voucher_id},${RATE},${req_count},${tps},${p95},${error_rate},${success_count},${no_stock_count},${duplicate_count},${lock_busy_count},${mq_error_count},${non200_count},${http5xx_count},${duplicate_users},${sold_count},${db_write_tps}" >> "$DETAILS_CSV"
}

run_case() {
  local flow=$1
  local variant=$2
  local run=$3
  local voucher_id=$4
  local cfg_file="$CFG_DIR/${flow}_run${run}.json"
  local raw_file="$RAW_DIR/${flow}_run${run}.json"
  local log_file="$LOG_DIR/${flow}_run${run}.log"

  "$RESET_SCRIPT" "$voucher_id" "$INITIAL_STOCK"

  cat > "$cfg_file" <<JSON
{
  "baseUrl": "${BASE_URL}",
  "variant": "${variant}",
  "voucherId": ${voucher_id},
  "rate": ${RATE},
  "warmupSeconds": ${WARMUP_SECONDS},
  "durationSeconds": ${MEASURE_SECONDS},
  "preAllocatedVUs": 300,
  "maxVUs": 12000,
  "timeoutMs": 2000
}
JSON

  echo "[run] flow=${flow} variant=${variant} run=${run} voucher=${voucher_id} rate=${RATE}"
  if ! k6 run \
    --summary-export "$raw_file" \
    -e CONFIG_FILE="$cfg_file" \
    -e TOKENS_FILE="$TOKENS_FILE" \
    "$K6_SCRIPT" > "$log_file" 2>&1; then
    echo "[warn] k6 exited non-zero for ${flow}-run${run}; continue"
  fi

  if [[ ! -f "$raw_file" ]]; then
    echo '{"metrics":{}}' > "$raw_file"
  fi

  if [[ "$variant" == "D" ]]; then
    wait_kafka_lag_zero 90
  fi

  append_row "$flow" "$variant" "$run" "$voucher_id" "$raw_file"
}

counter=1
for run in $(seq 1 "$RUNS"); do
  v_before=$((VOUCHER_BASE + counter))
  counter=$((counter + 1))
  run_case "Before_DB_SYNC" "B" "$run" "$v_before"

  v_after=$((VOUCHER_BASE + counter))
  counter=$((counter + 1))
  run_case "After_Redis_MQ" "D" "$run" "$v_after"
done

before_tps=$(avg_col "Before_DB_SYNC" 7)
after_tps=$(avg_col "After_Redis_MQ" 7)
before_p95=$(avg_col "Before_DB_SYNC" 8)
after_p95=$(avg_col "After_Redis_MQ" 8)
before_err=$(avg_col "Before_DB_SYNC" 9)
after_err=$(avg_col "After_Redis_MQ" 9)
before_db_tps=$(avg_col "Before_DB_SYNC" 19)
after_db_tps=$(avg_col "After_Redis_MQ" 19)
before_dup_users=$(max_col "Before_DB_SYNC" 17)
after_dup_users=$(max_col "After_Redis_MQ" 17)

tps_gain=$(awk -v b="$before_tps" -v a="$after_tps" 'BEGIN { if (b == 0) printf "0"; else printf "%.2f", ((a - b) / b) * 100 }')
p95_change=$(awk -v b="$before_p95" -v a="$after_p95" 'BEGIN { if (b == 0) printf "0"; else printf "%.2f", ((a - b) / b) * 100 }')

cat > "$SUMMARY_MD" <<MD
# Before vs After (Small)

## Config
- runs: ${RUNS}
- rate: ${RATE} RPS
- warmup: ${WARMUP_SECONDS}s
- measure: ${MEASURE_SECONDS}s
- initial_stock: ${INITIAL_STOCK}
- base_url: ${BASE_URL}

## Result
| Flow | Variant | Avg TPS | Avg P95 (ms) | Avg Error Rate | Avg DB Write TPS | duplicate_users_max |
|---|---|---:|---:|---:|---:|---:|
| Before_DB_SYNC | B | ${before_tps} | ${before_p95} | ${before_err} | ${before_db_tps} | ${before_dup_users} |
| After_Redis_MQ | D | ${after_tps} | ${after_p95} | ${after_err} | ${after_db_tps} | ${after_dup_users} |

## Delta (After vs Before)
- ingress TPS change: ${tps_gain}%
- p95 latency change: ${p95_change}% (negative is better)
MD

echo "[done] details: $DETAILS_CSV"
echo "[done] summary: $SUMMARY_MD"
