package com.hmdp.utils;


import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;

import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

public class SimpleRedisLock implements ILock{

    private static final String KEY_PREFIX = "lock:";
    //当前这个JVM创建的UUID
    private static final String THREAD_ID_PREFIX = UUID.randomUUID().toString() + "-";
    private final String name;
    private final StringRedisTemplate stringRedisTemplate;
    private static final DefaultRedisScript<Long> UNLOCK_SCRIPT;
    //使用静态代码块来加载Lua脚本
    static {
        UNLOCK_SCRIPT = new DefaultRedisScript<>();
        UNLOCK_SCRIPT.setLocation(new ClassPathResource("unlock.lua"));
        UNLOCK_SCRIPT.setResultType(Long.class);
    }

    //constructor
    public SimpleRedisLock(String name, StringRedisTemplate stringRedisTemplate) {
        this.name = name;
        this.stringRedisTemplate = stringRedisTemplate;
    }

    @Override
    public boolean tryLock(long timeoutSec) {
        //1. 获取当前线程的id
        long threadId = Thread.currentThread().getId();
        //2. 计算锁的key
        String key = KEY_PREFIX + name;
        String value = THREAD_ID_PREFIX + threadId;
        //3. 尝试获取锁
        //对应 SET key value NX EX, 是Redis的原子操作
        Boolean success = stringRedisTemplate.opsForValue().setIfAbsent(key, value, timeoutSec, TimeUnit.SECONDS);
        //4. 判断是否获取锁成功
        return Boolean.TRUE.equals(success);
    }

//    @Override
//    public void unlock() {
//        String key = KEY_PREFIX + name;
//        String expectedValue = THREAD_ID_PREFIX + Thread.currentThread().getId();
//        String currentValue = stringRedisTemplate.opsForValue().get(key);
//        if(expectedValue.equals(currentValue)){
//            //删除锁
//            stringRedisTemplate.delete(key);
//        }
//    }

    //调用Lua 脚本来保证删除锁的原子性
    @Override
    public void unlock() {
        String key = KEY_PREFIX + name;
        String expectedValue = THREAD_ID_PREFIX + Thread.currentThread().getId();
        //Lua脚本
        stringRedisTemplate.execute(UNLOCK_SCRIPT,
                Collections.singletonList(key),
                expectedValue);
    }
}
