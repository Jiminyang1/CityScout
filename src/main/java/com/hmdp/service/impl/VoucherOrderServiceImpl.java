package com.hmdp.service.impl;

import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import org.redisson.api.RLock;
import org.redisson.api.RedissonClient;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Collections;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

@Service
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private RedissonClient redissonClient;

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${app.kafka.topic.order-created:order.created}")
    private String orderCreatedTopic;

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

    @Override
    public Result SeckillVoucherWithLua(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        long orderId = redisIdWorker.nextId("order");
        String requestId = UUID.randomUUID().toString();

        String lockName = RedisConstants.LOCK_ORDER_KEY + userId + ":" + voucherId;
        RLock lock = redissonClient.getLock(lockName);
        boolean lockSuccess = lock.tryLock();
        if (!lockSuccess) {
            return Result.fail("请求过于频繁，请稍后再试");
        }

        try {
            Long luaFlag = stringRedisTemplate.execute(
                    SECKILL_SCRIPT,
                    Collections.emptyList(),
                    voucherId.toString(),
                    userId.toString(),
                    String.valueOf(orderId));
            if (luaFlag == null) {
                return Result.fail("下单失败");
            }

            int code = luaFlag.intValue();
            if (code == 1) {
                return Result.fail("库存不足");
            }
            if (code == 2) {
                return Result.fail("用户已下单");
            }
            if (code != 0) {
                return Result.fail("下单失败");
            }

            OrderCreatedEvent event = new OrderCreatedEvent();
            event.setOrderId(orderId);
            event.setUserId(userId);
            event.setVoucherId(voucherId);
            event.setRequestId(requestId);

            try {
                String payload = objectMapper.writeValueAsString(event);
                String key = userId + ":" + voucherId;
                kafkaTemplate.send(orderCreatedTopic, key, payload)
                        .get(2, TimeUnit.SECONDS);
            } catch (Exception e) {
                log.error("发送Kafka订单事件失败, orderId=" + orderId
                        + ", userId=" + userId + ", voucherId=" + voucherId + "，执行补偿", e);
                compensateReservation(voucherId, userId);
                return Result.fail("系统繁忙，请稍后再试");
            }

            return Result.ok(orderId);
        } finally {
            if (lock.isHeldByCurrentThread()) {
                lock.unlock();
            }
        }
    }

    @Override
    public Result SeckillVoucher(Long voucherId) {
        SeckillVoucher seckillVoucher = seckillVoucherService.getById(voucherId);
        if (seckillVoucher == null) {
            return Result.fail("秒杀券不存在");
        }
        if (seckillVoucher.getBeginTime().isAfter(LocalDateTime.now())) {
            return Result.fail("秒杀未开始");
        }
        if (seckillVoucher.getEndTime().isBefore(LocalDateTime.now())) {
            return Result.fail("秒杀已结束");
        }
        return SeckillVoucherWithLua(voucherId);
    }

    @Transactional
    @Override
    public Result createVoucherOrder(Long voucherId) {
        Long userId = UserHolder.getUser().getId();
        Integer count = query().eq("user_id", userId).eq("voucher_id", voucherId).count();
        if (count > 0) {
            return Result.fail("用户已下单");
        }

        boolean success = seckillVoucherService.update()
                .setSql("stock = stock - 1")
                .eq("voucher_id", voucherId)
                .gt("stock", 0)
                .update();
        if (!success) {
            return Result.fail("库存不足");
        }

        VoucherOrder voucherOrder = new VoucherOrder();
        long orderId = redisIdWorker.nextId("order");
        voucherOrder.setId(orderId);
        voucherOrder.setUserId(userId);
        voucherOrder.setVoucherId(voucherId);
        save(voucherOrder);
        return Result.ok(orderId);
    }

    @Override
    public void handleRealOrderCreation(VoucherOrder voucherOrder) {
        voucherOrderTxService.handleRealOrderCreation(voucherOrder);
    }

    private void compensateReservation(Long voucherId, Long userId) {
        stringRedisTemplate.execute(
                SECKILL_COMPENSATE_SCRIPT,
                Collections.emptyList(),
                voucherId.toString(),
                userId.toString());
    }
}
