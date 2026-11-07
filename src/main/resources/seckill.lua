-- 秒杀预扣 Lua
--
-- KEYS:
--   1: stockKey         = seckill:stock:{voucherId}
--   2: orderKey         = seckill:order:{voucherId}
--   3: pendingKey       = seckill:pending:{voucherId}:{orderId}
--   4: pendingIndexKey  = seckill:pending:index
--   5: metaKey          = seckill:meta:{voucherId}
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
--   3 = 未初始化 (stock/meta key 不完整)
--   4 = 秒杀未开始
--   5 = 秒杀已结束

local stockKey         = KEYS[1]
local orderKey         = KEYS[2]
local pendingKey       = KEYS[3]
local pendingIndexKey  = KEYS[4]
local metaKey          = KEYS[5]

local userId    = ARGV[1]
local voucherId = ARGV[2]
local orderId   = ARGV[3]
local requestId = ARGV[4]
local nowTs     = ARGV[5]

local beginTsRaw = redis.call('hget', metaKey, 'beginTs')
local endTsRaw = redis.call('hget', metaKey, 'endTs')
local stockRaw = redis.call('get', stockKey)
if not beginTsRaw or not endTsRaw or not stockRaw then
    return 3
end

local now = tonumber(nowTs)
local beginTs = tonumber(beginTsRaw)
local endTs = tonumber(endTsRaw)
local stock = tonumber(stockRaw)
if not now or not beginTs or not endTs or not stock then
    return 3
end

if now < beginTs then
    return 4
end

if now > endTs then
    return 5
end

if redis.call('sismember', orderKey, userId) == 1 then
    return 2
end

if stock <= 0 then
    return 1
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
