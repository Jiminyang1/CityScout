-- 秒杀预扣 Lua
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
--   4: requestId
--   5: nowTsMs
--
-- 返回:
--   0 = 成功
--   1 = 库存不足
--   2 = 已下单
--   3 = 未初始化 (stock key 不存在)

local stockKey         = KEYS[1]
local orderKey         = KEYS[2]
local pendingKey       = KEYS[3]
local pendingIndexKey  = KEYS[4]

local userId    = ARGV[1]
local voucherId = ARGV[2]
local orderId   = ARGV[3]
local requestId = ARGV[4]
local nowTs     = ARGV[5]

local stockRaw = redis.call('get', stockKey)
if not stockRaw then
    return 3
end

local stock = tonumber(stockRaw)
if stock <= 0 then
    return 1
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

redis.call('incrby', stockKey, -1)
redis.call('sadd', orderKey, userId)

redis.call('hset', pendingKey,
    'userId', userId,
    'voucherId', voucherId,
    'orderId', orderId,
    'requestId', requestId,
    'createTs', nowTs)
redis.call('expire', pendingKey, 86400)

local indexMember = voucherId .. ':' .. orderId
redis.call('zadd', pendingIndexKey, nowTs, indexMember)

return 0
