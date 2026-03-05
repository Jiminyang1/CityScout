#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)

K6_SCRIPT="$ROOT_DIR/benchmarks/k6/seckill.js"
TOKENS_FILE="${TOKENS_FILE:-$ROOT_DIR/benchmarks/data/tokens.json}"
RESET_SCRIPT="$ROOT_DIR/benchmarks/scripts/reset_state.sh"

BASE_URL="${BASE_URL:-http://localhost:8081}"
INITIAL_STOCK="${INITIAL_STOCK:-60000}"
WARMUP_SECONDS="${WARMUP_SECONDS:-10}"
MEASURE_SECONDS="${MEASURE_SECONDS:-30}"

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-3306}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-123456}
DB_NAME=${DB_NAME:-hmdp}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-hm-dianping-mysql}
MYSQL=()

KAFKA_GROUP=${KAFKA_GROUP:-hmdp-order-group}
BENCH_TOPIC=${BENCH_TOPIC:-order.created.bench}

RATE_STAGE1=${RATE_STAGE1:-3000}
VOUCHER_BASE=${VOUCHER_BASE:-900000}
STAGE1_RUNS=${STAGE1_RUNS:-5}
STAGE2_RUNS=${STAGE2_RUNS:-3}
STAGE2_RATES=${STAGE2_RATES:-"3500 4500 5000 5500"}

TS=$(date +%Y%m%d_%H%M%S)
RESULT_DIR="$ROOT_DIR/benchmarks/results/$TS"
RAW_DIR="$RESULT_DIR/raw"
CFG_DIR="$RESULT_DIR/configs"
LOG_DIR="$RESULT_DIR/logs"
mkdir -p "$RAW_DIR" "$CFG_DIR" "$LOG_DIR"

DETAILS_CSV="$RESULT_DIR/run_details.csv"
SUMMARY_CSV="$RESULT_DIR/summary.csv"
INTERVIEW_MD="$RESULT_DIR/interview_table.md"

echo "group,phase,variant,run,voucher_id,rate,req_count,tps,p50_ms,p95_ms,p99_ms,error_rate,success_count,no_stock_count,duplicate_count,lock_busy_count,mq_error_count,other_code_count,non200_count,http5xx_count,duplicate_users,sold_count,db_write_tps" > "$DETAILS_CSV"

require_cmd() {
  command -v "$1" >/dev/null 2>&1 || { echo "missing command: $1"; exit 1; }
}

require_cmd k6
require_cmd jq
require_cmd redis-cli

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

ensure_topic_ready() {
  local topic=$1
  local partitions=${2:-6}

  if ! command -v docker >/dev/null 2>&1; then
    return
  fi

  if ! docker ps --format '{{.Names}}' | grep -q '^hm-dianping-kafka$'; then
    return
  fi

  docker exec hm-dianping-kafka /opt/kafka/bin/kafka-topics.sh \
    --bootstrap-server localhost:9092 \
    --create \
    --if-not-exists \
    --topic "$topic" \
    --partitions "$partitions" \
    --replication-factor 1 >/dev/null 2>&1 || true
}

wait_kafka_lag_zero() {
  local topic=$1
  local timeout_seconds=${2:-90}

  if ! command -v docker >/dev/null 2>&1; then
    sleep 3
    return
  fi

  if ! docker ps --format '{{.Names}}' | grep -q '^hm-dianping-kafka$'; then
    sleep 3
    return
  fi

  local start_ts
  start_ts=$(date +%s)
  while true; do
    local lag
    local lag_report
    lag_report=$(docker exec hm-dianping-kafka /opt/kafka/bin/kafka-consumer-groups.sh \
      --bootstrap-server localhost:9092 \
      --group "$KAFKA_GROUP" \
      --topic "$topic" \
      --describe 2>/dev/null || true)
    lag=$(printf '%s\n' "$lag_report" | awk 'NR>1 && $5 ~ /^[0-9]+$/ {sum+=$5} END{print sum+0}')
    lag=${lag:-0}
    if [[ "$lag" == "0" ]]; then
      return
    fi

    local now
    now=$(date +%s)
    if (( now - start_ts >= timeout_seconds )); then
      echo "[warn] kafka lag not drained within ${timeout_seconds}s, current lag=${lag}" >&2
      return
    fi
    sleep 2
  done
}

generate_config() {
  local variant=$1
  local voucher_id=$2
  local rate=$3
  local config_file=$4

  cat > "$config_file" <<JSON
{
  "baseUrl": "${BASE_URL}",
  "variant": "${variant}",
  "voucherId": ${voucher_id},
  "rate": ${rate},
  "warmupSeconds": ${WARMUP_SECONDS},
  "durationSeconds": ${MEASURE_SECONDS},
  "preAllocatedVUs": 500,
  "maxVUs": 25000,
  "timeoutMs": 2000
}
JSON
}

