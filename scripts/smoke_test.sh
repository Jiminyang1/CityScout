#!/usr/bin/env bash
# 秒杀链路端到端冒烟测试
#
# 假设:
#   - docker compose up -d 已经起好，所有服务 healthy
#   - 后端在 localhost:8081，Redis/MySQL/Kafka 容器名见 docker-compose.yml
#
# 测试场景:
#   A. happy path        - 下单 → 落库 → 查询
#   B. Kafka 短暂不可用   - 停 Kafka → 下单 → 启 Kafka → 等 reconciler 重投
#   C. Consumer 持续失败  - 通过 DB 触发约束冲突让消息进 DLT → 查 tb_order_failed
#
# 用法:
#   ./scripts/smoke_test.sh [A|B|C|all]
#
set -euo pipefail

BACKEND="${BACKEND:-http://localhost:8081/api}"
MYSQL_CTR="${MYSQL_CTR:-hm-dianping-mysql}"
REDIS_CTR="${REDIS_CTR:-hm-dianping-redis}"
KAFKA_CTR="${KAFKA_CTR:-hm-dianping-kafka}"
MYSQL_PWD="${MYSQL_PWD:-123456}"

VOUCHER_ID="${VOUCHER_ID:-1}"

# 颜色
GREEN='\033[0;32m'
RED='\033[0;31m'
YELLOW='\033[0;33m'
BLUE='\033[0;34m'
NC='\033[0m'

log()  { echo -e "${BLUE}[INFO]${NC} $*"; }
ok()   { echo -e "${GREEN}[PASS]${NC} $*"; }
warn() { echo -e "${YELLOW}[WARN]${NC} $*"; }
fail() { echo -e "${RED}[FAIL]${NC} $*"; exit 1; }

# --- 辅助函数 -----------------------------------------------------------------

mysql_exec() {
    docker exec "$MYSQL_CTR" mysql -uroot -p"$MYSQL_PWD" -N -B hmdp -e "$1" 2>/dev/null
}

redis_exec() {
    docker exec "$REDIS_CTR" redis-cli "$@"
}

# 取整数值，去掉 redis-cli --no-raw 的双引号
redis_get_int() {
    docker exec "$REDIS_CTR" redis-cli GET "$1" 2>/dev/null | tr -d '"' | tr -d '\r'
}

wait_backend_ready() {
    log "等待后端就绪..."
    for i in {1..60}; do
        local code
        code=$(curl -s -o /dev/null -w "%{http_code}" "$BACKEND/shop-type/list" 2>/dev/null || echo "000")
        if [ "$code" = "200" ]; then
            ok "后端就绪 (${i}s)"
            return 0
        fi
        sleep 1
    done
    fail "后端 60s 内未就绪"
}

