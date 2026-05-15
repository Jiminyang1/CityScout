#!/usr/bin/env bash
# 秒杀链路并发压测 (场景 F)
#
# 思路:
#   1. Reset 状态 (stock 还原到 STOCK 值)
#   2. 预创建 USER_COUNT 个测试用户拿 token
#   3. xargs -P 并发触发 USER_COUNT 个 seckill 请求
#   4. 等 consumer 处理完
#   5. 全面对账: DB orders == STOCK, Redis stock == 0, 无失败订单
#
# 用法: ./scripts/press_test.sh [STOCK] [USER_COUNT]
#   默认: STOCK=100, USER_COUNT=200

set -euo pipefail

BACKEND="${BACKEND:-http://localhost:8081/api}"
MYSQL_CTR="${MYSQL_CTR:-hm-dianping-mysql}"
REDIS_CTR="${REDIS_CTR:-hm-dianping-redis}"
MYSQL_PWD="${MYSQL_PWD:-123456}"
VOUCHER_ID="${VOUCHER_ID:-1}"
STOCK="${1:-100}"
USER_COUNT="${2:-200}"
CONCURRENCY="${CONCURRENCY:-200}"
CONSUMER_DRAIN_SEC="${CONSUMER_DRAIN_SEC:-30}"

GREEN='\033[0;32m'; RED='\033[0;31m'; BLUE='\033[0;34m'; YELLOW='\033[0;33m'; NC='\033[0m'
log()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; }

TMPDIR=$(mktemp -d)
# 不清理:留 /tmp/press_results.txt 给后续 debug 用
TOKENS=$TMPDIR/tokens.txt
RESULTS=/tmp/press_results.txt

mysql_exec() {
    docker exec "$MYSQL_CTR" mysql -uroot -p"$MYSQL_PWD" -N -B hmdp -e "$1" 2>/dev/null
}

# ---------- Step 1: Reset state ----------
log "Reset state: stock=$STOCK, voucher=$VOUCHER_ID"
docker exec "$REDIS_CTR" redis-cli SET "seckill:stock:$VOUCHER_ID" "$STOCK" > /dev/null
docker exec "$REDIS_CTR" redis-cli DEL "seckill:order:$VOUCHER_ID" "seckill:pending:index" > /dev/null
# 清掉 pending Hash (会有零碎,用 SCAN 安全删)
docker exec "$REDIS_CTR" redis-cli --scan --pattern "seckill:pending:$VOUCHER_ID:*" \
    | xargs -r -n100 docker exec "$REDIS_CTR" redis-cli DEL > /dev/null 2>&1 || true
mysql_exec "UPDATE tb_seckill_voucher SET stock=$STOCK WHERE voucher_id=$VOUCHER_ID;
            DELETE FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID;
            DELETE FROM tb_order_failed WHERE voucher_id=$VOUCHER_ID;"
ok "State reset"

# ---------- Step 2: Pre-create users + login ----------
log "Creating $USER_COUNT users..."
> "$TOKENS"

