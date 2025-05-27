-- ARGV[1] 是期望的锁的值
-- KEYS[1] 是锁的键名
if redis.call("get", KEYS[1]) == ARGV[1] then
    return redis.call("del", KEYS[1])
else
    return 0
end