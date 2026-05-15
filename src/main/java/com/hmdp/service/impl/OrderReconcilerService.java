package com.hmdp.service.impl;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hmdp.entity.VoucherOrder;
import com.hmdp.mapper.OrderFailedMapper;
import com.hmdp.mapper.VoucherOrderMapper;
import com.hmdp.mq.OrderCreatedEvent;
import com.hmdp.utils.RedisConstants;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import javax.annotation.Resource;
import java.util.Map;
import java.util.Set;

/**
 * 秒杀链路兜底对账服务。
 * 周期扫描 seckill:pending:index 中 age > 阈值的 pending 订单，
 * 按"DB 已存在 / DLT failed 已存在 / 都没有"三分支自愈。
 */
@Slf4j
@Service
public class OrderReconcilerService {

    @Resource
    private StringRedisTemplate stringRedisTemplate;

    @Resource
    private VoucherOrderMapper voucherOrderMapper;

    @Resource
    private OrderFailedMapper orderFailedMapper;

    @Resource
    private VoucherOrderTxService voucherOrderTxService;

    @Resource
    private KafkaTemplate<String, String> kafkaTemplate;

    @Resource
    private ObjectMapper objectMapper;

    @Value("${app.kafka.topic.order-created:order.created}")
    private String orderCreatedTopic;

    @Value("${app.reconciler.age-threshold-seconds:90}")
    private long ageThresholdSeconds;

    @Value("${app.reconciler.batch-size:200}")
    private int batchSize;

    @Scheduled(fixedDelayString = "${app.reconciler.fixed-delay-ms:60000}")
    public void reconcile() {
        long now = System.currentTimeMillis();
        long ceiling = now - ageThresholdSeconds * 1000L;

        Set<String> candidates = stringRedisTemplate.opsForZSet()
                .rangeByScore(RedisConstants.SECKILL_PENDING_INDEX_KEY, 0, ceiling, 0, batchSize);
        if (candidates == null || candidates.isEmpty()) {
            return;
        }

        int republish = 0;
        int cleanup = 0;
        int release = 0;
        int skip = 0;

        for (String member : candidates) {
            // member 格式: "{voucherId}:{orderId}"
            int sep = member.indexOf(':');
            if (sep <= 0) {
                log.warn("pending index 出现非法 member, 跳过: {}", member);
                stringRedisTemplate.opsForZSet().remove(RedisConstants.SECKILL_PENDING_INDEX_KEY, member);
                continue;
            }
            Long voucherId;
            Long orderId;
            try {
                voucherId = Long.parseLong(member.substring(0, sep));
                orderId = Long.parseLong(member.substring(sep + 1));
            } catch (NumberFormatException e) {
                log.warn("pending index member 解析失败, 跳过: {}", member);
                stringRedisTemplate.opsForZSet().remove(RedisConstants.SECKILL_PENDING_INDEX_KEY, member);
                continue;
            }

            try {
                ReconcileOutcome outcome = handleOne(voucherId, orderId);
                switch (outcome) {
                    case CLEANUP:    cleanup++;   break;
                    case RELEASE:    release++;   break;
                    case REPUBLISH:  republish++; break;
                    case SKIP:       skip++;      break;
                }
            } catch (Exception e) {
                log.warn("reconciler 处理失败, voucherId={}, orderId={}", voucherId, orderId, e);
                skip++;
            }
        }

        log.info("reconciler 完成, scanned={}, republish={}, cleanup={}, release={}, skip={}",
                candidates.size(), republish, cleanup, release, skip);
    }

    private ReconcileOutcome handleOne(Long voucherId, Long orderId) {
        // 分支一：DB 已存在订单 → consumer afterCommit 失败留下的脏 pending，清理
        VoucherOrder existing = voucherOrderMapper.selectById(orderId);
        if (existing != null) {
            cleanupPending(voucherId, orderId);
            return ReconcileOutcome.CLEANUP;
        }

        // 分支二：DLT failed 表已有 → DLT release 没成功，补释放
        if (orderFailedMapper.selectById(orderId) != null) {
            String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
            Map<Object, Object> pending = stringRedisTemplate.opsForHash().entries(pendingKey);
            Long userId = parseUserId(pending);
            if (userId == null) {
                // pending 已经不在，只清 index
                stringRedisTemplate.opsForZSet().remove(
                        RedisConstants.SECKILL_PENDING_INDEX_KEY, voucherId + ":" + orderId);
                return ReconcileOutcome.CLEANUP;
            }
            voucherOrderTxService.releaseRedisReservation(voucherId, userId, orderId, false);
            return ReconcileOutcome.RELEASE;
        }

        // 分支三：DB 没有 + failed 没有 → 重投 Kafka
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        Map<Object, Object> pending = stringRedisTemplate.opsForHash().entries(pendingKey);
        if (pending == null || pending.isEmpty()) {
            // pending Hash 也丢了 (TTL 过期或 Redis 异常)，无法重投，清 index
            stringRedisTemplate.opsForZSet().remove(
                    RedisConstants.SECKILL_PENDING_INDEX_KEY, voucherId + ":" + orderId);
            log.warn("pending Hash 缺失，无法重投, voucherId={}, orderId={}", voucherId, orderId);
            return ReconcileOutcome.SKIP;
        }

        Long userId = parseUserId(pending);
        String requestId = stringValue(pending.get("requestId"));
        if (userId == null || requestId == null) {
            log.warn("pending 数据残缺, 跳过: voucherId={}, orderId={}, pending={}",
                    voucherId, orderId, pending);
            return ReconcileOutcome.SKIP;
        }

        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setVoucherId(voucherId);
        event.setRequestId(requestId);

        try {
            String payload = objectMapper.writeValueAsString(event);
            kafkaTemplate.send(orderCreatedTopic, userId.toString(), payload);
            log.info("reconciler 重投, orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId);
            return ReconcileOutcome.REPUBLISH;
        } catch (Exception e) {
            log.warn("reconciler 重投失败, orderId={}", orderId, e);
            return ReconcileOutcome.SKIP;
        }
    }

    private void cleanupPending(Long voucherId, Long orderId) {
        String pendingKey = RedisConstants.SECKILL_PENDING_KEY + voucherId + ":" + orderId;
        String indexMember = voucherId + ":" + orderId;
        stringRedisTemplate.delete(pendingKey);
        stringRedisTemplate.opsForZSet().remove(RedisConstants.SECKILL_PENDING_INDEX_KEY, indexMember);
    }

    private Long parseUserId(Map<Object, Object> pending) {
        Object raw = pending == null ? null : pending.get("userId");
        if (raw == null) return null;
        try {
            return Long.parseLong(raw.toString());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private String stringValue(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private enum ReconcileOutcome {
        CLEANUP, RELEASE, REPUBLISH, SKIP
    }
}