read_metric() {
  local file=$1
  local expr=$2
  jq -r "$expr // 0" "$file"
}

calc_float_div() {
  local a=$1
  local b=$2
  awk -v a="$a" -v b="$b" 'BEGIN { if (b == 0) printf "0"; else printf "%.4f", a / b }'
}

append_row() {
  local group=$1
  local phase=$2
  local variant=$3
  local run=$4
  local voucher_id=$5
  local rate=$6
  local raw_file=$7

  local req_count p50 p95 p99 error_rate
  local success_count no_stock_count duplicate_count lock_busy_count mq_error_count other_code_count
  local non200_count http5xx_count duplicate_users sold_count tps db_write_tps

  req_count=$(read_metric "$raw_file" '.metrics.bench_request_count.count')
  p50=$(read_metric "$raw_file" '.metrics.bench_latency["p(50)"]')
  if [[ "$p50" == "0" ]]; then
    p50=$(read_metric "$raw_file" '.metrics.bench_latency.med')
  fi
  p95=$(read_metric "$raw_file" '.metrics.bench_latency["p(95)"]')
  p99=$(read_metric "$raw_file" '.metrics.bench_latency["p(99)"]')
  error_rate=$(read_metric "$raw_file" '.metrics.bench_error_rate.value')

  success_count=$(read_metric "$raw_file" '.metrics.bench_success_count.count')
  no_stock_count=$(read_metric "$raw_file" '.metrics.bench_no_stock_count.count')
  duplicate_count=$(read_metric "$raw_file" '.metrics.bench_duplicate_count.count')
  lock_busy_count=$(read_metric "$raw_file" '.metrics.bench_lock_busy_count.count')
  mq_error_count=$(read_metric "$raw_file" '.metrics.bench_mq_error_count.count')
  other_code_count=$(read_metric "$raw_file" '.metrics.bench_other_code_count.count')
  non200_count=$(read_metric "$raw_file" '.metrics.bench_non_200_count.count')
  http5xx_count=$(read_metric "$raw_file" '.metrics.bench_http_5xx_count.count')

  duplicate_users=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM (SELECT user_id FROM tb_voucher_order WHERE voucher_id=${voucher_id} GROUP BY user_id HAVING COUNT(*) > 1) t;")
  sold_count=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=${voucher_id};")

  tps=$(calc_float_div "$req_count" "$MEASURE_SECONDS")
  db_write_tps=$(calc_float_div "$sold_count" "$MEASURE_SECONDS")

  echo "${group},${phase},${variant},${run},${voucher_id},${rate},${req_count},${tps},${p50},${p95},${p99},${error_rate},${success_count},${no_stock_count},${duplicate_count},${lock_busy_count},${mq_error_count},${other_code_count},${non200_count},${http5xx_count},${duplicate_users},${sold_count},${db_write_tps}" >> "$DETAILS_CSV"
}

run_once() {
  local phase=$1
  local variant=$2
  local run=$3
  local rate=$4
  local group=$5

  local voucher_id=$((VOUCHER_BASE + run_counter))
  run_counter=$((run_counter + 1))

  "$RESET_SCRIPT" "$voucher_id" "$INITIAL_STOCK"

  local cfg_file="$CFG_DIR/${phase}_${variant}_run${run}.json"
  local raw_file="$RAW_DIR/${phase}_${variant}_run${run}.json"
  local log_file="$LOG_DIR/${phase}_${variant}_run${run}.log"

  generate_config "$variant" "$voucher_id" "$rate" "$cfg_file"

  echo "[run] phase=${phase} variant=${variant} run=${run} rate=${rate} voucher=${voucher_id}"

  if ! k6 run \
    --summary-export "$raw_file" \
    -e CONFIG_FILE="$cfg_file" \
    -e TOKENS_FILE="$TOKENS_FILE" \
    "$K6_SCRIPT" > "$log_file" 2>&1; then
    echo "[warn] k6 exited non-zero for ${phase}-${variant}-run${run}; keep result for analysis"
  fi

  if [[ ! -f "$raw_file" ]]; then
    echo '{"metrics":{}}' > "$raw_file"
  fi

  if [[ "$variant" == "D" ]]; then
    wait_kafka_lag_zero "$BENCH_TOPIC" 120
  fi

  append_row "$group" "$phase" "$variant" "$run" "$voucher_id" "$rate" "$raw_file"
}

