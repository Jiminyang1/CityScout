-- 秒杀名额释放 Lua
-- 由以下场景调用:
--   1. DLT consumer  - 订单彻底失败,释放名额
--   2. Cancel order  - 用户取消未支付订单 (afterCommit)
--   3. Reconciler    - 自愈分支
--
-- KEYS:
--   1: stockKey         = seckill:stock:{voucherId}
--   2: orderKey         = seckill:order:{voucherId}
--   3: pendingKey       = seckill:pending:{voucherId}:{orderId}
--   4: pendingIndexKey  = seckill:pending:index
--
-- ARGV:
--   1: userId
--   2: voucherId
--   3: orderId
--   4: forceUserRelease  -- "1": 即使 pending 不存在也释放 user (取消场景); "0": 仅 pending 存在时释放
--
-- 返回:
--   0 = 未执行 (没什么可释放)
--   1 = 完整释放 (user + stock + pending)
--   2 = 仅清理 pending (user 已经不在 set, 比如重复调用)
--   3 = 仅释放 user (取消场景下 pending 已被 consumer 清理)

local stockKey        = KEYS[1]
local orderKey        = KEYS[2]
local pendingKey      = KEYS[3]
local pendingIndexKey = KEYS[4]

local userId    = ARGV[1]
local voucherId = ARGV[2]
local orderId   = ARGV[3]
local forceUserRelease = ARGV[4]

local indexMember = voucherId .. ':' .. orderId
local pendingExists = redis.call('exists', pendingKey) == 1
local userInSet = redis.call('sismember', orderKey, userId) == 1

if pendingExists then
    -- 校验 pending 里的 userId 匹配,防止 orderId 串号误释放
    local pendingUser = redis.call('hget', pendingKey, 'userId')
    if pendingUser ~= userId then
        return 0
    end

    redis.call('del', pendingKey)
    redis.call('zrem', pendingIndexKey, indexMember)

    if userInSet then
        redis.call('srem', orderKey, userId)
        redis.call('incrby', stockKey, 1)
        return 1
    end
    return 2
end

-- pending 已不在,但取消场景仍需释放 user
if forceUserRelease == '1' and userInSet then
    redis.call('srem', orderKey, userId)
    redis.call('incrby', stockKey, 1)
    redis.call('zrem', pendingIndexKey, indexMember)
    return 3
end

return 0