# 生成合法测试手机号 (后端正则: 1[38][0-9]/14[579]/15[0-3,5-9]/166/17[0135678]/19[89])
gen_phone() {
    local prefixes=(130 131 132 133 134 135 136 137 138 139 180 181 182 183 184 185 186 187 188 189 198 199)
    local prefix=${prefixes[$((RANDOM % ${#prefixes[@]}))]}
    printf "%s%08d" "$prefix" $((RANDOM % 100000000))
}

# 用 sendCode + Redis 读 code + login 拿 token
login_as() {
    local phone="$1"
    curl -fsS -X POST "$BACKEND/user/code?phone=$phone" -o /dev/null
    local code
    code=$(redis_exec --no-raw GET "login:code:$phone" | tr -d '"')
    if [ -z "$code" ]; then
        fail "无法从 Redis 读取验证码 (phone=$phone)"
    fi

    local resp
    resp=$(curl -fsS -X POST "$BACKEND/user/login" \
        -H "Content-Type: application/json" \
        -d "{\"phone\":\"$phone\",\"code\":\"$code\"}")
    local token
    token=$(echo "$resp" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('data',''))")
    if [ -z "$token" ]; then
        fail "登录失败: $resp"
    fi
    echo "$token"
}

# 抢券，返回 orderId
seckill() {
    local token="$1" voucher_id="$2"
    local resp
    resp=$(curl -fsS -X POST "$BACKEND/voucher-order/seckill/$voucher_id" \
        -H "authorization: $token")
    local order_id
    order_id=$(echo "$resp" | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('data','')) if d.get('success') else sys.exit('seckill 失败: ' + str(d))")
    echo "$order_id"
}

# 查订单，返回 status 数字
query_order_status() {
    local token="$1" order_id="$2"
    curl -fsS "$BACKEND/voucher-order/$order_id" \
        -H "authorization: $token" \
        | python3 -c "import sys, json; d=json.load(sys.stdin); print(d.get('data',{}).get('status',''))"
}

# 等订单状态变成期望值，超时返回 1
wait_order_status() {
    local token="$1" order_id="$2" expected="$3" timeout="${4:-30}"
    for i in $(seq 1 "$timeout"); do
        local cur
        cur=$(query_order_status "$token" "$order_id" 2>/dev/null || echo "?")
        if [ "$cur" = "$expected" ]; then
            return 0
        fi
        sleep 1
    done
    return 1
}

dump_state() {
    local label="$1"
    echo "--- 状态快照: $label ---"
    echo "Redis stock(v=$VOUCHER_ID): $(redis_get_int "seckill:stock:$VOUCHER_ID")"
    echo "Redis order set 大小: $(redis_exec SCARD "seckill:order:$VOUCHER_ID")"
    echo "Redis pending index 大小: $(redis_exec ZCARD "seckill:pending:index")"
    echo "DB stock: $(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")"
    echo "DB orders (v=$VOUCHER_ID, 活跃): $(mysql_exec "SELECT COUNT(*) FROM tb_voucher_order WHERE voucher_id=$VOUCHER_ID AND status IN (1,2,3,5)")"
    echo "DB failed (v=$VOUCHER_ID): $(mysql_exec "SELECT COUNT(*) FROM tb_order_failed WHERE voucher_id=$VOUCHER_ID")"
    echo "---"
}

# --- 场景 A: happy path -------------------------------------------------------

scenario_A() {
    log "=== 场景 A: happy path ==="
    local phone token order_id status
    phone=$(gen_phone)
    token=$(login_as "$phone")
    log "测试用户登录成功, phone=$phone"

    local before_db_stock before_redis_stock
    before_db_stock=$(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
    before_redis_stock=$(redis_get_int "seckill:stock:$VOUCHER_ID")
    log "下单前: DB stock=$before_db_stock, Redis stock=$before_redis_stock"

    order_id=$(seckill "$token" "$VOUCHER_ID")
    log "下单成功, orderId=$order_id"

    if ! wait_order_status "$token" "$order_id" "1" 15; then
        dump_state "A 失败"
        fail "订单 15s 内未变为未支付状态"
    fi

    local after_db_stock after_redis_stock
    after_db_stock=$(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
    after_redis_stock=$(redis_get_int "seckill:stock:$VOUCHER_ID")
    log "下单后: DB stock=$after_db_stock, Redis stock=$after_redis_stock"

    if [ $((before_db_stock - 1)) -ne "$after_db_stock" ]; then
        fail "DB stock 没减 1 (前 $before_db_stock → 后 $after_db_stock)"
    fi
    if [ $((before_redis_stock - 1)) -ne "$after_redis_stock" ]; then
        fail "Redis stock 没减 1 (前 $before_redis_stock → 后 $after_redis_stock)"
    fi

    # pending 应该已经被 consumer afterCommit 清掉
    local pending_count
    pending_count=$(redis_exec EXISTS "seckill:pending:$VOUCHER_ID:$order_id")
    if [ "$pending_count" != "0" ]; then
        warn "pending Hash 未被清理 (可能是 race，reconciler 会兜): $pending_count"
    fi

    ok "场景 A 通过"
}

# --- 场景 B: Kafka 短暂不可用 → reconciler 接管 ----------------------------------

scenario_B() {
    log "=== 场景 B: Kafka 不可用 → reconciler 重投 ==="
    local phone token order_id
    phone=$(gen_phone)
    token=$(login_as "$phone")

    log "停 Kafka..."
    docker compose stop kafka >/dev/null 2>&1
    sleep 3

    log "Kafka 停的状态下下单 (Lua 应该通过、Kafka send 会失败)..."
    # send 不阻塞,所以 controller 还是会返回 orderId
    order_id=$(seckill "$token" "$VOUCHER_ID")
    log "拿到 orderId=$order_id"

    log "确认订单此时还没落库..."
    local mid_status
    mid_status=$(query_order_status "$token" "$order_id")
    if [ "$mid_status" != "0" ]; then
        warn "预期 status=0 (创建中), 实际=$mid_status — 可能 Kafka send 在停之前已经成功"
    fi

    # 确认 pending 有
    local pending_user
    pending_user=$(redis_exec --no-raw HGET "seckill:pending:$VOUCHER_ID:$order_id" userId)
    if [ -z "$pending_user" ] || [ "$pending_user" = "(nil)" ]; then
        dump_state "B 中间态"
        fail "Redis pending Hash 不存在,reconciler 没法兜底"
    fi
    ok "Redis pending Hash 存在 (userId=$pending_user)"

    log "启 Kafka..."
    docker compose start kafka >/dev/null 2>&1
    log "等 Kafka 健康..."
    for i in {1..40}; do
        if docker exec "$KAFKA_CTR" /opt/kafka/bin/kafka-topics.sh --bootstrap-server localhost:9092 --list >/dev/null 2>&1; then
            ok "Kafka 恢复 (${i}s)"
            break
        fi
        sleep 2
    done

    log "等 reconciler 重投 (默认 60s 一轮 + 90s 阈值 = 最多 150s)..."
    if wait_order_status "$token" "$order_id" "1" 180; then
        ok "场景 B 通过 - reconciler 重投成功落库"
    else
        dump_state "B 失败"
        fail "180s 后订单仍未落库"
    fi
}

# --- 场景 C: DB stock 漂移触发 Consumer 持续失败 → DLT --------------------------
# 思路:
#   人为把 DB stock 设为 0 (Redis stock 还有库存)
#   → Lua 通过、Kafka 发送、Consumer 的 UPDATE stock-1 WHERE stock>0 返 0 行
#   → 抛 IllegalStateException → 重试 3 次 → 进 DLT
#   → DLT consumer 落 tb_order_failed + 释放 Redis 名额
#
# 注意: 唯一索引冲突 (DuplicateKey) 会被 consumer 当幂等跳过,不会进 DLT。

scenario_C() {
    log "=== 场景 C: DB stock 漂移触发 Consumer 失败 → DLT ==="
    local phone token order_id
    phone=$(gen_phone)
    token=$(login_as "$phone")
    local user_id
    user_id=$(mysql_exec "SELECT id FROM tb_user WHERE phone='$phone'")
    log "测试用户 id=$user_id"

    local original_db_stock
    original_db_stock=$(mysql_exec "SELECT stock FROM tb_seckill_voucher WHERE voucher_id=$VOUCHER_ID")
    log "原 DB stock = $original_db_stock"

    log "人为把 DB stock 设为 0 (Redis stock 不动)..."
    mysql_exec "UPDATE tb_seckill_voucher SET stock=0 WHERE voucher_id=$VOUCHER_ID"

    log "下单 (Lua 通过, consumer 扣 DB 时 0 行受影响 → 抛异常)..."
    order_id=$(seckill "$token" "$VOUCHER_ID")
    log "新 orderId=$order_id"

    log "等 DLT 处理 (3 次 × 500ms + DLT consumer)..."
    sleep 10

    local failed_count
    failed_count=$(mysql_exec "SELECT COUNT(*) FROM tb_order_failed WHERE order_id=$order_id")
    if [ "$failed_count" = "1" ]; then
        ok "tb_order_failed 已写入"
    else
        log "再等 5s..."
        sleep 5
        failed_count=$(mysql_exec "SELECT COUNT(*) FROM tb_order_failed WHERE order_id=$order_id")
        if [ "$failed_count" != "1" ]; then
            dump_state "C 失败"
            fail "tb_order_failed 未写入 (count=$failed_count)"
        fi
        ok "tb_order_failed 已写入 (略迟)"
    fi

    local final_status
    final_status=$(query_order_status "$token" "$order_id")
    if [ "$final_status" = "-1" ]; then
        ok "queryOrder 返回 status=-1 终态"
    else
        fail "queryOrder 应返回 -1, 实际=$final_status"
    fi

    local in_set
    in_set=$(redis_exec SISMEMBER "seckill:order:$VOUCHER_ID" "$user_id")
    if [ "$in_set" = "0" ]; then
        ok "Redis order set 已释放 user"
    else
        warn "Redis order set 仍有 user (reconciler 稍后会清理)"
    fi

    log "恢复 DB stock = $original_db_stock..."
    mysql_exec "UPDATE tb_seckill_voucher SET stock=$original_db_stock WHERE voucher_id=$VOUCHER_ID"

    ok "场景 C 通过"
}

# --- 入口 ---------------------------------------------------------------------

main() {
    local target="${1:-all}"

    # 检查容器在线
    docker ps --format '{{.Names}}' | grep -q "$MYSQL_CTR" || fail "MySQL 容器未启动"
    docker ps --format '{{.Names}}' | grep -q "$REDIS_CTR" || fail "Redis 容器未启动"
    docker ps --format '{{.Names}}' | grep -q "$KAFKA_CTR" || fail "Kafka 容器未启动"

    wait_backend_ready
    dump_state "初始"

    case "$target" in
        A) scenario_A ;;
        B) scenario_B ;;
        C) scenario_C ;;
        all)
            scenario_A
            echo
            scenario_B
            echo
            scenario_C
            ;;
        *) fail "未知场景: $target (可选: A B C all)" ;;
    esac

    dump_state "结束"
    ok "全部完成"
}

main "$@"
