#!/usr/bin/env bash
set -euo pipefail

ROOT_DIR=$(cd "$(dirname "$0")/../.." && pwd)

VOUCHER_ID=${1:-}
INITIAL_STOCK=${2:-60000}

if [[ -z "$VOUCHER_ID" ]]; then
  echo "usage: $0 <voucher_id> [initial_stock]"
  exit 1
fi

DB_HOST=${DB_HOST:-localhost}
DB_PORT=${DB_PORT:-3306}
DB_USER=${DB_USER:-root}
DB_PASS=${DB_PASS:-123456}
DB_NAME=${DB_NAME:-hmdp}

REDIS_HOST=${REDIS_HOST:-localhost}
REDIS_PORT=${REDIS_PORT:-6379}

MYSQL=(mysql -h"$DB_HOST" -P"$DB_PORT" -u"$DB_USER" -p"$DB_PASS" "$DB_NAME")
REDIS=(redis-cli -h "$REDIS_HOST" -p "$REDIS_PORT")

# keep seckill window fully open for benchmark
BEGIN_TIME="2024-01-01 00:00:00"
END_TIME="2099-12-31 23:59:59"

"${MYSQL[@]}" <<SQL
DELETE FROM tb_voucher_order WHERE voucher_id = ${VOUCHER_ID};

INSERT INTO tb_voucher (
  id, shop_id, title, sub_title, rules, pay_value, actual_value, type, status, create_time, update_time
) VALUES (
  ${VOUCHER_ID}, 1, 'benchmark-voucher-${VOUCHER_ID}', 'benchmark', 'benchmark', 100, 100, 1, 1, NOW(), NOW()
)
ON DUPLICATE KEY UPDATE
  title = VALUES(title),
  sub_title = VALUES(sub_title),
  rules = VALUES(rules),
  type = 1,
  status = 1,
  update_time = NOW();

INSERT INTO tb_seckill_voucher (
  voucher_id, stock, create_time, begin_time, end_time, update_time
) VALUES (
  ${VOUCHER_ID}, ${INITIAL_STOCK}, NOW(), '${BEGIN_TIME}', '${END_TIME}', NOW()
)
ON DUPLICATE KEY UPDATE
  stock = VALUES(stock),
  begin_time = VALUES(begin_time),
  end_time = VALUES(end_time),
  update_time = NOW();
SQL

"${REDIS[@]}" SET "seckill:stock:${VOUCHER_ID}" "${INITIAL_STOCK}" >/dev/null
"${REDIS[@]}" DEL "seckill:order:${VOUCHER_ID}" >/dev/null

echo "[reset_state] voucher_id=${VOUCHER_ID}, stock=${INITIAL_STOCK} reset done"
