package com.hmdp.service.impl;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
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

import jakarta.annotation.Resource;
import java.util.Map;
import java.util.Set;

/**
 * 秒杀链路兜底对账服务。
 * 周期扫描 seckill:pending:index 中 age > 阈值的 pending 订单，
 * 按"DB 已存在 / DLT failed 已存在 / DLT fence / 都没有"四分支自愈。
 *
 * index member 格式为 {voucherId}:{orderId}:{userId}（兼容旧格式 {voucherId}:{orderId}）。
 * pending Hash 丢失（TTL 过期或 Redis 丢数据）时，靠 member 里的 userId 仍可重投或释放名额。
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

    @Value("${app.reconciler.release-retry-batch-size:100}")
    private int releaseRetryBatchSize;

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
        int fenced = 0;
        int skip = 0;

        for (String member : candidates) {
            PendingRef ref = PendingRef.parse(member);
            if (ref == null) {
                log.warn("pending index 出现非法 member, 移除: {}", member);
                removeIndex(member);
                continue;
            }

            try {
                ReconcileOutcome outcome = handleOne(ref);
                switch (outcome) {
                    case CLEANUP:    cleanup++;   break;
                    case RELEASE:    release++;   break;
                    case REPUBLISH:  republish++; break;
                    case FENCED:     fenced++;    break;
                    case SKIP:       skip++;      break;
                }
            } catch (Exception e) {
                log.warn("reconciler 处理失败, member={}", member, e);
                skip++;
            }
        }

        log.info("reconciler 完成, scanned={}, republish={}, cleanup={}, release={}, fenced={}, skip={}",
                candidates.size(), republish, cleanup, release, fenced, skip);
    }

    @Scheduled(fixedDelayString = "${app.reconciler.release-retry-fixed-delay-ms:60000}")
    public void retryRedisReleases() {
        int released = voucherOrderTxService.retryPendingRedisReleases(releaseRetryBatchSize);
        if (released > 0) {
            log.info("Redis 名额释放重试完成, released={}", released);
        }
    }

    private ReconcileOutcome handleOne(PendingRef ref) {
        Long voucherId = ref.voucherId;
        Long orderId = ref.orderId;

        // 分支一：DB 已存在订单 → consumer afterCommit 失败留下的脏 pending，清理
        VoucherOrder existing = voucherOrderMapper.selectById(orderId);
        if (existing != null) {
            cleanupPending(ref);
            return ReconcileOutcome.CLEANUP;
        }

        // 分支二：DLT failed 表已有 → DLT release 没成功，补释放
        if (orderFailedMapper.selectById(orderId) != null) {
            return releaseFailedOrder(ref);
        }

        // 分支三：DLT consumer 已经拿到死信但 failed 事务尚未提交，避免把失败订单重投复活
        if (isDltFenced(orderId)) {
            log.info("pending 已进入 DLT 处理窗口，跳过重投, voucherId={}, orderId={}", voucherId, orderId);
            return ReconcileOutcome.FENCED;
        }

        // 分支四：DB 没有 + failed 没有 + DLT fence 没有 → 重投 Kafka
        Map<Object, Object> pending = stringRedisTemplate.opsForHash().entries(pendingKey(ref));
        boolean pendingMissing = pending == null || pending.isEmpty();
        if (pendingMissing && ref.userId == null) {
            // 旧格式 member 且 pending Hash 也丢了：没有 userId，既不能重投也不能释放，只能清 index
            removeIndex(ref.member);
            log.warn("pending Hash 缺失且 index 无 userId，无法重投, voucherId={}, orderId={}", voucherId, orderId);
            return ReconcileOutcome.SKIP;
        }

        Long userId = pendingMissing ? ref.userId : firstNonNull(parseUserId(pending), ref.userId);
        if (userId == null) {
            log.warn("pending 数据残缺, 跳过: voucherId={}, orderId={}, pending={}", voucherId, orderId, pending);
            return ReconcileOutcome.SKIP;
        }
        String requestId = pendingMissing ? null : stringValue(pending.get("requestId"));
        if (requestId == null) {
            requestId = "reconcile-" + orderId;
        }
        if (pendingMissing) {
            // Lua 已经为这个用户扣过名额，按 index 里的 userId 重投；原消息若仍在途，DB 主键会去重
            log.warn("pending Hash 缺失，按 index 中的 userId 重投, voucherId={}, orderId={}, userId={}",
                    voucherId, orderId, userId);
        }

        OrderCreatedEvent event = new OrderCreatedEvent();
        event.setOrderId(orderId);
        event.setUserId(userId);
        event.setVoucherId(voucherId);
        event.setRequestId(requestId);

        try {
            String payload = objectMapper.writeValueAsString(event);
            var future = kafkaTemplate.send(orderCreatedTopic, userId.toString(), payload);
            if (future != null) {
                final Long uid = userId;
                future.whenComplete((result, ex) -> {
                    if (ex != null) {
                        log.error("reconciler 重投真实失败, orderId={}, voucherId={}, userId={}",
                                orderId, voucherId, uid, ex);
                        return;
                    }
                    log.info("reconciler 重投已被 broker 接收, orderId={}, voucherId={}, userId={}",
                            orderId, voucherId, uid);
                });
            }
            log.info("reconciler 已提交重投请求, orderId={}, voucherId={}, userId={}",
                    orderId, voucherId, userId);
            return ReconcileOutcome.REPUBLISH;
        } catch (Exception e) {
            log.warn("reconciler 重投失败, orderId={}", orderId, e);
            return ReconcileOutcome.SKIP;
        }
    }

    /**
     * 订单已被 DLT 判定失败，但 Redis 名额还没释放。
     * pending 在：正常释放（forceUserRelease=false，脚本会校验 pending 里的 userId）。
     * pending 不在：用 index 里的 userId 强制释放；但如果该用户对这张券还有别的有效订单
     *   （例如名额早已释放、用户又成功买了一单），就不能把他移出一人一单集合，只清 index。
     */
    private ReconcileOutcome releaseFailedOrder(PendingRef ref) {
        Map<Object, Object> pending = stringRedisTemplate.opsForHash().entries(pendingKey(ref));
        Long pendingUserId = parseUserId(pending);
        if (pendingUserId != null) {
            voucherOrderTxService.releaseRedisReservation(ref.voucherId, pendingUserId, ref.orderId, false);
            return ReconcileOutcome.RELEASE;
        }

        if (ref.userId == null) {
            // 旧格式 member + pending 已不在：无法定位用户，只清 index
            removeIndex(ref.member);
            return ReconcileOutcome.CLEANUP;
        }

        if (hasOtherActiveOrder(ref.userId, ref.voucherId, ref.orderId)) {
            removeIndex(ref.member);
            log.info("失败订单的用户已有其他有效订单，不释放名额，只清 index, voucherId={}, orderId={}, userId={}",
                    ref.voucherId, ref.orderId, ref.userId);
            return ReconcileOutcome.CLEANUP;
        }

        log.warn("失败订单的 pending Hash 已缺失，按 index 中的 userId 释放名额, voucherId={}, orderId={}, userId={}",
                ref.voucherId, ref.orderId, ref.userId);
        voucherOrderTxService.releaseRedisReservation(ref.voucherId, ref.userId, ref.orderId, true);
        return ReconcileOutcome.RELEASE;
    }

    private boolean hasOtherActiveOrder(Long userId, Long voucherId, Long orderId) {
        Long count = voucherOrderMapper.selectCount(new QueryWrapper<VoucherOrder>()
                .eq("user_id", userId)
                .eq("voucher_id", voucherId)
                .in("status", 1, 2, 3, 5)
                .ne("id", orderId));
        return count != null && count > 0;
    }

    private void cleanupPending(PendingRef ref) {
        stringRedisTemplate.delete(pendingKey(ref));
        removeIndex(ref.member);
    }

    private void removeIndex(String member) {
        stringRedisTemplate.opsForZSet().remove(RedisConstants.SECKILL_PENDING_INDEX_KEY, member);
    }

    private static String pendingKey(PendingRef ref) {
        return RedisConstants.SECKILL_PENDING_KEY + ref.voucherId + ":" + ref.orderId;
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

    private static Long firstNonNull(Long a, Long b) {
        return a != null ? a : b;
    }

    private String stringValue(Object raw) {
        return raw == null ? null : raw.toString();
    }

    private boolean isDltFenced(Long orderId) {
        return Boolean.TRUE.equals(stringRedisTemplate.hasKey(RedisConstants.SECKILL_DLT_FENCE_KEY + orderId));
    }

    private enum ReconcileOutcome {
        CLEANUP, RELEASE, REPUBLISH, FENCED, SKIP
    }

    /** 解析后的 index member：{voucherId}:{orderId}[:{userId}] */
    static final class PendingRef {
        final String member;
        final Long voucherId;
        final Long orderId;
        final Long userId;

        private PendingRef(String member, Long voucherId, Long orderId, Long userId) {
            this.member = member;
            this.voucherId = voucherId;
            this.orderId = orderId;
            this.userId = userId;
        }

        static PendingRef parse(String member) {
            if (member == null) {
                return null;
            }
            String[] parts = member.split(":", -1);
            if (parts.length != 2 && parts.length != 3) {
                return null;
            }
            try {
                Long voucherId = Long.parseLong(parts[0]);
                Long orderId = Long.parseLong(parts[1]);
                Long userId = parts.length == 3 ? Long.parseLong(parts[2]) : null;
                return new PendingRef(member, voucherId, orderId, userId);
            } catch (NumberFormatException e) {
                return null;
            }
        }
    }
}
