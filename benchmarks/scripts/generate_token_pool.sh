#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)
DATA_DIR="$ROOT_DIR/benchmarks/data"
TOKENS_FILE="$DATA_DIR/tokens.json"
IDS_FILE="$DATA_DIR/user_ids.tmp"

TARGET_USERS=${1:-80000}

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-3306}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-123456}
DB_NAME=${DB_NAME:-hmdp}
MYSQL_CONTAINER=${MYSQL_CONTAINER:-hm-dianping-mysql}

REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}
LOGIN_TTL_SECONDS=${LOGIN_TTL_SECONDS:-2160000}

MYSQL=()
REDIS=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")

if command -v mysql >/dev/null 2>&1; then
  MYSQL=(mysql -N -s -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME")
elif command -v docker >/dev/null 2>&1 && docker ps --format '{{.Names}}' | grep -q "^${MYSQL_CONTAINER}$"; then
  MYSQL=(docker exec -i "$MYSQL_CONTAINER" mysql -N -s -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME")
else
  echo "missing mysql client and mysql container (${MYSQL_CONTAINER}) not running"
  exit 1
fi

mkdir -p "$DATA_DIR"

echo "[token_pool] target users: ${TARGET_USERS}"
current_count=$("${MYSQL[@]}" -e "SELECT COUNT(*) FROM tb_user;")
missing=$((TARGET_USERS - current_count))

if (( missing > 0 )); then
  echo "[token_pool] inserting missing users: ${missing}"
  batch_size=2000
  inserted=0
  while (( inserted < missing )); do
    current_batch=$batch_size
    if (( inserted + current_batch > missing )); then
      current_batch=$((missing - inserted))
    fi

    values=""
    for ((i=0; i<current_batch; i++)); do
      seq_num=$((current_count + inserted + i + 1))
      phone=$(printf "1%010d" "$seq_num")
      nick="bench_user_${seq_num}"
      values+="('${phone}','', '${nick}', '', NOW(), NOW()),"
    done
    values=${values%,}

    "${MYSQL[@]}" -e "
      INSERT IGNORE INTO tb_user (phone, password, nick_name, icon, create_time, update_time)
      VALUES ${values};"

    inserted=$((inserted + current_batch))
    echo "[token_pool] inserted ${inserted}/${missing}"
  done
fi

echo "[token_pool] collecting ${TARGET_USERS} user ids"
"${MYSQL[@]}" -e "SELECT id FROM tb_user ORDER BY id LIMIT ${TARGET_USERS};" > "$IDS_FILE"

echo "[token_pool] writing tokens to redis + json"
{
  echo "["
  line_no=0
  while IFS= read -r user_id; do
    token="$(uuidgen | tr '[:upper:]' '[:lower:]')_${user_id}"
    key="login:token:${token}"

    "${REDIS[@]}" HSET "$key" id "$user_id" nickName "bench_${user_id}" >/dev/null
    "${REDIS[@]}" EXPIRE "$key" "$LOGIN_TTL_SECONDS" >/dev/null

    if (( line_no > 0 )); then
      echo ","
    fi
    printf "  \"%s\"" "$token"

    line_no=$((line_no + 1))
    if (( line_no % 1000 == 0 )); then
      echo >&2 "[token_pool] processed ${line_no}/${TARGET_USERS}"
    fi
  done < "$IDS_FILE"
  echo
  echo "]"
} > "$TOKENS_FILE"

rm -f "$IDS_FILE"
echo "[token_pool] done: ${TOKENS_FILE}"