create_user() {
    local i=$1
    local phone=$(printf "130%08d" $i)
    local token=""
    for attempt in 1 2 3; do
        curl -s -X POST "$BACKEND/user/code?phone=$phone" -o /dev/null
        local code=$(docker exec "$REDIS_CTR" redis-cli GET "login:code:$phone" | tr -d '"\r')
        if [ -z "$code" ] || [ "$code" = "(nil)" ]; then
            sleep 0.2
            continue
        fi
        token=$(curl -s -X POST "$BACKEND/user/login" \
            -H "Content-Type: application/json" \
            -d "{\"phone\":\"$phone\",\"code\":\"$code\"}" \
            | python3 -c "import sys, json
try:
    d = json.load(sys.stdin)
    print(d.get('data','') if d.get('success') else '')
except: print('')")
        if [ -n "$token" ]; then break; fi
        sleep 0.2
    done
    echo "$token"
}
export -f create_user
export BACKEND REDIS_CTR

seq 1 "$USER_COUNT" | xargs -P 20 -I {} bash -c 'create_user "$@"' _ {} > "$TOKENS"
ACTUAL_USERS=$(grep -cv "^$" "$TOKENS" || true)
log "Got $ACTUAL_USERS valid tokens"
if [ "$ACTUAL_USERS" -ne "$USER_COUNT" ]; then
    fail "用户创建不全 ($ACTUAL_USERS / $USER_COUNT)"
    exit 1
fi

# ---------- Step 3: Fire concurrent seckill ----------
log "Firing $USER_COUNT concurrent seckill (concurrency=$CONCURRENCY)..."
RESULTS_DIR=$TMPDIR/responses
mkdir -p "$RESULTS_DIR"

fire_one() {
    local idx=$1 token=$2
    curl -s -X POST "$BACKEND/voucher-order/seckill/$VOUCHER_ID" \
        -H "authorization: $token" \
        > "$RESULTS_DIR/resp_${idx}.json"
}
export -f fire_one
export BACKEND VOUCHER_ID RESULTS_DIR

START_MS=$(python3 -c "import time; print(int(time.time()*1000))")
awk 'NF' "$TOKENS" | nl -ba | xargs -P "$CONCURRENCY" -L 1 bash -c 'fire_one "$1" "$2"' _
END_MS=$(python3 -c "import time; print(int(time.time()*1000))")
DUR_MS=$((END_MS - START_MS))

# 汇总每个响应文件,Python 分类
RD="$RESULTS_DIR" python3 - "$TMPDIR/counts.txt" <<'PYEOF'
import sys, json, os, glob
out_path = sys.argv[1]
files = sorted(glob.glob(os.environ["RD"] + "/resp_*.json"))
counts = {"success": 0, "stock_not_enough": 0, "already": 0, "not_init": 0, "other": 0, "empty": 0, "total": 0}
for f in files:
    counts["total"] += 1
    body = open(f).read().strip()
    if not body:
        counts["empty"] += 1; continue
    try:
        d = json.loads(body)
        if d.get("success"):
            counts["success"] += 1
        else:
            msg = d.get("errorMsg", "")
            if "库存不足" in msg: counts["stock_not_enough"] += 1
            elif "已下单" in msg: counts["already"] += 1
            elif "未就绪" in msg: counts["not_init"] += 1
            else: counts["other"] += 1
    except Exception:
        counts["other"] += 1
with open(out_path, "w") as fh:
    for k, v in counts.items():
        fh.write(f"{k}={v}\n")
PYEOF

HTTP_SUCCESS=$(grep '^success=' "$TMPDIR/counts.txt" | cut -d= -f2)
NOT_ENOUGH=$(grep '^stock_not_enough=' "$TMPDIR/counts.txt" | cut -d= -f2)
ALREADY=$(grep '^already=' "$TMPDIR/counts.txt" | cut -d= -f2)
NOT_INIT=$(grep '^not_init=' "$TMPDIR/counts.txt" | cut -d= -f2)
OTHER_FAIL=$(grep '^other=' "$TMPDIR/counts.txt" | cut -d= -f2)
EMPTY=$(grep '^empty=' "$TMPDIR/counts.txt" | cut -d= -f2)
TOTAL_RESP=$(grep '^total=' "$TMPDIR/counts.txt" | cut -d= -f2)
cp -r "$RESULTS_DIR" "$RESULTS" 2>/dev/null || true
ls "$RESULTS_DIR"/resp_*.json | head -1 | xargs -I {} cp {} "$RESULTS" 2>/dev/null || true

log "Request results in ${DUR_MS}ms (total responses: $TOTAL_RESP):"
log "  success(立即拿到 orderId) = $HTTP_SUCCESS"
log "  库存不足                   = $NOT_ENOUGH"
log "  用户已下单                 = $ALREADY"
log "  系统暂未就绪               = $NOT_INIT"
log "  其他失败                   = $OTHER_FAIL"
log "  空响应                     = $EMPTY"

# ---------- Step 4: Wait for consumer to drain ----------
log "Waiting ${CONSUMER_DRAIN_SEC}s for consumer + reconciler 收敛..."
for i in $(seq 1 "$CONSUMER_DRAIN_SEC"); do
    PENDING=$(docker exec "$REDIS_CTR" redis-cli ZCARD "seckill:pending:index" 2>/dev/null | tr -d '\r')
    DB_ORDERS=$(mysql_exec "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID AND status IN (1,2,3,5)")
    if [ "$PENDING" = "0" ] && [ "$DB_ORDERS" = "$STOCK" ]; then
        log "Consumer drained at ${i}s (pending=0, DB orders=$STOCK)"
        break
    fi
    sleep 1
done

# ---------- Step 5: Final assertions ----------
DB_STOCK=$(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
DB_ORDERS=$(mysql_exec "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID AND status IN (1,2,3,5)")
DB_DUP=$(mysql_exec "SELECT COUNT(*) FROM (SELECT user_id, voucher_id, COUNT(*) c FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID AND status IN (1,2,3,5) GROUP BY user_id, voucher_id HAVING c > 1) t")
REDIS_STOCK=$(docker exec "$REDIS_CTR" redis-cli GET "seckill:stock:$VOUCHER_ID" | tr -d '"\r')
REDIS_ORDER_SET=$(docker exec "$REDIS_CTR" redis-cli SCARD "seckill:order:$VOUCHER_ID" | tr -d '\r')
PENDING=$(docker exec "$REDIS_CTR" redis-cli ZCARD "seckill:pending:index" | tr -d '\r')
FAILED=$(mysql_exec "SELECT COUNT(*) FROM tb_order_failed WHERE voucher_id=$VOUCHER_ID")

echo
echo "============================================"
echo "  最终对账 (用户=$USER_COUNT, 库存=$STOCK)"
echo "============================================"
printf "  %-26s %s (期望 %s)\n" "DB stock"               "$DB_STOCK"        "0"
printf "  %-26s %s (期望 %s)\n" "DB 活跃订单数"            "$DB_ORDERS"       "$STOCK"
printf "  %-26s %s (期望 %s)\n" "DB 一人多单"             "$DB_DUP"          "0"
printf "  %-26s %s (期望 %s)\n" "Redis stock"            "$REDIS_STOCK"     "0"
printf "  %-26s %s (期望 %s)\n" "Redis order set"        "$REDIS_ORDER_SET" "$STOCK"
printf "  %-26s %s (期望 %s)\n" "Redis pending index"    "$PENDING"         "0"
printf "  %-26s %s (期望 %s)\n" "tb_order_failed"        "$FAILED"          "0"
echo "============================================"

ALL_OK=1
check() {
    local label="$1" actual="$2" expected="$3"
    if [ "$actual" = "$expected" ]; then
        ok "$label"
    else
        fail "$label: $actual != $expected"
        ALL_OK=0
    fi
}
check "无超卖 (DB orders = STOCK)"        "$DB_ORDERS"        "$STOCK"
check "无一人多单"                         "$DB_DUP"           "0"
check "DB stock 归零"                     "$DB_STOCK"         "0"
check "Redis stock 归零"                  "$REDIS_STOCK"      "0"
check "Redis order set = STOCK"           "$REDIS_ORDER_SET"  "$STOCK"
check "pending index 干净"                "$PENDING"          "0"
check "无 DLT 失败订单"                    "$FAILED"           "0"
check "HTTP 成功数 = STOCK"               "$HTTP_SUCCESS"     "$STOCK"
check "库存不足拒绝数 = 失败数"             "$NOT_ENOUGH"       "$((USER_COUNT - STOCK))"

echo
if [ $ALL_OK = 1 ]; then
    ok "压测全部通过：无超卖、状态完全一致"
    exit 0
else
    fail "压测有断言失败，见上"
    exit 1
fi
