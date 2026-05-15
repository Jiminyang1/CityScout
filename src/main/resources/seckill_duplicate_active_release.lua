-- Restore Redis stock for a request rejected by the DB active-order unique key.
--
-- This keeps the user in seckill:order:{voucherId}, because an active DB order
-- already exists and Redis should continue blocking another purchase.
--
-- KEYS:
--   1: stockKey
--   2: orderKey
--   3: pendingKey
--   4: pendingIndexKey
--
-- ARGV:
--   1: userId
--   2: voucherId
--   3: orderId
--
-- Returns:
--   0 = no-op
--   1 = restored stock and cleared pending

local stockKey        = KEYS[1]
local orderKey        = KEYS[2]
local pendingKey      = KEYS[3]
local pendingIndexKey = KEYS[4]

local userId    = ARGV[1]
local voucherId = ARGV[2]
local orderId   = ARGV[3]

if redis.call('exists', pendingKey) ~= 1 then
    redis.call('zrem', pendingIndexKey, voucherId .. ':' .. orderId)
    return 0
end

local pendingUser = redis.call('hget', pendingKey, 'userId')
if pendingUser ~= userId then
    return 0
end

redis.call('del', pendingKey)
redis.call('zrem', pendingIndexKey, voucherId .. ':' .. orderId)
redis.call('sadd', orderKey, userId)
redis.call('incrby', stockKey, 1)

return 1
