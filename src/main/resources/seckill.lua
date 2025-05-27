-- 1. 参数列表
-- 优惠券id, userid
local voucherId = ARGV[1]
local userId = ARGV[2]

-- 2. 锁的key
-- 库存key, 订单key
local stockKey = 'seckill:stock:' .. voucherId
local orderKey = 'seckill:order:' .. voucherId

-- 1. 判断库存是否充足
if tonumber(redis.call('get', stockKey)) <= 0 then
    return 1 -- 库存不足
end
-- 2. 判断用户是否下单, 看用户id是否存在
if redis.call('sismember', orderKey, userId) == 1 then
    return 2 -- 用户已下单
end
-- 3. 扣减库存
redis.call('incrby', stockKey, -1)
-- 4. 将用户id存入订单集合
redis.call('sadd', orderKey, userId)
return 0 -- 成功