calc_stats() {
  local group=$1
  local col=$2
  local arr=()
  while IFS= read -r line; do
    arr+=("$line")
  done < <(awk -F, -v g="$group" -v c="$col" 'NR>1 && $1==g {print $c}' "$DETAILS_CSV" | sort -n)

  local n=${#arr[@]}
  if (( n == 0 )); then
    echo "0,0,0,0,0"
    return
  fi

  local min=${arr[0]}
  local max=${arr[$((n-1))]}
  local q1=${arr[$(((n-1)/4))]}
  local q3=${arr[$(((3*(n-1))/4))]}

  local median
  if (( n % 2 == 1 )); then
    median=${arr[$((n/2))]}
  else
    median=$(awk -v a="${arr[$((n/2-1))]}" -v b="${arr[$((n/2))]}" 'BEGIN{printf "%.6f", (a+b)/2}')
  fi

  echo "${median},${q1},${q3},${min},${max}"
}

generate_summary() {
  echo "group,runs,tps_median,tps_q1,tps_q3,tps_min,tps_max,p95_median,p95_q1,p95_q3,p95_min,p95_max,error_rate_median,error_rate_q1,error_rate_q3,error_rate_max,duplicate_users_max,sold_count_median,db_write_tps_median,stable_target_met" > "$SUMMARY_CSV"

  local groups=()
  while IFS= read -r g; do
    groups+=("$g")
  done < <(awk -F, 'NR>1{print $1}' "$DETAILS_CSV" | sort -u)
  for g in "${groups[@]}"; do
    runs=$(awk -F, -v g="$g" 'NR>1 && $1==g {cnt++} END{print cnt+0}' "$DETAILS_CSV")

    IFS=',' read -r tps_m tps_q1 tps_q3 tps_min tps_max <<< "$(calc_stats "$g" 8)"
    IFS=',' read -r p95_m p95_q1 p95_q3 p95_min p95_max <<< "$(calc_stats "$g" 10)"
    IFS=',' read -r er_m er_q1 er_q3 _ er_max <<< "$(calc_stats "$g" 12)"
    IFS=',' read -r sold_m _ _ _ _ <<< "$(calc_stats "$g" 22)"
    IFS=',' read -r dbtps_m _ _ _ _ <<< "$(calc_stats "$g" 23)"

    duplicate_users_max=$(awk -F, -v g="$g" 'NR>1 && $1==g {if($21>max) max=$21} END{print max+0}' "$DETAILS_CSV")

    stable_target_met="NA"
    if [[ "$g" =~ ^D_[0-9]+$ ]]; then
      stable_target_met=$(awk -v p95="$p95_m" -v er="$er_m" 'BEGIN { if (p95 < 500 && er < 0.01) print "YES"; else print "NO" }')
    fi

    echo "${g},${runs},${tps_m},${tps_q1},${tps_q3},${tps_min},${tps_max},${p95_m},${p95_q1},${p95_q3},${p95_min},${p95_max},${er_m},${er_q1},${er_q3},${er_max},${duplicate_users_max},${sold_m},${dbtps_m},${stable_target_met}" >> "$SUMMARY_CSV"
  done
}

generate_interview_table() {
  cat > "$INTERVIEW_MD" <<MD
# Interview Table

## Stage 1 (A/B/C/D)

| Variant | TPS Median | P95 Median (ms) | Error Rate Median | Duplicate Users Max | Sold Count Median |
|---|---:|---:|---:|---:|---:|
MD

  for g in A B C D; do
    row=$(awk -F, -v g="$g" 'NR>1 && $1==g {print $3"|"$8"|"$13"|"$17"|"$18}' "$SUMMARY_CSV")
    if [[ -n "$row" ]]; then
      IFS='|' read -r tps p95 er dup sold <<< "$row"
      echo "| ${g} | ${tps} | ${p95} | ${er} | ${dup} | ${sold} |" >> "$INTERVIEW_MD"
    fi
  done

  cat >> "$INTERVIEW_MD" <<MD

## Stage 2 (D Capacity)

| Group | TPS Median | P95 Median (ms) | Error Rate Median | Stable Target |
|---|---:|---:|---:|---:|
MD

  awk -F, 'NR>1 && $1 ~ /^D_[0-9]+$/ {printf "| %s | %s | %s | %s | %s |\n", $1, $3, $8, $13, $20}' "$SUMMARY_CSV" >> "$INTERVIEW_MD"
}

run_counter=1

# ensure benchmark topics exist (single-node local kafka)
ensure_topic_ready "order.created" 6
ensure_topic_ready "$BENCH_TOPIC" 6

# Stage 1: A/B/C/D each N runs, rotate execution order per run
variants=(A B C D)
for ((run=1; run<=STAGE1_RUNS; run++)); do
  offset=$((run - 1))
  for idx in 0 1 2 3; do
    variant=${variants[$(((idx + offset) % 4))]}
    run_once "stage1" "$variant" "$run" "$RATE_STAGE1" "$variant"
  done
done

# Stage 2: D capacity each rate M runs
for rate in ${STAGE2_RATES}; do
  group="D_${rate}"
  for ((run=1; run<=STAGE2_RUNS; run++)); do
    run_once "stage2" "D" "$run" "$rate" "$group"
  done
done

generate_summary
generate_interview_table

echo "[done] details: $DETAILS_CSV"
echo "[done] summary: $SUMMARY_CSV"
echo "[done] interview table: $INTERVIEW_MD"
