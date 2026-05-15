package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.dto.Result;
import com.hmdp.dto.UserDTO;
import com.hmdp.dto.VoucherOrderStatusDTO;
import com.hmdp.entity.OrderFailed;
import com.hmdp.entity.SeckillVoucher;
import com.hmdp.entity.Voucher;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.service.ISeckillVoucherService;
import com.hmdp.service.IVoucherOrderService;
import com.hmdp.service.IVoucherService;
import com.hmdp.utils.RedisConstants;
import com.hmdp.utils.RedisIdWorker;
import com.hmdp.utils.UserHolder;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

@Service
@Slf4j
public class VoucherOrderServiceImpl extends ServiceImpl<VoucherOrderMapper, VoucherOrder>
        implements IVoucherOrderService {

    @Resource
    private ISeckillVoucherService seckillVoucherService;

    @Resource
    private IVoucherService voucherService;

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private PaymentSimulationService paymentSimulationService;

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private RedisIdWorker redisIdWorker;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Resource
    private OrderFailedMapper orderFailedMapper;

    @Value("${app.kafka.topic.order-created:order.created}")
    private String orderCreatedTopic;

    private static final DefaultRedisScript<Long> SECKILL_SCRIPT;

    static {
        SECKILL_SCRIPT = new DefaultRedisScript<>();
        SECKILL_SCRIPT.setLocation(new ClassPathResource("seckill.lua"));
        SECKILL_SCRIPT.setResultType(Long.class);
    }

    @Override
    public Result SeckillVoucherWithLua(Long voucherId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        long orderId = redisIdWorker.nextId("order");
        String requestId = UUID.randomUUID().toString();
        long nowTs = System.currentTimeMillis();

        String stockKey = RedisConstants.SECKILL_STOCK_KEY + voucherId;
        String orderKey = RedisConstants.SECKILL_ORDER_KEY + voucherId;
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        String pendingIndexKey = RedisConstants.SECKILL_PENDING_INDEX_KEY;

        Long luaFlag = stringRedisTemplate.execute(
                SECKILL_SCRIPT,
                Arrays.asList(stockKey, orderKey, pendingKey, pendingIndexKey),
                userId.toString(),
                voucherId.toString(),
                String.valueOf(orderId),
                requestId,
                String.valueOf(nowTs));

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
        if (code == 3) {
            return Result.fail("系统暂未就绪，请稍后重试");
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
            kafkaTemplate.send(orderCreatedTopic, userId.toString(), payload)
                    .addCallback(
                            ok -> {
                                if (log.isDebugEnabled()) {
                                    log.debug("Kafka 投递成功, orderId={}", orderId);
                                }
                            },
                            ex -> log.warn("Kafka 投递失败, orderId={}, voucherId={}, userId={}，依赖 reconciler 兜底",
                                    orderId, voucherId, userId, ex));
        } catch (Exception e) {
            // 序列化等本地异常：消息没出去，pending 还在，reconciler 会重投
            log.error("Kafka 消息序列化或提交失败, orderId={}, voucherId={}, userId={}，依赖 reconciler 兜底",
                    orderId, voucherId, userId, e);
        }

        return Result.ok(String.valueOf(orderId));
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

    @Override
    public Result queryOrder(Long orderId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        VoucherOrder order = getById(orderId);
        if (order != null) {
            if (!userId.equals(order.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(toStatusDTO(order));
        }

        // DB 没有订单 → 看是否已被 DLT 判定失败
        OrderFailed failed = orderFailedMapper.selectById(orderId);
        if (failed != null) {
            if (!userId.equals(failed.getUserId())) {
                return Result.fail("无权查看该订单");
            }
            return Result.ok(toFailedDTO(failed));
        }

        // 仍在处理中 (Redis pending 还在或消息在途)
        VoucherOrderStatusDTO pending = new VoucherOrderStatusDTO();
        pending.setId(String.valueOf(orderId));
        pending.setStatus(0);
        pending.setStatusText("创建中");
        return Result.ok(pending);
    }

    @Override
    public Result queryMyOrders() {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        List<VoucherOrderStatusDTO> orders = list(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId)
                .orderByDesc("create_time")
                .last("LIMIT 20"))
                .stream()
                .map(this::toStatusDTO)
                .collect(Collectors.toList());
        return Result.ok(orders);
    }

    @Override
    public Result simulatePay(Long orderId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单仍在创建中，请稍后再试");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权操作该订单");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            return Result.fail("只有未支付订单可以模拟支付");
        }

        boolean success = paymentSimulationService.simulatePaymentSuccess(orderId, userId);
        if (!success) {
            return Result.fail("支付状态更新失败，请刷新后重试");
        }
        return Result.ok(toStatusDTO(getById(orderId)));
    }

    @Override
    public Result cancelOrder(Long orderId) {
        Long userId = currentUserId();
        if (userId == null) {
            return Result.fail("用户未登录");
        }

        VoucherOrder order = getById(orderId);
        if (order == null) {
            return Result.fail("订单仍在创建中，请稍后再试");
        }
        if (!userId.equals(order.getUserId())) {
            return Result.fail("无权操作该订单");
        }
        if (!Integer.valueOf(1).equals(order.getStatus())) {
            return Result.fail("只有未支付订单可以取消");
        }

        boolean success = voucherOrderTxService.cancelUnpaidOrder(orderId, userId);
        if (!success) {
            return Result.fail("取消失败，请刷新后重试");
        }
        return Result.ok(toStatusDTO(getById(orderId)));
    }

    private Long currentUserId() {
        UserDTO user = UserHolder.getUser();
        return user == null ? null : user.getId();
    }

    private VoucherOrderStatusDTO toStatusDTO(VoucherOrder order) {
        VoucherOrderStatusDTO dto = new VoucherOrderStatusDTO();
        dto.setId(String.valueOf(order.getId()));
        dto.setVoucherId(order.getVoucherId());
        dto.setStatus(order.getStatus());
        dto.setStatusText(statusText(order.getStatus()));
        dto.setCreateTime(order.getCreateTime());
        dto.setPayTime(order.getPayTime());
        dto.setUseTime(order.getUseTime());
        dto.setRefundTime(order.getRefundTime());
        dto.setUpdateTime(order.getUpdateTime());

        Voucher voucher = voucherService.getById(order.getVoucherId());
        dto.setVoucherTitle(voucher == null ? "秒杀券" : voucher.getTitle());
        return dto;
    }

    private VoucherOrderStatusDTO toFailedDTO(OrderFailed failed) {
        VoucherOrderStatusDTO dto = new VoucherOrderStatusDTO();
        dto.setId(String.valueOf(failed.getOrderId()));
        dto.setVoucherId(failed.getVoucherId());
        dto.setStatus(-1);
        dto.setStatusText("下单失败");
        dto.setCreateTime(failed.getCreateTime());

        Voucher voucher = voucherService.getById(failed.getVoucherId());
        dto.setVoucherTitle(voucher == null ? "秒杀券" : voucher.getTitle());
        return dto;
    }

    private String statusText(Integer status) {
        if (status == null) {
            return "未知";
        }
        switch (status) {
            case 0:
                return "创建中";
            case 1:
                return "未支付";
            case 2:
                return "已支付";
            case 3:
                return "已核销";
            case 4:
                return "已取消";
            case 5:
                return "退款中";
            case 6:
                return "已退款";
            default:
                return "未知";
        }
    }
}
