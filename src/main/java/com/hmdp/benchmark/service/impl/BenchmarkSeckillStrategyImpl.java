package com.hmdp.benchmark.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.benchmark.dto.BenchmarkSeckillResponse;
import com.hmdp.benchmark.service.BenchmarkSeckillStrategy;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.impl.VoucherOrderTxService;
import com.hmdp.utils.RedisIdWorker;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;

@Service
public class BenchmarkSeckillStrategyImpl implements BenchmarkSeckillStrategy {

    private static final Logger log = LoggerFactory.getLogger(BenchmarkSeckillStrategyImpl.class);

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;
    private static final DefaultRedisScript<Long> SECKILL_COMPENSATE_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);

        SECKILL_COMPENSATE_SCRIPT = new DefaultRedisScript<>();
        SECKILL_COMPENSATE_SCRIPT.setLocation(new ClassPathResource("seckill_compensate.lua"));
        SECKILL_COMPENSATE_SCRIPT.setResultType(Long.class);
    }

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${benchmark.kafka.topic:order.created.bench}")
    private String benchmarkKafkaTopic;

    @Override
    public BenchmarkSeckillResponse runA(Long voucherId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        BenchmarkSeckillResponse preCheck = preCheck(voucherId, "A", requestId);
        if (preCheck != null) {
            return preCheck;
        }

        RLock lock = redissonClient.getLock("lock:voucher:" + voucherId);
        boolean success = lock.tryLock();
        if (!success) {
            return BenchmarkSeckillResponse.fail("A", requestId, "LOCK_BUSY", "锁竞争中");
        }

        try {
            long orderId = redisIdWorker.nextId("order");
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            return persistSync(order, "A", requestId, false);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public BenchmarkSeckillResponse runB(Long voucherId, Long userId) {
        String requestId = UUID.randomUUID().toString();
        BenchmarkSeckillResponse preCheck = preCheck(voucherId, "B", requestId);
        if (preCheck != null) {
            return preCheck;
        }

        RLock lock = redissonClient.getLock("lock:" + userId + ":" + voucherId);
        boolean success = lock.tryLock();
        if (!success) {
            return BenchmarkSeckillResponse.fail("B", requestId, "LOCK_BUSY", "用户请求竞争中");
        }

        try {
            long orderId = redisIdWorker.nextId("order");
            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            return persistSync(order, "B", requestId, false);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public BenchmarkSeckillResponse runC(Long voucherId, Long userId) {
        String requestId = UUID.randomUUID().toString();

        RLock lock = redissonClient.getLock("lock:" + userId + ":" + voucherId);
        boolean lockSuccess = lock.tryLock();
        if (!lockSuccess) {
            return BenchmarkSeckillResponse.fail("C", requestId, "LOCK_BUSY", "用户请求竞争中");
        }

        try {
            long orderId = redisIdWorker.nextId("order");
            Long lua = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId));

            if (lua == null) {
                return BenchmarkSeckillResponse.fail("C", requestId, "LUA_ERROR", "Lua执行失败");
            }
            if (lua.intValue() == 1) {
                return BenchmarkSeckillResponse.fail("C", requestId, "NO_STOCK", "库存不足");
            }
            if (lua.intValue() == 2) {
                return BenchmarkSeckillResponse.fail("C", requestId, "DUPLICATE_ORDER", "用户已下单");
            }
            if (lua.intValue() != 0) {
                return BenchmarkSeckillResponse.fail("C", requestId, "LUA_ERROR", "Lua返回异常");
            }

            VoucherOrder order = new VoucherOrder();
            order.setId(orderId);
            order.setUserId(userId);
            order.setVoucherId(voucherId);
            return persistSync(order, "C", requestId, true);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public BenchmarkSeckillResponse runD(Long voucherId, Long userId) {
        String requestId = UUID.randomUUID().toString();

        RLock lock = redissonClient.getLock("lock:" + userId + ":" + voucherId);
        boolean lockSuccess = lock.tryLock();
        if (!lockSuccess) {
            return BenchmarkSeckillResponse.fail("D", requestId, "LOCK_BUSY", "用户请求竞争中");
        }

        try {
            long orderId = redisIdWorker.nextId("order");
            Long lua = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId));
            if (lua == null) {
                return BenchmarkSeckillResponse.fail("D", requestId, "LUA_ERROR", "Lua执行失败");
            }
            if (lua.intValue() == 1) {
                return BenchmarkSeckillResponse.fail("D", requestId, "NO_STOCK", "库存不足");
            }
            if (lua.intValue() == 2) {
                return BenchmarkSeckillResponse.fail("D", requestId, "DUPLICATE_ORDER", "用户已下单");
            }
            if (lua.intValue() != 0) {
                return BenchmarkSeckillResponse.fail("D", requestId, "LUA_ERROR", "Lua返回异常");
            }

            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(orderId);
            event.setUserId(userId);
            event.setVoucherId(voucherId);
            event.setRequestId(requestId);

            try {
                String key = userId + ":" + voucherId;
                String payload = objectMapper.writeValueAsString(event);
                kafkaTemplate.send(benchmarkKafkaTopic, key, payload).addCallback(
                        result -> {
                        },
                        ex -> {
                            log.error("Benchmark D异步发送Kafka失败, orderId={}, voucherId={}, userId={}",
                                    orderId, voucherId, userId, ex);
                            compensateReservation(voucherId, userId);
                        });
            } catch (Exception e) {
                log.error("Benchmark D发送Kafka失败, orderId={}, voucherId={}, userId={}",
                        orderId, voucherId, userId, e);
                compensateReservation(voucherId, userId);
                return BenchmarkSeckillResponse.fail("D", requestId, "MQ_ERROR", "消息投递失败");
            }

            return BenchmarkSeckillResponse.ok("D", requestId, orderId, "下单受理成功");
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    private BenchmarkSeckillResponse persistSync(VoucherOrder order,
                                                 String variant,
                                                 String requestId,
                                                 boolean compensateRedisOnFailure) {
        VoucherOrderTxService.OrderProcessResult result = voucherOrderTxService.processOrder(
                order,
                compensateRedisOnFailure,
                false);

        if (result == VoucherOrderTxService.OrderProcessResult.SUCCESS) {
            return BenchmarkSeckillResponse.ok(variant, requestId, order.getId(), "下单成功");
        }
        if (result == VoucherOrderTxService.OrderProcessResult.NO_STOCK) {
            return BenchmarkSeckillResponse.fail(variant, requestId, "NO_STOCK", "库存不足");
        }
        if (result == VoucherOrderTxService.OrderProcessResult.DUPLICATE_ORDER) {
            return BenchmarkSeckillResponse.fail(variant, requestId, "DUPLICATE_ORDER", "用户已下单");
        }
        return BenchmarkSeckillResponse.fail(variant, requestId, "DB_ERROR", "数据库写入失败");
    }

    private BenchmarkSeckillResponse preCheck(Long voucherId, String variant, String requestId) {
        SeckillVoucher voucher = seckillVoucherService.getById(voucherId);
        if (voucher == null) {
            return BenchmarkSeckillResponse.fail(variant, requestId, "VOUCHER_NOT_FOUND", "秒杀券不存在");
        }
        LocalDateTime now = LocalDateTime.now();
        if (voucher.getBeginTime().isAfter(now)) {
            return BenchmarkSeckillResponse.fail(variant, requestId, "SALE_NOT_STARTED", "秒杀未开始");
        }
        if (voucher.getEndTime().isBefore(now)) {
            return BenchmarkSeckillResponse.fail(variant, requestId, "SALE_ENDED", "秒杀已结束");
        }
        return null;
    }

    private void compensateReservation(Long voucherId, Long userId) {
        stringRedisTemplate.execute(SECKILL_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
    }
}
